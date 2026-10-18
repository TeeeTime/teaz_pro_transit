package pro.teaz.transit.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pro.teaz.transit.TransitProperties;

/**
 * Loads the GTFS timetable into the database: checks the feed URL for a new
 * version (on startup without a timetable, then on transit.gtfs.update-cron),
 * downloads the zip, streams its files into the inactive {@link Slot} and
 * switches over once everything is in. The old timetable keeps serving until
 * then, so a failed or running import never interrupts the departure boards.
 *
 * Only what departure boards need is kept (no shapes, transfers, fares).
 * stop_times.txt must list each trip's stops together, as gtfs.de does; that's
 * how each trip's last stop is found without holding all trips in memory.
 */
@Component
public class FeedImporter {

	/** Source name of the bundled feed (src/main/resources/sample-feed). */
	public static final String SAMPLE = "sample";
	// Bump when the bundled sample feed changes, so dev databases re-import it.
	private static final String SAMPLE_VERSION = "sample-2";

	private static final Logger log = LoggerFactory.getLogger(FeedImporter.class);
	private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	/** What the importer is doing right now, for /api/feed. */
	public record Status(boolean running, Instant lastCheck, String lastError) {
	}

	private final DataSource dataSource;
	private final FeedState feed;
	private final TransitProperties.Gtfs settings;
	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(30))
			.build();

	private final AtomicBoolean running = new AtomicBoolean();
	private volatile Instant lastCheck;
	private volatile String lastError;

	FeedImporter(DataSource dataSource, FeedState feed, TransitProperties properties) {
		this.dataSource = dataSource;
		this.feed = feed;
		this.settings = properties.gtfs();
	}

	public Status status() {
		return new Status(running.get(), lastCheck, lastError);
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		if (!settings.importOnStartup()) return;
		FeedState.Active active = feed.active();
		boolean sampleOutdated = settings.useSample() && (active == null || !SAMPLE_VERSION.equals(active.version()));
		if (active == null || sampleOutdated) {
			Thread.ofVirtual().name("gtfs-import").start(this::update);
		}
	}

	@Scheduled(cron = "${transit.gtfs.update-cron:0 30 4 * * *}", zone = "${transit.timezone:Europe/Berlin}")
	void onSchedule() {
		update();
	}

	/**
	 * Imports the feed if there's a new version. Returns right away (false) if
	 * an import is already running. Failures are logged and kept for /api/feed.
	 */
	public boolean update() {
		if (!running.compareAndSet(false, true)) return false;
		try {
			lastCheck = Instant.now();
			if (settings.useSample()) {
				FeedState.Active active = feed.active();
				if (active == null || !SAMPLE_VERSION.equals(active.version())) {
					importFeed(GtfsSource.classpath("sample-feed"), SAMPLE_VERSION, SAMPLE);
				}
			} else {
				downloadAndImport(settings.feedUrl());
			}
			lastError = null;
			return true;
		} catch (Exception e) {
			log.error("GTFS import failed, keeping the current timetable", e);
			lastError = e.getMessage();
			return true;
		} finally {
			running.set(false);
		}
	}

	private void downloadAndImport(String url) throws IOException, InterruptedException, SQLException {
		FeedState.Active active = feed.active();
		if (url.startsWith("file:")) {
			// HttpClient doesn't do file: URLs; handy for testing a downloaded feed locally.
			Path file = Path.of(URI.create(url));
			String version = Files.getLastModifiedTime(file).toString();
			if (active != null && url.equals(active.source()) && version.equals(active.version())) return;
			try (GtfsSource source = GtfsSource.zip(file)) {
				importFeed(source, version, url);
			}
			return;
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30));
		if (active != null && url.equals(active.source()) && active.version() != null) {
			request.header(active.version().startsWith("\"") || active.version().startsWith("W/")
					? "If-None-Match" : "If-Modified-Since", active.version());
		}

		Files.createDirectories(settings.workDirOrTemp());
		Path zip = Files.createTempFile(settings.workDirOrTemp(), "transit-feed-", ".zip");
		try {
			log.info("Checking {} for a new timetable", url);
			HttpResponse<Path> response = http.send(request.build(),
					HttpResponse.BodyHandlers.ofFile(zip));
			if (response.statusCode() == 304) {
				log.info("Timetable unchanged ({})", active.version());
				return;
			}
			if (response.statusCode() != 200) {
				throw new IOException("Feed download failed: HTTP " + response.statusCode());
			}
			String version = response.headers().firstValue("ETag")
					.or(() -> response.headers().firstValue("Last-Modified"))
					.orElse(Instant.now().toString());
			if (active != null && url.equals(active.source()) && version.equals(active.version())) {
				log.info("Timetable unchanged ({})", version);
				return;
			}
			log.info("Downloaded {} ({} MB), importing", url, Files.size(zip) / 1_000_000);
			try (GtfsSource source = GtfsSource.zip(zip)) {
				importFeed(source, version, url);
			}
		} finally {
			Files.deleteIfExists(zip);
		}
	}

	/** Visible for tests: imports synchronously, whatever version is loaded. */
	void importFeed(GtfsSource source, String version, String sourceName) throws IOException, SQLException {
		FeedState.Active active = feed.active();
		Slot target = active == null ? Slot.A : active.slot().other();
		long started = System.nanoTime();

		try (Connection connection = dataSource.getConnection();
				Connection sideConnection = dataSource.getConnection()) {
			connection.setAutoCommit(true);
			sideConnection.setAutoCommit(true);
			truncate(connection, target);
			execute(connection, "DROP INDEX IF EXISTS " + stopTimesIndex(target));

			importAgencies(source, connection, target);
			importRoutes(source, connection, target);
			importStops(source, connection, target);
			importTrips(source, connection, target);
			importCalendar(source, connection, target);
			importCalendarDates(source, connection, target);
			importStopTimes(source, connection, sideConnection, target);

			log.info("Indexing stop times");
			execute(connection, "CREATE INDEX " + stopTimesIndex(target) + " ON " + target.table("stop_times")
					+ " (stop_id, departure_secs)");
			if (isPostgres(connection)) {
				for (String table : Slot.TABLES) execute(connection, "ANALYZE " + target.table(table));
			}
		}

		feed.activate(target, version, sourceName);
		log.info("Timetable {} from {} is live (slot {}, {} s)", version, sourceName, target,
				Duration.ofNanos(System.nanoTime() - started).toSeconds());

		if (active != null) {
			try (Connection connection = dataSource.getConnection()) {
				truncate(connection, active.slot());
			}
		}
	}

	/** Must match the index in V1__init.sql. */
	private static String stopTimesIndex(Slot slot) {
		return slot.table("stop_times") + "_stop_dep";
	}

	private static void truncate(Connection connection, Slot slot) throws SQLException {
		for (String table : Slot.TABLES) execute(connection, "TRUNCATE TABLE " + slot.table(table));
	}

	private static void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static boolean isPostgres(Connection connection) throws SQLException {
		return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
	}

	private static GtfsTable table(GtfsSource source, String file, boolean required) throws IOException {
		InputStream in = source.open(file);
		if (in == null) {
			if (required) throw new IOException("GTFS feed lacks " + file);
			return null;
		}
		return new GtfsTable(in);
	}

	private static void importAgencies(GtfsSource source, Connection connection, Slot slot) throws IOException, SQLException {
		try (GtfsTable rows = table(source, "agency.txt", true);
				TableWriter out = TableWriter.open(connection, slot.table("agencies"), List.of("agency_id", "name"))) {
			int id = rows.column("agency_id"), name = rows.requiredColumn("agency_name");
			for (GtfsTable.Row row : rows) {
				// agency_id is optional for single-agency feeds; routes then omit it as well.
				out.row(row.get(id), row.get(name));
			}
			log.info("Imported {} agencies", out.rows());
		}
	}

	private static void importRoutes(GtfsSource source, Connection connection, Slot slot) throws IOException, SQLException {
		try (GtfsTable rows = table(source, "routes.txt", true);
				TableWriter out = TableWriter.open(connection, slot.table("routes"),
						List.of("route_id", "agency_id", "short_name", "long_name", "route_type"))) {
			int id = rows.requiredColumn("route_id"), agency = rows.column("agency_id"),
					shortName = rows.column("route_short_name"), longName = rows.column("route_long_name"),
					type = rows.requiredColumn("route_type");
			for (GtfsTable.Row row : rows) {
				out.row(row.get(id), row.get(agency), row.getOrNull(shortName), row.getOrNull(longName),
						parseInt(row.get(type), 3));
			}
			log.info("Imported {} routes", out.rows());
		}
	}

	private static void importStops(GtfsSource source, Connection connection, Slot slot) throws IOException, SQLException {
		try (GtfsTable rows = table(source, "stops.txt", true);
				TableWriter out = TableWriter.open(connection, slot.table("stops"),
						List.of("stop_id", "station_id", "name", "search_name", "lat", "lon", "platform", "is_station"))) {
			int id = rows.requiredColumn("stop_id"), name = rows.column("stop_name"), parent = rows.column("parent_station"),
					lat = rows.column("stop_lat"), lon = rows.column("stop_lon"), type = rows.column("location_type"),
					platform = rows.column("platform_code");
			for (GtfsTable.Row row : rows) {
				int locationType = parseInt(row.get(type), 0);
				// Entrances, generic nodes and boarding areas never have departures.
				if (locationType > 1 || row.get(name).isEmpty() || row.get(lat).isEmpty()) continue;
				String stopId = row.get(id);
				String parentId = locationType == 0 ? row.getOrNull(parent) : null;
				out.row(stopId, parentId != null ? parentId : stopId, row.get(name), searchName(row.get(name)),
						Double.parseDouble(row.get(lat)), Double.parseDouble(row.get(lon)),
						row.getOrNull(platform), parentId == null);
			}
			log.info("Imported {} stops", out.rows());
		}
	}

	private static void importTrips(GtfsSource source, Connection connection, Slot slot) throws IOException, SQLException {
		try (GtfsTable rows = table(source, "trips.txt", true);
				TableWriter out = TableWriter.open(connection, slot.table("trips"),
						List.of("trip_id", "route_id", "service_id", "headsign", "short_name"))) {
			int id = rows.requiredColumn("trip_id"), route = rows.requiredColumn("route_id"),
					service = rows.requiredColumn("service_id"), headsign = rows.column("trip_headsign"),
					shortName = rows.column("trip_short_name");
			for (GtfsTable.Row row : rows) {
				out.row(row.get(id), row.get(route), row.get(service), row.getOrNull(headsign), row.getOrNull(shortName));
			}
			log.info("Imported {} trips", out.rows());
		}
	}

	private static void importCalendar(GtfsSource source, Connection connection, Slot slot) throws IOException, SQLException {
		// Optional: some feeds define every service through calendar_dates.txt only.
		GtfsTable rows = table(source, "calendar.txt", false);
		if (rows == null) return;
		String[] weekdays = { "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday" };
		try (rows; TableWriter out = TableWriter.open(connection, slot.table("calendar"),
				List.of("service_id", "days", "start_date", "end_date"))) {
			int id = rows.requiredColumn("service_id"), start = rows.requiredColumn("start_date"),
					end = rows.requiredColumn("end_date");
			int[] dayColumns = new int[7];
			for (int d = 0; d < 7; d++) dayColumns[d] = rows.requiredColumn(weekdays[d]);
			for (GtfsTable.Row row : rows) {
				int days = 0;
				for (int d = 0; d < 7; d++) {
					if ("1".equals(row.get(dayColumns[d]))) days |= 1 << d;
				}
				out.row(row.get(id), (short) days, LocalDate.parse(row.get(start), GTFS_DATE),
						LocalDate.parse(row.get(end), GTFS_DATE));
			}
			log.info("Imported {} services", out.rows());
		}
	}

	private static void importCalendarDates(GtfsSource source, Connection connection, Slot slot) throws IOException, SQLException {
		GtfsTable rows = table(source, "calendar_dates.txt", false);
		if (rows == null) return;
		try (rows; TableWriter out = TableWriter.open(connection, slot.table("calendar_dates"),
				List.of("service_id", "service_date", "added"))) {
			int id = rows.requiredColumn("service_id"), date = rows.requiredColumn("date"),
					type = rows.requiredColumn("exception_type");
			for (GtfsTable.Row row : rows) {
				out.row(row.get(id), LocalDate.parse(row.get(date), GTFS_DATE), "1".equals(row.get(type)));
			}
			log.info("Imported {} service exceptions", out.rows());
		}
	}

	/**
	 * Stop times, plus two tables derived on the way: distinct headsigns (stored
	 * once, referenced by number) and each trip's last stop. The last stops go
	 * through a second connection, as one connection can only run one COPY.
	 */
	private static void importStopTimes(GtfsSource source, Connection connection, Connection sideConnection, Slot slot)
			throws IOException, SQLException {
		Map<String, Integer> headsigns = new HashMap<>();
		try (GtfsTable rows = table(source, "stop_times.txt", true);
				TableWriter out = TableWriter.open(connection, slot.table("stop_times"),
						List.of("trip_id", "stop_id", "stop_sequence", "departure_secs", "headsign_id", "no_pickup"));
				TableWriter ends = TableWriter.open(sideConnection, slot.table("trip_ends"),
						List.of("trip_id", "last_stop_id", "last_sequence"))) {
			int trip = rows.requiredColumn("trip_id"), stop = rows.requiredColumn("stop_id"),
					sequence = rows.requiredColumn("stop_sequence"), departure = rows.column("departure_time"),
					arrival = rows.column("arrival_time"), headsign = rows.column("stop_headsign"),
					pickup = rows.column("pickup_type");

			String currentTrip = null, lastStop = null;
			int lastSequence = Integer.MIN_VALUE;
			for (GtfsTable.Row row : rows) {
				String tripId = row.get(trip);
				if (!tripId.equals(currentTrip)) {
					if (currentTrip != null) ends.row(currentTrip, lastStop, lastSequence);
					currentTrip = tripId;
					lastSequence = Integer.MIN_VALUE;
				}
				int seq = Integer.parseInt(row.get(sequence));
				String stopId = row.get(stop);
				if (seq > lastSequence) {
					lastSequence = seq;
					lastStop = stopId;
				}

				String time = row.get(departure);
				if (time.isEmpty()) time = row.get(arrival);
				// Stops between timepoints may have no time; a board can't show those.
				if (time.isEmpty()) continue;
				String text = row.get(headsign);
				Integer headsignId = text.isEmpty() ? null : headsigns.computeIfAbsent(text, t -> headsigns.size() + 1);
				out.row(tripId, stopId, seq, parseTime(time), headsignId, "1".equals(row.get(pickup)));
				if (out.rows() % 5_000_000 == 0) log.info("Imported {} stop times", out.rows());
			}
			if (currentTrip != null) ends.row(currentTrip, lastStop, lastSequence);
			log.info("Imported {} stop times of {} trips", out.rows(), ends.rows());
		}

		try (TableWriter out = TableWriter.open(connection, slot.table("headsigns"), List.of("headsign_id", "text"))) {
			for (Map.Entry<String, Integer> entry : headsigns.entrySet()) out.row(entry.getValue(), entry.getKey());
		}
	}

	/** "25:10:00" -> seconds after the service day's start (GTFS allows hours past 24). */
	static int parseTime(String time) {
		int first = time.indexOf(':'), second = time.indexOf(':', first + 1);
		int hours = Integer.parseInt(time, 0, first, 10);
		int minutes = Integer.parseInt(time, first + 1, second < 0 ? time.length() : second, 10);
		int seconds = second < 0 ? 0 : Integer.parseInt(time, second + 1, time.length(), 10);
		return hours * 3600 + minutes * 60 + seconds;
	}

	/**
	 * What station search matches against: lower case, without accents, ß as ss,
	 * so "munster" finds "Münster". The search input is normalized the same way.
	 */
	public static String searchName(String name) {
		String decomposed = Normalizer.normalize(name.toLowerCase(Locale.GERMAN), Normalizer.Form.NFD);
		return decomposed.replaceAll("\\p{M}", "").replace("ß", "ss");
	}

	private static int parseInt(String value, int fallback) {
		if (value.isEmpty()) return fallback;
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}

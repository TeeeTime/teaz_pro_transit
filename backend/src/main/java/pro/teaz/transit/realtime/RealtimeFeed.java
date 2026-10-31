package pro.teaz.transit.realtime;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import pro.teaz.transit.TransitProperties;
import pro.teaz.transit.gtfs.FeedState;

/**
 * Keeps the latest GTFS-Realtime TripUpdates in memory, for the trips of the
 * {@link WatchedStations} only. The feed for all of Germany is several MB, so
 * it's only fetched while someone actually looks at departures (every
 * poll-interval, until idle-after without a request), not around the clock.
 *
 * The download goes to a file, never into memory, and is read from there one
 * entity at a time ({@link TripUpdates#read}). The file stays until the next
 * download, so a station that starts being watched gets its realtime right
 * away instead of with the next fetch.
 */
@Component
public class RealtimeFeed {

	private static final Logger log = LoggerFactory.getLogger(RealtimeFeed.class);

	private final TransitProperties.Realtime settings;
	private final Path file;
	private final FeedState feed;
	private final WatchedStations watched;
	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private final AtomicBoolean fetching = new AtomicBoolean();
	// Held while the file is replaced or read and current is swapped, so a
	// station added during a fetch doesn't get lost.
	private final Object swap = new Object();
	private volatile TripUpdates current;
	private volatile Instant lastDemand = Instant.EPOCH;
	private volatile Instant lastAttempt = Instant.EPOCH;
	// The last download in file: when (null before the first) and whether gzip-compressed.
	private volatile Instant fileFetchedAt;
	private boolean gzip;

	RealtimeFeed(TransitProperties properties, FeedState feed, WatchedStations watched) {
		this.settings = properties.realtime();
		this.file = properties.gtfs().workDirOrTemp().resolve("transit-realtime.pb");
		this.feed = feed;
		this.watched = watched;
	}

	public boolean enabled() {
		return settings.enabled();
	}

	/**
	 * The latest updates for the station's board, or null if realtime is off,
	 * not fetched yet or too old to trust. Counts as demand: watches the
	 * station, and starts fetching if nobody asked for a while.
	 */
	public TripUpdates current(FeedState.Active timetable, String stationId) {
		if (!settings.enabled()) return null;
		lastDemand = Instant.now();
		watch(timetable, stationId);
		if (stale(settings.pollInterval())) fetchInBackground();
		TripUpdates updates = current;
		return updates != null && !isOlderThan(updates.fetchedAt(), settings.maxAge()) ? updates : null;
	}

	/**
	 * Keeps realtime for the station from now on, e.g. because a user picked
	 * it: loads its trips from the database and reads their updates from the
	 * last download.
	 */
	public void watch(FeedState.Active timetable, String stationId) {
		if (!settings.enabled() || watched.shown(timetable, stationId)) return;
		synchronized (swap) {
			Set<String> added = watched.add(timetable, stationId);
			if (fileFetchedAt == null || added.isEmpty()) return;
			try {
				TripUpdates updates = current;
				current = updates != null && updates.fetchedAt().equals(fileFetchedAt)
						? updates.plus(readFile(added))
						: readFile(watched.tripIds());
			} catch (IOException e) {
				log.warn("Reading the GTFS-Realtime download failed: {}", e.toString());
			}
		}
	}

	/** When the data in use was fetched, null if none (for /api/feed; no demand). */
	public Instant fetchedAt() {
		return fileFetchedAt;
	}

	@Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
	void poll() {
		if (!settings.enabled()) return;
		if (!isOlderThan(lastDemand, settings.idleAfter())) {
			if (stale(settings.pollInterval())) fetchInBackground();
			return;
		}
		// Nobody looks anymore: once the data is too old to show, let it go.
		TripUpdates updates = current;
		if (updates != null && isOlderThan(updates.fetchedAt(), settings.maxAge())) {
			synchronized (swap) {
				current = null;
				watched.dropUnpicked();
			}
		}
	}

	private boolean stale(Duration interval) {
		// Also wait after failed attempts, so an outage isn't hammered.
		return isOlderThan(lastAttempt, interval);
	}

	private static boolean isOlderThan(Instant time, Duration age) {
		return time.plus(age).isBefore(Instant.now());
	}

	private void fetchInBackground() {
		if (!fetching.compareAndSet(false, true)) return;
		lastAttempt = Instant.now();
		Thread.ofVirtual().name("gtfs-rt-fetch").start(() -> {
			try {
				fetch();
			} catch (Exception e) {
				log.warn("GTFS-Realtime fetch failed: {}", e.toString());
			} finally {
				fetching.set(false);
			}
		});
	}

	private void fetch() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
				.header("Accept-Encoding", "gzip")
				.timeout(Duration.ofSeconds(60))
				.build();
		long started = System.nanoTime();
		Files.createDirectories(file.getParent());
		Path download = Files.createTempFile(file.getParent(), "transit-realtime-", ".part");
		TripUpdates kept;
		try {
			HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(download));
			if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
			boolean compressed = response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent();

			FeedState.Active timetable = feed.active();
			synchronized (swap) {
				Files.move(download, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				gzip = compressed;
				fileFetchedAt = Instant.now();
				if (timetable != null) watched.refresh(timetable);
				kept = readFile(watched.tripIds());
				current = kept;
			}
		} finally {
			Files.deleteIfExists(download);
		}
		log.debug("GTFS-Realtime: kept {} trips in {} ms", kept.tripCount(),
				Duration.ofNanos(System.nanoTime() - started).toMillis());
	}

	/** The given trips' updates from the last download. Only while holding swap. */
	private TripUpdates readFile(Set<String> tripIds) throws IOException {
		try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
				InputStream in = gzip ? new GZIPInputStream(raw, 1 << 16) : raw) {
			return TripUpdates.read(in, tripIds, fileFetchedAt);
		}
	}
}

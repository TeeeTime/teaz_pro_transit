package pro.teaz.transit.board;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import pro.teaz.transit.TransitProperties;
import pro.teaz.transit.gtfs.FeedImporter;
import pro.teaz.transit.gtfs.FeedState;
import pro.teaz.transit.gtfs.ServiceCalendar;
import pro.teaz.transit.gtfs.Slot;
import pro.teaz.transit.realtime.RealtimeFeed;
import pro.teaz.transit.realtime.TripUpdates;
import pro.teaz.transit.station.Station;
import pro.teaz.transit.station.StationRepository;

/**
 * Computes a station's departure board from the timetable and, if available,
 * realtime updates.
 *
 * GTFS stores times as seconds after the start of a service day, which can
 * run past midnight (25:10:00), so departures in a time window can come from
 * yesterday's, today's or tomorrow's service day: each is queried for its part
 * of the window and kept only if its service runs that day.
 */
@Service
public class DepartureService {

	public static final int MAX_LIMIT = 50;
	public static final int MAX_MINUTES = 360;

	// With realtime, also look at departures planned a while ago: they may be late.
	private static final Duration LOOKBACK_REALTIME = Duration.ofMinutes(30);
	// Without, keep what's leaving right now.
	private static final Duration LOOKBACK_PLANNED = Duration.ofMinutes(1);

	private final JdbcClient jdbc;
	private final FeedState feed;
	private final ServiceCalendar calendar;
	private final StationRepository stations;
	private final RealtimeFeed realtime;
	private final ZoneId zone;
	private final TransitProperties properties;
	private final Clock clock;

	DepartureService(JdbcClient jdbc, FeedState feed, ServiceCalendar calendar, StationRepository stations,
			RealtimeFeed realtime, TransitProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.feed = feed;
		this.calendar = calendar;
		this.stations = stations;
		this.realtime = realtime;
		this.zone = properties.timezone();
		this.properties = properties;
		this.clock = clock;
	}

	/** Departures within the next minutes (at most limit), soonest first. */
	public Board board(Station station, int limit, int minutes) {
		FeedState.Active active = feed.require();
		Instant now = clock.instant();
		TripUpdates updates = realtime.current();
		List<String> stopIds = stations.stopIds(active, station.id());

		Instant from = now.minus(updates != null ? LOOKBACK_REALTIME : LOOKBACK_PLANNED);
		Instant to = now.plus(Duration.ofMinutes(minutes));
		LocalDate today = LocalDate.ofInstant(now, zone);

		List<Departure> departures = new ArrayList<>();
		for (LocalDate day : List.of(today.minusDays(1), today, today.plusDays(1))) {
			ZonedDateTime dayStart = serviceDayStart(day);
			long fromSecs = Math.max(0, Duration.between(dayStart.toInstant(), from).toSeconds());
			long toSecs = Duration.between(dayStart.toInstant(), to).toSeconds();
			if (toSecs < 0) continue;
			Set<String> services = calendar.activeServices(active, day);
			if (services.isEmpty()) continue;
			for (Row row : query(active.slot(), stopIds, fromSecs, toSecs)) {
				if (!services.contains(row.serviceId())) continue;
				departures.add(departure(row, day, dayStart, updates));
			}
		}

		// Gone are the ones that already left; canceled ones stay until their planned time.
		Instant cutoff = now.minusSeconds(30);
		List<Departure> board = departures.stream()
				.filter(d -> !(d.canceled() ? d.plannedTime() : d.bestTime()).toInstant().isBefore(cutoff))
				.sorted(Comparator.comparing(Departure::bestTime).thenComparing(Departure::line))
				.limit(limit)
				.toList();
		return new Board(station, OffsetDateTime.ofInstant(now, zone), updates != null, board, attribution(active, updates));
	}

	/** Board for "no station picked yet": nothing to show, but the same shape. */
	public Board empty() {
		FeedState.Active active = feed.require();
		return new Board(null, OffsetDateTime.ofInstant(clock.instant(), zone), false, List.of(), attribution(active, null));
	}

	/**
	 * GTFS times count from "noon minus 12 hours", which is midnight except on
	 * days when the clocks change.
	 */
	private ZonedDateTime serviceDayStart(LocalDate day) {
		return day.atTime(12, 0).atZone(zone).minusHours(12);
	}

	private record Row(String tripId, String stopId, int stopSequence, int departureSecs, String serviceId,
			String stopHeadsign, String tripHeadsign, String tripShortName, String routeShortName, String routeLongName,
			int routeType, String agency, String platform, String lastStopName) {
	}

	private List<Row> query(Slot slot, List<String> stopIds, long fromSecs, long toSecs) {
		if (stopIds.isEmpty()) return List.of();
		// Departures only: not where the trip ends, not where it lets passengers off only.
		String sql = """
				SELECT st.trip_id, st.stop_id, st.stop_sequence, st.departure_secs, t.service_id,
				       h.text AS stop_headsign, t.headsign AS trip_headsign, t.short_name AS trip_short_name,
				       r.short_name AS route_short_name, r.long_name AS route_long_name, r.route_type,
				       a.name AS agency, s.platform, ls.name AS last_stop_name
				FROM %s st
				JOIN %s t ON t.trip_id = st.trip_id
				JOIN %s r ON r.route_id = t.route_id
				JOIN %s e ON e.trip_id = st.trip_id
				JOIN %s s ON s.stop_id = st.stop_id
				LEFT JOIN %s a ON a.agency_id = r.agency_id
				LEFT JOIN %s h ON h.headsign_id = st.headsign_id
				LEFT JOIN %s ls ON ls.stop_id = e.last_stop_id
				WHERE st.stop_id IN (:stops)
				  AND st.departure_secs BETWEEN :from AND :to
				  AND NOT st.no_pickup
				  AND st.stop_sequence < e.last_sequence
				""".formatted(slot.table("stop_times"), slot.table("trips"), slot.table("routes"), slot.table("trip_ends"),
				slot.table("stops"), slot.table("agencies"), slot.table("headsigns"), slot.table("stops"));
		return jdbc.sql(sql)
				.param("stops", stopIds)
				.param("from", fromSecs)
				.param("to", toSecs)
				.query((rs, i) -> new Row(rs.getString("trip_id"), rs.getString("stop_id"), rs.getInt("stop_sequence"),
						rs.getInt("departure_secs"), rs.getString("service_id"), rs.getString("stop_headsign"),
						rs.getString("trip_headsign"), rs.getString("trip_short_name"), rs.getString("route_short_name"),
						rs.getString("route_long_name"), rs.getInt("route_type"), rs.getString("agency"),
						rs.getString("platform"), rs.getString("last_stop_name")))
				.list();
	}

	private Departure departure(Row row, LocalDate day, ZonedDateTime dayStart, TripUpdates updates) {
		OffsetDateTime planned = dayStart.plusSeconds(row.departureSecs()).toOffsetDateTime();
		OffsetDateTime expected = null;
		Integer delayMinutes = null;
		boolean canceled = false;

		TripUpdates.StopRealtime live = updates == null ? null
				: updates.lookup(row.tripId(), day, row.stopSequence(), row.stopId());
		if (live != null) {
			canceled = live.canceled();
			if (live.time() != null) expected = OffsetDateTime.ofInstant(live.time(), zone);
			else if (live.delaySeconds() != null) expected = planned.plusSeconds(live.delaySeconds());
			if (expected != null) {
				delayMinutes = (int) Math.round(Duration.between(planned, expected).toSeconds() / 60.0);
			}
		}

		return new Departure(row.tripId(), line(row), Departure.mode(row.routeType()), destination(row),
				row.platform(), planned, expected, delayMinutes, canceled, row.agency());
	}

	private static String line(Row row) {
		if (notBlank(row.routeShortName())) return row.routeShortName();
		if (notBlank(row.tripShortName())) return row.tripShortName();
		if (notBlank(row.routeLongName())) return row.routeLongName();
		return "?";
	}

	private static String destination(Row row) {
		if (notBlank(row.stopHeadsign())) return row.stopHeadsign();
		if (notBlank(row.tripHeadsign())) return row.tripHeadsign();
		return row.lastStopName() != null ? row.lastStopName() : "";
	}

	private static boolean notBlank(String text) {
		return text != null && !text.isBlank();
	}

	private String attribution(FeedState.Active active, TripUpdates updates) {
		String timetable = FeedImporter.SAMPLE.equals(active.source())
				? "Sample timetable (made up)" : properties.gtfs().attribution();
		String live = properties.realtime().attribution();
		return updates != null && notBlank(live) ? timetable + ", " + live : timetable;
	}
}

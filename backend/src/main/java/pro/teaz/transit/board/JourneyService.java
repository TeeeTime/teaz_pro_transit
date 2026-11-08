package pro.teaz.transit.board;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import pro.teaz.transit.TransitProperties;
import pro.teaz.transit.gtfs.FeedState;
import pro.teaz.transit.gtfs.ServiceCalendar;
import pro.teaz.transit.gtfs.Slot;
import pro.teaz.transit.realtime.RealtimeFeed;
import pro.teaz.transit.realtime.TripUpdates;
import pro.teaz.transit.station.StationRepository;

/**
 * A trip's course on one service day ({@link Journey}): its stops from the
 * timetable, with realtime where the feed has it, and where the vehicle is.
 *
 * The position is judged by the times alone (the free feed has no vehicle
 * positions): at a stop from its arrival until shortly after its departure,
 * else between the stop it left and the next one.
 */
@Service
public class JourneyService {

	/**
	 * How long a vehicle still counts as at a stop after its departure time.
	 * Timetables give whole minutes and most stops have arrival = departure,
	 * so without it a vehicle would hardly ever be shown at a stop.
	 */
	static final Duration DWELL = Duration.ofSeconds(30);

	private final JdbcClient jdbc;
	private final FeedState feed;
	private final ServiceCalendar calendar;
	private final StationRepository stations;
	private final RealtimeFeed realtime;
	private final DepartureService departures;
	private final ZoneId zone;
	private final Clock clock;

	JourneyService(JdbcClient jdbc, FeedState feed, ServiceCalendar calendar, StationRepository stations,
			RealtimeFeed realtime, DepartureService departures, TransitProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.feed = feed;
		this.calendar = calendar;
		this.stations = stations;
		this.realtime = realtime;
		this.departures = departures;
		this.zone = properties.timezone();
		this.clock = clock;
	}

	/** The service day that started most recently (today's, also shortly after midnight). */
	public LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), zone);
	}

	/** Thrown for a trip that exists but doesn't run on the asked day. */
	public static class NotRunningException extends RuntimeException {
		NotRunningException() {
			super("This trip doesn't run on that day.");
		}
	}

	/**
	 * The trip's course on the service day; empty if the timetable has no such
	 * trip. stationId (optional) is the board it was opened from: its realtime
	 * is kept, so the trip's is too.
	 */
	public Optional<Journey> journey(String tripId, LocalDate serviceDay, String stationId) {
		FeedState.Active active = feed.require();
		Optional<TripRow> found = trip(active.slot(), tripId);
		if (found.isEmpty()) return Optional.empty();
		TripRow trip = found.get();
		if (!calendar.activeServices(active, serviceDay).contains(trip.serviceId())) throw new NotRunningException();

		boolean knownStation = stationId != null && stations.find(active, stationId).isPresent();
		TripUpdates updates = knownStation ? realtime.current(active, stationId) : realtime.latest();
		Instant now = clock.instant();

		List<StopRow> rows = stops(active.slot(), tripId);
		int[] sequences = rows.stream().mapToInt(StopRow::sequence).toArray();
		String[] stopIds = rows.stream().map(StopRow::stopId).toArray(String[]::new);
		TripUpdates.Course course = updates == null ? null : updates.course(tripId, serviceDay, sequences, stopIds);
		boolean canceled = course != null && course.canceled();

		ZonedDateTime dayStart = DepartureService.serviceDayStart(serviceDay, zone);
		List<JourneyStop> timed = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			TripUpdates.StopEvents live = course == null || canceled ? null : course.stops().get(i);
			timed.add(stop(rows.get(i), dayStart, live, canceled, i == rows.size() - 1));
		}
		Journey.Position position = position(timed, now);
		List<JourneyStop> stops = new ArrayList<>(timed.size());
		for (int i = 0; i < timed.size(); i++) stops.add(timed.get(i).withPassed(passed(position, i)));

		return Optional.of(new Journey(tripId, serviceDay,
				Departure.line(trip.routeShortName(), trip.tripShortName(), trip.routeLongName()),
				Departure.mode(trip.routeType()),
				Departure.notBlank(trip.headsign()) ? trip.headsign() : trip.lastStopName() != null ? trip.lastStopName() : "",
				trip.agency(), OffsetDateTime.ofInstant(now, zone), updates != null, course != null, canceled,
				List.copyOf(stops), position, departures.attribution(active, updates)));
	}

	private JourneyStop stop(StopRow row, ZonedDateTime dayStart, TripUpdates.StopEvents live, boolean tripCanceled,
			boolean last) {
		OffsetDateTime plannedDeparture = dayStart.plusSeconds(row.departureSecs()).toOffsetDateTime();
		OffsetDateTime plannedArrival = row.arrivalSecs() != null
				? dayStart.plusSeconds(row.arrivalSecs()).toOffsetDateTime() : plannedDeparture;
		boolean canceled = tripCanceled || (live != null && live.skipped());
		OffsetDateTime expectedArrival = null, expectedDeparture = null;
		if (live != null && !canceled) {
			expectedArrival = expected(plannedArrival, live.arrival());
			expectedDeparture = expected(plannedDeparture, live.departure());
			// Can't leave before it's there.
			if (expectedArrival != null && expectedDeparture != null && expectedDeparture.isBefore(expectedArrival)) {
				expectedDeparture = expectedArrival;
			}
		}
		OffsetDateTime planned = last ? plannedArrival : plannedDeparture;
		OffsetDateTime expected = last ? expectedArrival : expectedDeparture;
		Integer delayMinutes = expected == null ? null
				: (int) Math.round(Duration.between(planned, expected).toSeconds() / 60.0);
		return new JourneyStop(row.stopId(), row.stationId(), row.stationName(), row.platform(), plannedArrival,
				expectedArrival, plannedDeparture, expectedDeparture, delayMinutes, canceled, false);
	}

	private OffsetDateTime expected(OffsetDateTime planned, TripUpdates.StopRealtime live) {
		if (live == null) return null;
		if (live.time() != null) return OffsetDateTime.ofInstant(live.time(), zone);
		if (live.delaySeconds() != null) return planned.plusSeconds(live.delaySeconds());
		return null;
	}

	/** Where the vehicle is at now, from the stops' expected (else planned) times. Skipped stops don't count. */
	static Journey.Position position(List<JourneyStop> stops, Instant now) {
		int previous = -1;
		for (int i = 0; i < stops.size(); i++) {
			JourneyStop stop = stops.get(i);
			if (stop.canceled()) continue;
			if (now.isBefore(stop.arrival().toInstant())) {
				if (previous < 0) return new Journey.Position("NOT_STARTED", i, null, null);
				Instant left = stops.get(previous).departure().toInstant();
				long total = Duration.between(left, stop.arrival().toInstant()).toMillis();
				double progress = total <= 0 ? 0 : Duration.between(left, now).toMillis() / (double) total;
				return new Journey.Position("BETWEEN", previous, i, Math.clamp(progress, 0.0, 1.0));
			}
			if (now.isBefore(stop.departure().toInstant().plus(DWELL))) {
				return new Journey.Position("AT_STOP", i, null, null);
			}
			previous = i;
		}
		return previous < 0 ? new Journey.Position("CANCELED", null, null, null)
				: new Journey.Position("ARRIVED", previous, null, null);
	}

	private static boolean passed(Journey.Position position, int index) {
		return switch (position.status()) {
			case "AT_STOP" -> index < position.stopIndex();
			case "BETWEEN", "ARRIVED" -> index <= position.stopIndex();
			default -> false;
		};
	}

	private record TripRow(String serviceId, String headsign, String tripShortName, String routeShortName,
			String routeLongName, int routeType, String agency, String lastStopName) {
	}

	private Optional<TripRow> trip(Slot slot, String tripId) {
		String sql = """
				SELECT t.service_id, t.headsign, t.short_name AS trip_short_name, r.short_name AS route_short_name,
				       r.long_name AS route_long_name, r.route_type, a.name AS agency, ls.name AS last_stop_name
				FROM %s t
				JOIN %s r ON r.route_id = t.route_id
				LEFT JOIN %s a ON a.agency_id = r.agency_id
				LEFT JOIN %s e ON e.trip_id = t.trip_id
				LEFT JOIN %s ls ON ls.stop_id = e.last_stop_id
				WHERE t.trip_id = :trip
				""".formatted(slot.table("trips"), slot.table("routes"), slot.table("agencies"), slot.table("trip_ends"),
				slot.table("stops"));
		return jdbc.sql(sql)
				.param("trip", tripId)
				.query((rs, i) -> new TripRow(rs.getString("service_id"), rs.getString("headsign"),
						rs.getString("trip_short_name"), rs.getString("route_short_name"), rs.getString("route_long_name"),
						rs.getInt("route_type"), rs.getString("agency"), rs.getString("last_stop_name")))
				.optional();
	}

	private record StopRow(String stopId, int sequence, Integer arrivalSecs, int departureSecs, String stationId,
			String stationName, String platform) {
	}

	/** The trip's stops with a time, in order (stops between timepoints without a time aren't in the database). */
	private List<StopRow> stops(Slot slot, String tripId) {
		String sql = """
				SELECT st.stop_id, st.stop_sequence, st.arrival_secs, st.departure_secs, s.station_id,
				       station.name AS station_name, s.platform
				FROM %s st
				JOIN %s s ON s.stop_id = st.stop_id
				JOIN %s station ON station.stop_id = s.station_id
				WHERE st.trip_id = :trip
				ORDER BY st.stop_sequence
				""".formatted(slot.table("stop_times"), slot.table("stops"), slot.table("stops"));
		return jdbc.sql(sql)
				.param("trip", tripId)
				.query((rs, i) -> new StopRow(rs.getString("stop_id"), rs.getInt("stop_sequence"),
						rs.getObject("arrival_secs", Integer.class), rs.getInt("departure_secs"),
						rs.getString("station_id"), rs.getString("station_name"), rs.getString("platform")))
				.list();
	}
}

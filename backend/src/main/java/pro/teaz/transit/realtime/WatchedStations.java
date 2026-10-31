package pro.teaz.transit.realtime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import pro.teaz.transit.TransitProperties;
import pro.teaz.transit.gtfs.FeedState;
import pro.teaz.transit.gtfs.ServiceCalendar;
import pro.teaz.transit.station.StationRepository;

/**
 * The stations whose realtime is kept, each with the trips leaving from it on
 * yesterday's, today's and tomorrow's service day (all a board looks at).
 * Realtime for every other trip is dropped while the feed is read.
 *
 * A station is watched while a user has picked it, and for idle-after after
 * its board was last shown (browsing other stations). Its trips are loaded
 * from the database when it's first watched and again for a new timetable or
 * day.
 */
@Component
class WatchedStations {

	private record Watch(FeedState.Active feed, LocalDate day, Set<String> tripIds, Instant lastShown) {
	}

	private final JdbcClient jdbc;
	private final StationRepository stations;
	private final ServiceCalendar calendar;
	private final ZoneId zone;
	private final Duration keepShown;
	private final Clock clock;
	private final Map<String, Watch> watches = new ConcurrentHashMap<>();
	private volatile Set<String> tripIds = Set.of();

	WatchedStations(JdbcClient jdbc, StationRepository stations, ServiceCalendar calendar, TransitProperties properties,
			Clock clock) {
		this.jdbc = jdbc;
		this.stations = stations;
		this.calendar = calendar;
		this.zone = properties.timezone();
		this.keepShown = properties.realtime().idleAfter();
		this.clock = clock;
	}

	/** The trips of all watched stations. */
	Set<String> tripIds() {
		return tripIds;
	}

	/** Notes that the station's board is shown; true if its trips are loaded and current. */
	boolean shown(FeedState.Active feed, String stationId) {
		Watch watch = watches.computeIfPresent(stationId,
				(id, w) -> new Watch(w.feed(), w.day(), w.tripIds(), clock.instant()));
		return watch != null && watch.feed().equals(feed) && watch.day().equals(today());
	}

	/** Watches the station, (re)loading its trips; returns them. */
	Set<String> add(FeedState.Active feed, String stationId) {
		Watch watch = load(feed, stationId, clock.instant());
		watches.put(stationId, watch);
		updateTripIds();
		return watch.tripIds();
	}

	/**
	 * Watches every station a user picked, drops the others once their board
	 * wasn't shown for idle-after, and reloads trips that are outdated.
	 */
	void refresh(FeedState.Active feed) {
		Set<String> picked = picked();
		Instant shownAfter = clock.instant().minus(keepShown);
		LocalDate today = today();
		for (Map.Entry<String, Watch> entry : watches.entrySet()) {
			Watch watch = entry.getValue();
			if (!picked.contains(entry.getKey()) && watch.lastShown().isBefore(shownAfter)) {
				watches.remove(entry.getKey());
			} else if (!watch.feed().equals(feed) || !watch.day().equals(today)) {
				watches.put(entry.getKey(), load(feed, entry.getKey(), watch.lastShown()));
			}
		}
		for (String stationId : picked) {
			if (!watches.containsKey(stationId)) watches.put(stationId, load(feed, stationId, Instant.EPOCH));
		}
		updateTripIds();
	}

	/** Forgets every station nobody picked (nobody looks at boards anymore). */
	void dropUnpicked() {
		Set<String> picked = picked();
		watches.keySet().retainAll(picked);
		updateTripIds();
	}

	private Set<String> picked() {
		return Set.copyOf(jdbc.sql("SELECT DISTINCT station_id FROM user_station").query(String.class).list());
	}

	private Watch load(FeedState.Active feed, String stationId, Instant lastShown) {
		LocalDate today = today();
		Set<String> services = new HashSet<>();
		for (LocalDate day : List.of(today.minusDays(1), today, today.plusDays(1))) {
			services.addAll(calendar.activeServices(feed, day));
		}
		Set<String> trips = new HashSet<>();
		List<String> stopIds = stations.stopIds(feed, stationId);
		if (!stopIds.isEmpty()) {
			jdbc.sql("SELECT DISTINCT st.trip_id, t.service_id FROM " + feed.slot().table("stop_times") + " st JOIN "
					+ feed.slot().table("trips") + " t ON t.trip_id = st.trip_id WHERE st.stop_id IN (:stops)")
					.param("stops", stopIds)
					.query(rs -> {
						if (services.contains(rs.getString(2))) trips.add(rs.getString(1));
					});
		}
		return new Watch(feed, today, Set.copyOf(trips), lastShown);
	}

	private void updateTripIds() {
		Set<String> all = new HashSet<>();
		for (Watch watch : watches.values()) all.addAll(watch.tripIds());
		tripIds = Set.copyOf(all);
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), zone);
	}
}

package pro.teaz.transit.station;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import pro.teaz.transit.gtfs.FeedState;

/**
 * The one station each user picked (table user_station).
 *
 * Stop ids aren't guaranteed to stay the same from one feed version to the
 * next, so name and position are stored too: if the saved id is gone (or now
 * names a different place), the station with the same name nearest to the
 * saved position takes its place, and the stored id is updated.
 */
@Service
public class UserStations {

	// How far a station may have "moved" between feed versions and still count as the same.
	private static final int SAME_STATION_RADIUS_METERS = 500;

	private record Saved(String stationId, String name, double lat, double lon) {
	}

	private final JdbcClient jdbc;
	private final FeedState feed;
	private final StationRepository stations;

	UserStations(JdbcClient jdbc, FeedState feed, StationRepository stations) {
		this.jdbc = jdbc;
		this.feed = feed;
		this.stations = stations;
	}

	/** The user's station in the served timetable; empty if none picked or it no longer exists. */
	public Optional<Station> find(String userId) {
		Optional<Saved> saved = jdbc.sql("SELECT station_id, station_name, lat, lon FROM user_station WHERE user_id = ?")
				.param(userId)
				.query((rs, i) -> new Saved(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4)))
				.optional();
		if (saved.isEmpty()) return Optional.empty();
		Saved s = saved.get();
		FeedState.Active active = feed.require();

		Optional<Station> byId = stations.find(active, s.stationId())
				.filter(st -> st.name().equals(s.name())
						&& Station.distance(st.lat(), st.lon(), s.lat(), s.lon()) <= SAME_STATION_RADIUS_METERS);
		if (byId.isPresent()) return byId;

		Optional<Station> byName = stations.findByName(active, s.name(), s.lat(), s.lon(), SAME_STATION_RADIUS_METERS);
		byName.ifPresent(station -> save(userId, station));
		return byName;
	}

	/** Picks a station; empty if there's no station with that id. */
	public Optional<Station> choose(String userId, String stationId) {
		Optional<Station> station = stations.find(feed.require(), stationId);
		station.ifPresent(s -> save(userId, s));
		return station;
	}

	public void clear(String userId) {
		jdbc.sql("DELETE FROM user_station WHERE user_id = ?").param(userId).update();
	}

	private void save(String userId, Station station) {
		var now = Instant.now().atOffset(ZoneOffset.UTC);
		int updated = jdbc.sql("""
				UPDATE user_station SET station_id = ?, station_name = ?, lat = ?, lon = ?, updated_at = ?
				WHERE user_id = ?""")
				.params(station.id(), station.name(), station.lat(), station.lon(), now, userId)
				.update();
		if (updated == 0) {
			jdbc.sql("""
					INSERT INTO user_station (user_id, station_id, station_name, lat, lon, updated_at)
					VALUES (?, ?, ?, ?, ?, ?)""")
					.params(userId, station.id(), station.name(), station.lat(), station.lon(), now)
					.update();
		}
	}
}

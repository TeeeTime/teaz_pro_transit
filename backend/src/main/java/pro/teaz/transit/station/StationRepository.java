package pro.teaz.transit.station;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import pro.teaz.transit.gtfs.FeedImporter;
import pro.teaz.transit.gtfs.FeedState;

/** Stations of the served timetable. */
@Repository
public class StationRepository {

	// Candidates fetched from the database before ranking; enough for any useful query.
	private static final int SEARCH_CANDIDATES = 500;

	private static final RowMapper<Station> STATION = (rs, i) ->
			new Station(rs.getString("stop_id"), rs.getString("name"), rs.getDouble("lat"), rs.getDouble("lon"));

	private final JdbcClient jdbc;

	StationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Station> find(FeedState.Active feed, String id) {
		return jdbc.sql("SELECT stop_id, name, lat, lon FROM " + feed.slot().table("stops")
				+ " WHERE stop_id = ? AND is_station")
				.param(id)
				.query(STATION)
				.optional();
	}

	/** The station's own stop and all its platforms: where its departures leave from. */
	public List<String> stopIds(FeedState.Active feed, String stationId) {
		return jdbc.sql("SELECT stop_id FROM " + feed.slot().table("stops") + " WHERE station_id = ?")
				.param(stationId)
				.query(String.class)
				.list();
	}

	/**
	 * Stations with a name containing every word of the query (ignoring case and
	 * accents), best matches first: exact name, then names starting with the
	 * query, then shorter names.
	 *
	 * Platform names count too: feeds often name a station differently from its
	 * platforms (gtfs.de: station "Münster Hauptbahnhof", platform
	 * "Münster(Westf)Hbf"), and people search for either.
	 */
	public List<Station> search(FeedState.Active feed, String query, int limit) {
		String normalized = FeedImporter.searchName(query.trim());
		List<String> words = Arrays.stream(normalized.split("\\s+")).filter(w -> !w.isEmpty()).toList();
		if (words.isEmpty()) return List.of();

		String stops = feed.slot().table("stops");
		StringBuilder sql = new StringBuilder("SELECT st.stop_id, st.name, st.lat, st.lon, s.search_name FROM ")
				.append(stops).append(" s JOIN ").append(stops).append(" st ON st.stop_id = s.station_id AND st.is_station")
				.append(" WHERE TRUE");
		for (int i = 0; i < words.size(); i++) sql.append(" AND s.search_name LIKE ? ESCAPE '\\'");
		sql.append(" LIMIT ").append(SEARCH_CANDIDATES);

		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
		for (String word : words) statement = statement.param("%" + escapeLike(word) + "%");
		record Candidate(Station station, String searchName) {
		}
		Comparator<Candidate> best = Comparator
				.comparingInt((Candidate c) -> rank(c.searchName(), normalized))
				.thenComparingInt(c -> c.searchName().length())
				.thenComparing(c -> c.station().name());
		// One result per station, ranked by its best-matching name.
		Map<String, Candidate> byStation = new HashMap<>();
		for (Candidate candidate : statement
				.query((rs, i) -> new Candidate(STATION.mapRow(rs, i), rs.getString("search_name")))
				.list()) {
			byStation.merge(candidate.station().id(), candidate, (a, b) -> best.compare(a, b) <= 0 ? a : b);
		}
		return byStation.values().stream()
				.sorted(best)
				.limit(limit)
				.map(Candidate::station)
				.toList();
	}

	private static int rank(String name, String query) {
		if (name.equals(query)) return 0;
		if (name.startsWith(query)) return 1;
		if (name.contains(" " + query) || name.contains("(" + query)) return 2;
		return 3;
	}

	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/** Closest stations within radiusMeters of the position, nearest first. */
	public List<Station.Nearby> nearby(FeedState.Active feed, double lat, double lon, int radiusMeters, int limit) {
		double dLat = radiusMeters / 111_320.0;
		double dLon = radiusMeters / (111_320.0 * Math.max(Math.cos(Math.toRadians(lat)), 0.01));
		return jdbc.sql("SELECT stop_id, name, lat, lon FROM " + feed.slot().table("stops")
				+ " WHERE is_station AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?")
				.params(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
				.query(STATION)
				.list().stream()
				.map(s -> new Station.Nearby(s.id(), s.name(), s.lat(), s.lon(),
						(int) Math.round(Station.distance(lat, lon, s.lat(), s.lon()))))
				.filter(s -> s.distanceMeters() <= radiusMeters)
				.sorted(Comparator.comparingInt(Station.Nearby::distanceMeters))
				.limit(limit)
				.toList();
	}

	/** The station with exactly this name closest to the position, if one is within radiusMeters. */
	public Optional<Station> findByName(FeedState.Active feed, String name, double lat, double lon, int radiusMeters) {
		return jdbc.sql("SELECT stop_id, name, lat, lon FROM " + feed.slot().table("stops")
				+ " WHERE is_station AND search_name = ?")
				.param(FeedImporter.searchName(name))
				.query(STATION)
				.list().stream()
				.filter(s -> Station.distance(lat, lon, s.lat(), s.lon()) <= radiusMeters)
				.min(Comparator.comparingDouble(s -> Station.distance(lat, lon, s.lat(), s.lon())));
	}
}

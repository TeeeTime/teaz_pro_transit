package pro.teaz.transit.board;

import java.time.OffsetDateTime;
import java.util.List;

import pro.teaz.transit.station.Station;

/**
 * A station's next departures. Part of the API for other apps (see README).
 *
 * @param station     the station, null if the user hasn't picked one (only /api/board)
 * @param generatedAt when this was computed
 * @param realtime    whether realtime data was available; if false, all times are planned times
 * @param departures  soonest first (by expected time, planned time without realtime)
 * @param attribution data sources, to be shown wherever departures are shown (license terms)
 */
public record Board(
		Station station,
		OffsetDateTime generatedAt,
		boolean realtime,
		List<Departure> departures,
		String attribution) {
}

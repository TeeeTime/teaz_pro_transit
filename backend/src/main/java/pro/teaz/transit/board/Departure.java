package pro.teaz.transit.board;

import java.time.OffsetDateTime;

/**
 * One departure on a board. Part of the API for other apps (see README), so
 * fields are only ever added, not renamed or removed.
 *
 * @param tripId        GTFS trip_id
 * @param line          what the vehicle shows, e.g. "S1", "RE 7", "11"
 * @param mode          TRAIN, SUBURBAN, SUBWAY, TRAM, BUS, FERRY or OTHER
 * @param destination   where it's going (headsign)
 * @param platform      platform or bay, null if the timetable has none
 * @param plannedTime   timetable departure
 * @param expectedTime  realtime departure; null without realtime data for this trip
 * @param delayMinutes  expected minus planned, rounded; null without realtime data
 * @param canceled      canceled, or doesn't stop here today
 * @param agency        operator, e.g. "Stadtwerke Münster"
 */
public record Departure(
		String tripId,
		String line,
		String mode,
		String destination,
		String platform,
		OffsetDateTime plannedTime,
		OffsetDateTime expectedTime,
		Integer delayMinutes,
		boolean canceled,
		String agency) {

	/** When it actually leaves, as far as known: what the board is sorted by. */
	OffsetDateTime bestTime() {
		return expectedTime != null ? expectedTime : plannedTime;
	}

	/** GTFS route_type (basic or extended) as one of a few modes a board distinguishes. */
	static String mode(int routeType) {
		return switch (routeType) {
			case 0, 5 -> "TRAM";
			case 1 -> "SUBWAY";
			case 2 -> "TRAIN";
			case 3, 11 -> "BUS";
			case 4 -> "FERRY";
			case 109 -> "SUBURBAN";
			default -> {
				if (routeType >= 100 && routeType < 200) yield "TRAIN";
				if (routeType >= 400 && routeType < 500) yield "SUBWAY";
				if (routeType >= 700 && routeType < 900) yield "BUS";
				if (routeType >= 900 && routeType < 1000) yield "TRAM";
				if (routeType == 1000 || routeType == 1200) yield "FERRY";
				yield "OTHER";
			}
		};
	}
}

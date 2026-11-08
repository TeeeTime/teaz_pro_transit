package pro.teaz.transit.board;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One trip's course: all its stops with planned and expected times, and
 * where the vehicle is right now. Opened from a departure on a board.
 *
 * @param tripId      GTFS trip_id
 * @param serviceDay  the service day the times belong to
 * @param line        as on the board, e.g. "RE 7"
 * @param mode        as on the board
 * @param destination the trip's headsign, else its last stop
 * @param agency      operator, null if the timetable has none
 * @param generatedAt when this was computed (the moment {@code position} is for)
 * @param realtime    whether realtime data was available at all
 * @param tracked     whether realtime knows this trip; false: every time is a planned time
 * @param canceled    the whole trip is canceled
 * @param stops       in the order the vehicle serves them
 * @param position    where the vehicle is at generatedAt
 * @param attribution data sources, to be shown with the times (license terms)
 */
public record Journey(
		String tripId,
		LocalDate serviceDay,
		String line,
		String mode,
		String destination,
		String agency,
		OffsetDateTime generatedAt,
		boolean realtime,
		boolean tracked,
		boolean canceled,
		List<JourneyStop> stops,
		Position position,
		String attribution) {

	/**
	 * Where the vehicle is, judged by the (expected, else planned) times.
	 *
	 * @param status        NOT_STARTED (before its first stop), AT_STOP, BETWEEN,
	 *                      ARRIVED (at its last stop, done) or CANCELED
	 * @param stopIndex     NOT_STARTED: the first stop; AT_STOP: that stop;
	 *                      BETWEEN: the stop it left last; ARRIVED: the last stop; CANCELED: null
	 * @param nextStopIndex BETWEEN: the stop it heads for, else null
	 * @param progress      BETWEEN: 0 to 1, the share of the time between the two stops that has passed, else null
	 */
	public record Position(String status, Integer stopIndex, Integer nextStopIndex, Double progress) {
	}
}

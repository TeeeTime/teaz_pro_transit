package pro.teaz.transit.board;

import java.time.OffsetDateTime;

/**
 * One stop of a {@link Journey}. Expected times are null where realtime
 * knows nothing (not "on time").
 *
 * @param stopId            GTFS stop_id (the platform or bay)
 * @param stationId         the station it belongs to, as on boards and in station search
 * @param name              the station's name
 * @param platform          platform or bay, null if the timetable has none
 * @param plannedArrival    timetable arrival (the departure where the timetable has none)
 * @param expectedArrival   realtime arrival, null without realtime
 * @param plannedDeparture  timetable departure
 * @param expectedDeparture realtime departure, null without realtime
 * @param delayMinutes      at the departure (at the last stop: the arrival), null without realtime
 * @param canceled          the vehicle doesn't stop here (trip canceled, or stop skipped)
 * @param passed            the vehicle has already left this stop
 */
public record JourneyStop(
		String stopId,
		String stationId,
		String name,
		String platform,
		OffsetDateTime plannedArrival,
		OffsetDateTime expectedArrival,
		OffsetDateTime plannedDeparture,
		OffsetDateTime expectedDeparture,
		Integer delayMinutes,
		boolean canceled,
		boolean passed) {

	OffsetDateTime arrival() {
		return expectedArrival != null ? expectedArrival : plannedArrival;
	}

	OffsetDateTime departure() {
		return expectedDeparture != null ? expectedDeparture : plannedDeparture;
	}

	JourneyStop withPassed(boolean passed) {
		return new JourneyStop(stopId, stationId, name, platform, plannedArrival, expectedArrival, plannedDeparture,
				expectedDeparture, delayMinutes, canceled, passed);
	}
}

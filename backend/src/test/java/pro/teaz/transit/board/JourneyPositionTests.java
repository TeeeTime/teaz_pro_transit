package pro.teaz.transit.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Where a vehicle is, judged from its stops' times. */
class JourneyPositionTests {

	private static final OffsetDateTime START = OffsetDateTime.of(2026, 10, 19, 8, 0, 0, 0, ZoneOffset.ofHours(2));

	/** A stop planned at the minute, optionally expected later or skipped. */
	private static JourneyStop stop(int minute, Integer delayMinutes, boolean canceled) {
		OffsetDateTime planned = START.plusMinutes(minute);
		OffsetDateTime expected = delayMinutes == null ? null : planned.plusMinutes(delayMinutes);
		return new JourneyStop("s" + minute, "s" + minute, "Stop " + minute, null, planned, expected, planned, expected,
				delayMinutes, canceled, false);
	}

	private static Instant at(int minute) {
		return START.plusMinutes(minute).toInstant();
	}

	@Test
	void expectedTimesWinOverPlannedOnes() {
		// Planned at the second stop at 08:10, but 5 minutes late.
		List<JourneyStop> stops = List.of(stop(0, null, false), stop(10, 5, false), stop(20, 5, false));
		Journey.Position position = JourneyService.position(stops, at(12));
		assertThat(position.status()).isEqualTo("BETWEEN");
		assertThat(position.stopIndex()).isZero();
		assertThat(position.nextStopIndex()).isEqualTo(1);
		assertThat(position.progress()).isEqualTo(0.8);
		assertThat(JourneyService.position(stops, at(15)).status()).isEqualTo("AT_STOP");
	}

	@Test
	void skippedStopsAreDrivenPast() {
		List<JourneyStop> stops = List.of(stop(0, null, false), stop(10, null, true), stop(20, null, false));
		Journey.Position position = JourneyService.position(stops, at(10));
		assertThat(position.status()).isEqualTo("BETWEEN");
		assertThat(position.stopIndex()).isZero();
		assertThat(position.nextStopIndex()).isEqualTo(2);
		assertThat(position.progress()).isEqualTo(0.5);
	}

	@Test
	void allStopsCanceled() {
		List<JourneyStop> stops = List.of(stop(0, null, true), stop(10, null, true));
		assertThat(JourneyService.position(stops, at(5)).status()).isEqualTo("CANCELED");
	}
}

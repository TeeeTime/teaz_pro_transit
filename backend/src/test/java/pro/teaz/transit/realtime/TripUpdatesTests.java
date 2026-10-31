package pro.teaz.transit.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;

class TripUpdatesTests {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

	private static StopTimeUpdate stop(int sequence, Integer delay) {
		StopTimeUpdate.Builder stop = StopTimeUpdate.newBuilder().setStopSequence(sequence);
		if (delay != null) stop.setDeparture(StopTimeEvent.newBuilder().setDelay(delay));
		return stop.build();
	}

	private static FeedEntity trip(String id, String startDate, TripDescriptor.ScheduleRelationship relationship,
			StopTimeUpdate... stops) {
		TripDescriptor.Builder trip = TripDescriptor.newBuilder().setTripId(id).setScheduleRelationship(relationship);
		if (startDate != null) trip.setStartDate(startDate);
		return FeedEntity.newBuilder().setId(id)
				.setTripUpdate(TripUpdate.newBuilder().setTrip(trip).addAllStopTimeUpdate(java.util.List.of(stops)))
				.build();
	}

	private static FeedMessage message(FeedEntity... entities) {
		return FeedMessage.newBuilder()
				.setHeader(FeedHeader.newBuilder().setGtfsRealtimeVersion("2.0"))
				.addAllEntity(java.util.List.of(entities))
				.build();
	}

	private static TripUpdates updates(FeedEntity... entities) {
		return TripUpdates.from(message(entities), Instant.now());
	}

	@Test
	void delaysCarryOverToLaterStopsWithoutTheirOwnUpdate() {
		TripUpdates updates = updates(trip("t1", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED,
				stop(2, 120), stop(5, 300)));

		assertThat(updates.lookup("t1", DAY, 1, "a")).isNull();
		assertThat(updates.lookup("t1", DAY, 2, "b").delaySeconds()).isEqualTo(120);
		assertThat(updates.lookup("t1", DAY, 3, "c").delaySeconds()).isEqualTo(120);
		assertThat(updates.lookup("t1", DAY, 5, "e").delaySeconds()).isEqualTo(300);
		assertThat(updates.lookup("t1", DAY, 9, "z").delaySeconds()).isEqualTo(300);
	}

	@Test
	void matchesTheServiceDay() {
		TripUpdates updates = updates(trip("t1", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 60)));
		assertThat(updates.lookup("t1", DAY, 1, "a")).isNotNull();
		assertThat(updates.lookup("t1", DAY.plusDays(1), 1, "a")).isNull();
		assertThat(updates.lookup("other", DAY, 1, "a")).isNull();
	}

	@Test
	void withoutStartDateAppliesToAnyDay() {
		TripUpdates updates = updates(trip("t1", null, TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 60)));
		assertThat(updates.lookup("t1", DAY.plusDays(3), 1, "a").delaySeconds()).isEqualTo(60);
	}

	@Test
	void canceledTripsAndSkippedStops() {
		StopTimeUpdate skipped = StopTimeUpdate.newBuilder().setStopSequence(2)
				.setScheduleRelationship(StopTimeUpdate.ScheduleRelationship.SKIPPED).build();
		TripUpdates updates = updates(
				trip("gone", "20261019", TripDescriptor.ScheduleRelationship.CANCELED),
				trip("t2", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 0), skipped));

		assertThat(updates.lookup("gone", DAY, 4, "x").canceled()).isTrue();
		assertThat(updates.lookup("t2", DAY, 1, "a").canceled()).isFalse();
		assertThat(updates.lookup("t2", DAY, 2, "b").canceled()).isTrue();
	}

	@Test
	void absoluteTimesAreKept() {
		StopTimeUpdate timed = StopTimeUpdate.newBuilder().setStopSequence(1)
				.setDeparture(StopTimeEvent.newBuilder().setTime(1_800_000_000L)).build();
		TripUpdates updates = updates(trip("t1", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, timed));
		TripUpdates.StopRealtime live = updates.lookup("t1", DAY, 1, "a");
		assertThat(live.time()).isEqualTo(Instant.ofEpochSecond(1_800_000_000L));
		assertThat(live.delaySeconds()).isNull();
	}

	@Test
	void readingKeepsOnlyTheGivenTrips() throws IOException {
		FeedMessage message = message(
				trip("keep", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 60)),
				trip("drop", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 120)),
				trip("gone", "20261019", TripDescriptor.ScheduleRelationship.CANCELED));
		TripUpdates updates = TripUpdates.read(new ByteArrayInputStream(message.toByteArray()), Set.of("keep", "gone"),
				Instant.now());

		assertThat(updates.tripCount()).isEqualTo(2);
		assertThat(updates.lookup("keep", DAY, 1, "a").delaySeconds()).isEqualTo(60);
		assertThat(updates.lookup("gone", DAY, 1, "a").canceled()).isTrue();
		assertThat(updates.lookup("drop", DAY, 1, "a")).isNull();
	}

	@Test
	void plusAddsTheOtherTrips() {
		TripUpdates first = updates(trip("t1", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 60)));
		TripUpdates second = updates(trip("t2", "20261019", TripDescriptor.ScheduleRelationship.SCHEDULED, stop(1, 120)));
		TripUpdates both = first.plus(second);

		assertThat(both.tripCount()).isEqualTo(2);
		assertThat(both.fetchedAt()).isEqualTo(first.fetchedAt());
		assertThat(both.lookup("t2", DAY, 1, "a").delaySeconds()).isEqualTo(120);
	}
}

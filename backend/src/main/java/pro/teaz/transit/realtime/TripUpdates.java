package pro.teaz.transit.realtime;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;

/**
 * One fetched GTFS-Realtime feed, reduced to what departure boards need:
 * per trip and service day, its delays and cancellations, for the trips of
 * the watched stations only (see {@link WatchedStations}). Immutable, so it
 * can be swapped in as a whole while requests read the previous one.
 */
public final class TripUpdates {

	/** What realtime says about one departure. delaySeconds or time may be null (no data). */
	public record StopRealtime(Integer delaySeconds, Instant time, boolean canceled) {
	}

	private static final int NO_DELAY = Integer.MIN_VALUE;
	private static final long NO_TIME = Long.MIN_VALUE;
	// How FeedMessage.entity starts in the wire format: its field number, length-delimited.
	private static final int ENTITY_TAG = (FeedMessage.ENTITY_FIELD_NUMBER << 3) | WireFormat.WIRETYPE_LENGTH_DELIMITED;

	/** Stop updates of one trip, in feed order (by stop_sequence, as the spec requires). */
	private record Trip(boolean canceled, int[] sequences, String[] stopIds, int[] delays, long[] times, boolean[] skipped) {
	}

	private final Instant fetchedAt;
	private final Map<String, Trip> trips;

	private TripUpdates(Instant fetchedAt, Map<String, Trip> trips) {
		this.fetchedAt = fetchedAt;
		this.trips = trips;
	}

	public Instant fetchedAt() {
		return fetchedAt;
	}

	public int tripCount() {
		return trips.size();
	}

	static TripUpdates from(FeedMessage message, Instant fetchedAt) {
		Map<String, Trip> trips = new HashMap<>();
		for (FeedEntity entity : message.getEntityList()) add(trips, entity);
		return new TripUpdates(fetchedAt, Map.copyOf(trips));
	}

	/**
	 * Reads a serialized FeedMessage one entity at a time and keeps only the
	 * given trips. The feed for all of Germany is never in memory as a whole:
	 * each entity is parsed, checked and dropped before the next is read.
	 */
	static TripUpdates read(InputStream in, Set<String> tripIds, Instant fetchedAt) throws IOException {
		Map<String, Trip> trips = new HashMap<>();
		CodedInputStream input = CodedInputStream.newInstance(in, 1 << 16);
		for (int tag = input.readTag(); tag != 0; tag = input.readTag()) {
			if (tag != ENTITY_TAG) {
				input.skipField(tag);
				continue;
			}
			int limit = input.pushLimit(input.readRawVarint32());
			FeedEntity entity = FeedEntity.parseFrom(input);
			input.popLimit(limit);
			if (tripIds.contains(entity.getTripUpdate().getTrip().getTripId())) add(trips, entity);
		}
		return new TripUpdates(fetchedAt, Map.copyOf(trips));
	}

	/** These updates plus the other's (same fetch, other trips). */
	TripUpdates plus(TripUpdates other) {
		Map<String, Trip> merged = new HashMap<>(trips);
		merged.putAll(other.trips);
		return new TripUpdates(fetchedAt, Map.copyOf(merged));
	}

	private static void add(Map<String, Trip> trips, FeedEntity entity) {
		if (!entity.hasTripUpdate() || entity.getIsDeleted()) return;
		TripUpdate update = entity.getTripUpdate();
		TripDescriptor descriptor = update.getTrip();
		if (!descriptor.hasTripId()) return;

		List<StopTimeUpdate> stops = update.getStopTimeUpdateList();
		int n = stops.size();
		int[] sequences = new int[n], delays = new int[n];
		String[] stopIds = new String[n];
		long[] times = new long[n];
		boolean[] skipped = new boolean[n];
		for (int i = 0; i < n; i++) {
			StopTimeUpdate stop = stops.get(i);
			sequences[i] = stop.hasStopSequence() ? stop.getStopSequence() : -1;
			stopIds[i] = stop.hasStopId() ? stop.getStopId() : null;
			// A board shows departures, so the departure event wins over the arrival.
			StopTimeEvent event = stop.hasDeparture() ? stop.getDeparture() : stop.hasArrival() ? stop.getArrival() : null;
			boolean noData = stop.getScheduleRelationship() == StopTimeUpdate.ScheduleRelationship.NO_DATA;
			delays[i] = event != null && event.hasDelay() && !noData ? event.getDelay() : NO_DELAY;
			times[i] = event != null && event.hasTime() && !noData ? event.getTime() : NO_TIME;
			skipped[i] = stop.getScheduleRelationship() == StopTimeUpdate.ScheduleRelationship.SKIPPED;
		}
		boolean canceled = descriptor.getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED;
		trips.put(key(descriptor.getTripId(), descriptor.hasStartDate() ? descriptor.getStartDate() : null),
				new Trip(canceled, sequences, stopIds, delays, times, skipped));
	}

	private static String key(String tripId, String startDate) {
		return startDate == null ? tripId : tripId + "/" + startDate;
	}

	/**
	 * Realtime for the departure of a trip (on its service day) at one stop,
	 * or null if the feed knows nothing about that trip. A delay reported for
	 * an earlier stop carries over to later stops without their own update,
	 * as the GTFS-Realtime spec defines.
	 */
	public StopRealtime lookup(String tripId, LocalDate serviceDay, int stopSequence, String stopId) {
		Trip trip = trips.get(key(tripId, serviceDay.format(DateTimeFormatter.BASIC_ISO_DATE)));
		if (trip == null) trip = trips.get(tripId);
		if (trip == null) return null;
		if (trip.canceled()) return new StopRealtime(null, null, true);

		int carried = NO_DELAY;
		for (int i = 0; i < trip.sequences().length; i++) {
			boolean bySequence = trip.sequences()[i] >= 0;
			boolean here = bySequence ? trip.sequences()[i] == stopSequence : stopId.equals(trip.stopIds()[i]);
			if (here) {
				if (trip.skipped()[i]) return new StopRealtime(null, null, true);
				Instant time = trip.times()[i] != NO_TIME ? Instant.ofEpochSecond(trip.times()[i]) : null;
				Integer delay = null;
				if (trip.delays()[i] != NO_DELAY) delay = trip.delays()[i];
				// An absolute time says it all; a delay from earlier stops would only guess.
				else if (time == null && carried != NO_DELAY) delay = carried;
				return time == null && delay == null ? null : new StopRealtime(delay, time, false);
			}
			if (bySequence && trip.sequences()[i] > stopSequence) break;
			if (trip.delays()[i] != NO_DELAY) carried = trip.delays()[i];
		}
		return carried == NO_DELAY ? null : new StopRealtime(carried, null, false);
	}
}

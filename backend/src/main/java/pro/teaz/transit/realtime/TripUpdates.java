package pro.teaz.transit.realtime;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * One fetched GTFS-Realtime feed, reduced to what boards and journeys need:
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

	/**
	 * What realtime says about one stop of a trip's course: its arrival and
	 * departure (each null without data), or skipped (the vehicle doesn't stop).
	 */
	public record StopEvents(StopRealtime arrival, StopRealtime departure, boolean skipped) {
	}

	/** Realtime for a trip's whole course: canceled as a whole, else one entry per stop asked for. */
	public record Course(boolean canceled, List<StopEvents> stops) {
	}

	/**
	 * Stop updates of one trip, in feed order (by stop_sequence, as the spec
	 * requires). Arrival and departure each as delay and absolute time.
	 */
	private record Trip(boolean canceled, int[] sequences, String[] stopIds, int[] arrivalDelays, long[] arrivalTimes,
			int[] departureDelays, long[] departureTimes, boolean[] skipped) {

		boolean hasDeparture(int i) {
			return departureDelays[i] != NO_DELAY || departureTimes[i] != NO_TIME;
		}

		/** What a board shows: the departure event, else the arrival. */
		int delay(int i) {
			return hasDeparture(i) ? departureDelays[i] : arrivalDelays[i];
		}

		long time(int i) {
			return hasDeparture(i) ? departureTimes[i] : arrivalTimes[i];
		}
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
		int[] sequences = new int[n], arrivalDelays = new int[n], departureDelays = new int[n];
		String[] stopIds = new String[n];
		long[] arrivalTimes = new long[n], departureTimes = new long[n];
		boolean[] skipped = new boolean[n];
		for (int i = 0; i < n; i++) {
			StopTimeUpdate stop = stops.get(i);
			sequences[i] = stop.hasStopSequence() ? stop.getStopSequence() : -1;
			stopIds[i] = stop.hasStopId() ? stop.getStopId() : null;
			boolean noData = stop.getScheduleRelationship() == StopTimeUpdate.ScheduleRelationship.NO_DATA;
			StopTimeEvent arrival = stop.hasArrival() && !noData ? stop.getArrival() : null;
			StopTimeEvent departure = stop.hasDeparture() && !noData ? stop.getDeparture() : null;
			arrivalDelays[i] = arrival != null && arrival.hasDelay() ? arrival.getDelay() : NO_DELAY;
			arrivalTimes[i] = arrival != null && arrival.hasTime() ? arrival.getTime() : NO_TIME;
			departureDelays[i] = departure != null && departure.hasDelay() ? departure.getDelay() : NO_DELAY;
			departureTimes[i] = departure != null && departure.hasTime() ? departure.getTime() : NO_TIME;
			skipped[i] = stop.getScheduleRelationship() == StopTimeUpdate.ScheduleRelationship.SKIPPED;
		}
		boolean canceled = descriptor.getScheduleRelationship() == TripDescriptor.ScheduleRelationship.CANCELED;
		trips.put(key(descriptor.getTripId(), descriptor.hasStartDate() ? descriptor.getStartDate() : null),
				new Trip(canceled, sequences, stopIds, arrivalDelays, arrivalTimes, departureDelays, departureTimes,
						skipped));
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
		Trip trip = find(tripId, serviceDay);
		if (trip == null) return null;
		if (trip.canceled()) return new StopRealtime(null, null, true);

		int carried = NO_DELAY;
		for (int i = 0; i < trip.sequences().length; i++) {
			boolean bySequence = trip.sequences()[i] >= 0;
			boolean here = bySequence ? trip.sequences()[i] == stopSequence : stopId.equals(trip.stopIds()[i]);
			if (here) {
				if (trip.skipped()[i]) return new StopRealtime(null, null, true);
				Instant time = trip.time(i) != NO_TIME ? Instant.ofEpochSecond(trip.time(i)) : null;
				Integer delay = null;
				if (trip.delay(i) != NO_DELAY) delay = trip.delay(i);
				// An absolute time says it all; a delay from earlier stops would only guess.
				else if (time == null && carried != NO_DELAY) delay = carried;
				return time == null && delay == null ? null : new StopRealtime(delay, time, false);
			}
			if (bySequence && trip.sequences()[i] > stopSequence) break;
			if (trip.delay(i) != NO_DELAY) carried = trip.delay(i);
		}
		return carried == NO_DELAY ? null : new StopRealtime(carried, null, false);
	}

	/**
	 * Realtime for a trip's whole course (on its service day): for each of the
	 * given stops, in order, its arrival and departure. Null if the feed knows
	 * nothing about the trip.
	 *
	 * As in {@link #lookup}, a delay carries over to later events without
	 * their own update: from an arrival to the same stop's departure, and from
	 * a stop to the following ones. A stop's update is found by stop_sequence,
	 * or by stop_id where the feed gives no sequence.
	 */
	public Course course(String tripId, LocalDate serviceDay, int[] stopSequences, String[] stopIds) {
		Trip trip = find(tripId, serviceDay);
		if (trip == null) return null;
		if (trip.canceled()) return new Course(true, List.of());

		List<StopEvents> course = new ArrayList<>(stopSequences.length);
		int carried = NO_DELAY;
		int next = 0; // the first update not used yet
		for (int k = 0; k < stopSequences.length; k++) {
			// Updates for stops before this one (or not on the course at all) only pass on their delay.
			int own = -1;
			while (next < trip.sequences().length) {
				int sequence = trip.sequences()[next];
				boolean matches = sequence >= 0 ? sequence == stopSequences[k] : stopIds[k].equals(trip.stopIds()[next]);
				if (matches) {
					own = next++;
					break;
				}
				boolean before = sequence >= 0 ? sequence < stopSequences[k] : !onCourse(trip.stopIds()[next], stopIds, k);
				if (!before) break;
				if (trip.arrivalDelays()[next] != NO_DELAY) carried = trip.arrivalDelays()[next];
				if (trip.departureDelays()[next] != NO_DELAY) carried = trip.departureDelays()[next];
				next++;
			}
			if (own < 0) {
				StopRealtime guess = carried == NO_DELAY ? null : new StopRealtime(carried, null, false);
				course.add(new StopEvents(guess, guess, false));
				continue;
			}
			if (trip.skipped()[own]) {
				course.add(new StopEvents(null, null, true));
				continue;
			}
			StopRealtime arrival = event(trip.arrivalDelays()[own], trip.arrivalTimes()[own], carried);
			if (trip.arrivalDelays()[own] != NO_DELAY) carried = trip.arrivalDelays()[own];
			StopRealtime departure = event(trip.departureDelays()[own], trip.departureTimes()[own], carried);
			if (trip.departureDelays()[own] != NO_DELAY) carried = trip.departureDelays()[own];
			course.add(new StopEvents(arrival, departure, false));
		}
		return new Course(false, List.copyOf(course));
	}

	private static StopRealtime event(int delay, long time, int carried) {
		Instant at = time != NO_TIME ? Instant.ofEpochSecond(time) : null;
		if (delay != NO_DELAY) return new StopRealtime(delay, at, false);
		if (at != null) return new StopRealtime(null, at, false);
		return carried == NO_DELAY ? null : new StopRealtime(carried, null, false);
	}

	/** Whether the stop comes at or after position from of the course. */
	private static boolean onCourse(String stopId, String[] stopIds, int from) {
		for (int i = from; i < stopIds.length; i++) {
			if (stopIds[i].equals(stopId)) return true;
		}
		return false;
	}

	private Trip find(String tripId, LocalDate serviceDay) {
		Trip trip = trips.get(key(tripId, serviceDay.format(DateTimeFormatter.BASIC_ISO_DATE)));
		return trip != null ? trip : trips.get(tripId);
	}
}

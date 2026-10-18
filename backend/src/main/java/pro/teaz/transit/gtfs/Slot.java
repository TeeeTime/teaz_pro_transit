package pro.teaz.transit.gtfs;

import java.util.List;

/**
 * One of the two copies of the timetable tables (suffix _a / _b, see
 * V1__init.sql). Table names are only ever built from this enum, never from
 * input, so concatenating them into SQL is safe.
 */
public enum Slot {
	A, B;

	static final List<String> TABLES = List.of(
			"agencies", "routes", "stops", "trips", "trip_ends", "headsigns", "stop_times", "calendar", "calendar_dates");

	public String table(String base) {
		return base + "_" + name().toLowerCase();
	}

	public Slot other() {
		return this == A ? B : A;
	}
}

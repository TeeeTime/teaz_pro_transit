package pro.teaz.transit.station;

/**
 * A stop or station users can pick: a GTFS station (location_type 1) with its
 * platforms, or a stop without a parent station. Its board shows the
 * departures of all its platforms.
 */
public record Station(String id, String name, double lat, double lon) {

	/** Search result near a position. */
	public record Nearby(String id, String name, double lat, double lon, int distanceMeters) {
	}

	/** Great-circle distance in meters. */
	public static double distance(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
		double a = Math.pow(Math.sin(dLat / 2), 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
		return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}
}

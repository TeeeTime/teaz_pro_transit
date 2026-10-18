package pro.teaz.transit.station;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.transit.gtfs.FeedState;
import pro.teaz.transit.security.CurrentUser;

/** Finding stations, and the user's own pick. */
@RestController
@RequestMapping("/api")
class StationController {

	private static final int MAX_RESULTS = 30;
	private static final int NEARBY_RADIUS_METERS = 2_000;

	/** Response of /api/me/station; station is null if none is picked. */
	record MyStation(Station station) {
	}

	record ChooseStation(String stationId) {
	}

	private final StationRepository stations;
	private final UserStations userStations;
	private final FeedState feed;
	private final CurrentUser currentUser;

	StationController(StationRepository stations, UserStations userStations, FeedState feed, CurrentUser currentUser) {
		this.stations = stations;
		this.userStations = userStations;
		this.feed = feed;
		this.currentUser = currentUser;
	}

	@GetMapping("/stations")
	List<Station> search(@RequestParam String query, @RequestParam(defaultValue = "10") int limit) {
		if (query.trim().length() < 2) return List.of();
		return stations.search(feed.require(), query, clamp(limit));
	}

	@GetMapping("/stations/nearby")
	List<Station.Nearby> nearby(@RequestParam double lat, @RequestParam double lon,
			@RequestParam(defaultValue = "10") int limit) {
		return stations.nearby(feed.require(), lat, lon, NEARBY_RADIUS_METERS, clamp(limit));
	}

	@GetMapping("/stations/{id}")
	Station get(@PathVariable String id) {
		return stations.find(feed.require(), id).orElseThrow(StationController::notFound);
	}

	@GetMapping("/me/station")
	MyStation myStation() {
		return new MyStation(userStations.find(currentUser.id()).orElse(null));
	}

	@PutMapping("/me/station")
	MyStation chooseStation(@RequestBody ChooseStation body) {
		if (body.stationId() == null || body.stationId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId is required.");
		}
		return new MyStation(userStations.choose(currentUser.id(), body.stationId()).orElseThrow(StationController::notFound));
	}

	@DeleteMapping("/me/station")
	ResponseEntity<Void> clearStation() {
		userStations.clear(currentUser.id());
		return ResponseEntity.noContent().build();
	}

	static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such station in the current timetable.");
	}

	private static int clamp(int limit) {
		return Math.clamp(limit, 1, MAX_RESULTS);
	}
}

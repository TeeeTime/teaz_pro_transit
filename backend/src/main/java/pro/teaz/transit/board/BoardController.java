package pro.teaz.transit.board;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.transit.gtfs.FeedState;
import pro.teaz.transit.security.CurrentUser;
import pro.teaz.transit.station.StationRepository;
import pro.teaz.transit.station.UserStations;

/**
 * Departure boards. {@code /api/board} (the user's own station) is the API
 * for other apps, e.g. a tile in Portal; see README.
 */
@RestController
@RequestMapping("/api")
class BoardController {

	private final DepartureService departures;
	private final UserStations userStations;
	private final StationRepository stations;
	private final FeedState feed;
	private final CurrentUser currentUser;

	BoardController(DepartureService departures, UserStations userStations, StationRepository stations, FeedState feed,
			CurrentUser currentUser) {
		this.departures = departures;
		this.userStations = userStations;
		this.stations = stations;
		this.feed = feed;
		this.currentUser = currentUser;
	}

	@GetMapping("/board")
	Board myBoard(@RequestParam(defaultValue = "5") int limit, @RequestParam(defaultValue = "60") int minutes) {
		return userStations.find(currentUser.id())
				.map(station -> departures.board(station, clampLimit(limit), clampMinutes(minutes)))
				.orElseGet(departures::empty);
	}

	@GetMapping("/stations/{id}/departures")
	Board stationBoard(@PathVariable String id, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "90") int minutes) {
		var station = stations.find(feed.require(), id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such station in the current timetable."));
		return departures.board(station, clampLimit(limit), clampMinutes(minutes));
	}

	private static int clampLimit(int limit) {
		return Math.clamp(limit, 1, DepartureService.MAX_LIMIT);
	}

	private static int clampMinutes(int minutes) {
		return Math.clamp(minutes, 1, DepartureService.MAX_MINUTES);
	}
}

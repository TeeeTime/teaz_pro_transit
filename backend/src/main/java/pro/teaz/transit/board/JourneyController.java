package pro.teaz.transit.board;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A departure's trip: its whole course and where the vehicle is right now. */
@RestController
class JourneyController {

	private final JourneyService journeys;

	JourneyController(JourneyService journeys) {
		this.journeys = journeys;
	}

	/**
	 * @param day     the departure's serviceDay; today's service day if omitted
	 * @param station the board it was opened from, so the trip's realtime is kept
	 */
	@GetMapping("/api/trips/{tripId}")
	Journey journey(@PathVariable String tripId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
			@RequestParam(required = false) String station) {
		try {
			return journeys.journey(tripId, day != null ? day : journeys.today(), station)
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such trip in the current timetable."));
		} catch (JourneyService.NotRunningException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
		}
	}
}

package pro.teaz.transit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import pro.teaz.transit.gtfs.FeedState;

/** No timetable yet (first start, import running) as 503 with a readable detail. */
@RestControllerAdvice
public class ApiErrors {

	@ExceptionHandler(FeedState.NotReadyException.class)
	public ProblemDetail notReady(FeedState.NotReadyException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
	}
}

package pro.teaz.transit.gtfs;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import pro.teaz.transit.realtime.RealtimeFeed;

/** Which timetable is loaded and how fresh the data is, for the footer of the UI. */
@RestController
class FeedController {

	/**
	 * @param ready             a timetable is loaded (false only before the first import finished)
	 * @param version           the loaded feed's version (ETag / Last-Modified of the download)
	 * @param importedAt        when it was loaded
	 * @param importing         an import is running right now
	 * @param lastError         why the last import failed, null if it didn't
	 * @param realtime          realtime is configured
	 * @param realtimeUpdatedAt when realtime data was last fetched, null if never
	 */
	record FeedInfo(boolean ready, String source, String version, Instant importedAt, boolean importing,
			Instant lastCheck, String lastError, boolean realtime, Instant realtimeUpdatedAt) {
	}

	private final FeedState feed;
	private final FeedImporter importer;
	private final RealtimeFeed realtime;

	FeedController(FeedState feed, FeedImporter importer, RealtimeFeed realtime) {
		this.feed = feed;
		this.importer = importer;
		this.realtime = realtime;
	}

	@GetMapping("/api/feed")
	FeedInfo info() {
		FeedState.Active active = feed.active();
		FeedImporter.Status status = importer.status();
		return new FeedInfo(active != null,
				active == null ? null : active.source(),
				active == null ? null : active.version(),
				active == null ? null : active.importedAt(),
				status.running(), status.lastCheck(), status.lastError(),
				realtime.enabled(), realtime.fetchedAt());
	}
}

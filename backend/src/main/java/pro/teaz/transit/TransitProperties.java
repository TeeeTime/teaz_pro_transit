package pro.teaz.transit;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code transit.*}, see application.properties.
 *
 * @param timezone the timetable's time zone (GTFS times are local times)
 * @param gtfs     where the timetable comes from and when it's refreshed
 * @param realtime delays and cancellations
 * @param cors     other Teaz apps allowed to read /api/board from the browser
 */
@ConfigurationProperties("transit")
public record TransitProperties(
		@DefaultValue("Europe/Berlin") ZoneId timezone,
		@DefaultValue Gtfs gtfs,
		@DefaultValue Realtime realtime,
		@DefaultValue Cors cors) {

	/**
	 * @param feedUrl          GTFS zip; unset or blank = the bundled sample feed
	 * @param updateCron       when to check for a new feed version
	 * @param importOnStartup  import right after startup if no feed is loaded yet
	 * @param workDir          where the downloaded zip is kept during an import (default: temp dir)
	 * @param attribution      data source and license, shown with every board
	 */
	public record Gtfs(
			String feedUrl,
			@DefaultValue("") String attribution,
			@DefaultValue("0 30 4 * * *") String updateCron,
			@DefaultValue("true") boolean importOnStartup,
			Path workDir) {

		public Path workDirOrTemp() {
			return workDir != null ? workDir : Path.of(System.getProperty("java.io.tmpdir"));
		}

		public boolean useSample() {
			return feedUrl == null || feedUrl.isBlank();
		}
	}

	/**
	 * @param url          GTFS-Realtime TripUpdates feed; unset or blank = planned times only
	 * @param pollInterval how often to fetch it while someone looks at departures
	 * @param idleAfter    stop fetching when nobody asked for departures for this long
	 * @param maxAge       older data is not used (the feed stopped updating)
	 * @param attribution  data source and license, shown with boards that use it
	 */
	public record Realtime(
			String url,
			@DefaultValue("") String attribution,
			@DefaultValue("60s") Duration pollInterval,
			@DefaultValue("10m") Duration idleAfter,
			@DefaultValue("5m") Duration maxAge) {

		public boolean enabled() {
			return url != null && !url.isBlank();
		}
	}

	public record Cors(@DefaultValue List<String> allowedOrigins) {
	}
}

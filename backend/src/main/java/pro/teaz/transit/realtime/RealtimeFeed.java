package pro.teaz.transit.realtime;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.google.transit.realtime.GtfsRealtime.FeedMessage;

import pro.teaz.transit.TransitProperties;

/**
 * Keeps the latest GTFS-Realtime TripUpdates in memory. The feed for all of
 * Germany is several MB, so it's only fetched while someone actually looks
 * at departures (every poll-interval, until idle-after without a request),
 * not around the clock.
 */
@Component
public class RealtimeFeed {

	private static final Logger log = LoggerFactory.getLogger(RealtimeFeed.class);

	private final TransitProperties.Realtime settings;
	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	private final AtomicBoolean fetching = new AtomicBoolean();
	private volatile TripUpdates current;
	private volatile Instant lastDemand = Instant.EPOCH;
	private volatile Instant lastAttempt = Instant.EPOCH;

	RealtimeFeed(TransitProperties properties) {
		this.settings = properties.realtime();
	}

	public boolean enabled() {
		return settings.enabled();
	}

	/**
	 * The latest updates, or null if realtime is off, not fetched yet or too old
	 * to trust. Counts as demand: starts fetching if nobody asked for a while.
	 */
	public TripUpdates current() {
		if (!settings.enabled()) return null;
		lastDemand = Instant.now();
		if (stale(settings.pollInterval())) fetchInBackground();
		TripUpdates updates = current;
		return updates != null && !isOlderThan(updates.fetchedAt(), settings.maxAge()) ? updates : null;
	}

	/** When the data in use was fetched, null if none (for /api/feed; no demand). */
	public Instant fetchedAt() {
		TripUpdates updates = current;
		return updates == null ? null : updates.fetchedAt();
	}

	@Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
	void poll() {
		if (!settings.enabled() || isOlderThan(lastDemand, settings.idleAfter())) return;
		if (stale(settings.pollInterval())) fetchInBackground();
	}

	private boolean stale(Duration interval) {
		// Also wait after failed attempts, so an outage isn't hammered.
		return isOlderThan(lastAttempt, interval);
	}

	private static boolean isOlderThan(Instant time, Duration age) {
		return time.plus(age).isBefore(Instant.now());
	}

	private void fetchInBackground() {
		if (!fetching.compareAndSet(false, true)) return;
		lastAttempt = Instant.now();
		Thread.ofVirtual().name("gtfs-rt-fetch").start(() -> {
			try {
				fetch();
			} catch (Exception e) {
				log.warn("GTFS-Realtime fetch failed: {}", e.toString());
			} finally {
				fetching.set(false);
			}
		});
	}

	private void fetch() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(settings.url()))
				.header("Accept-Encoding", "gzip")
				.timeout(Duration.ofSeconds(60))
				.build();
		long started = System.nanoTime();
		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
			boolean gzip = response.headers().firstValue("Content-Encoding").filter("gzip"::equalsIgnoreCase).isPresent();
			try (InputStream in = gzip ? new GZIPInputStream(body, 1 << 16) : body) {
				current = TripUpdates.from(FeedMessage.parseFrom(in), Instant.now());
			}
		}
		log.debug("GTFS-Realtime: {} trips in {} ms", current.tripCount(), Duration.ofNanos(System.nanoTime() - started).toMillis());
	}
}

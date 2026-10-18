package pro.teaz.transit.gtfs;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which slot holds the timetable that is served right now (table feed_state).
 * Read once, then kept in memory; only the importer changes it.
 */
@Component
public class FeedState {

	/**
	 * @param slot       where the timetable's tables are
	 * @param version    the feed's version (HTTP ETag / Last-Modified), to skip unchanged downloads
	 * @param source     the URL it came from ("sample" for the bundled feed)
	 * @param importedAt when the import finished
	 */
	public record Active(Slot slot, String version, String source, Instant importedAt) {
	}

	private final JdbcClient jdbc;
	private volatile Active active;
	private volatile boolean loaded;

	FeedState(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The served timetable, or null before the first import finished. */
	public Active active() {
		if (!loaded) {
			synchronized (this) {
				if (!loaded) {
					active = jdbc.sql("SELECT active_slot, version, source, imported_at FROM feed_state WHERE id = 1 AND active_slot IS NOT NULL")
							.query((rs, i) -> new Active(
									Slot.valueOf(rs.getString(1).trim().toUpperCase()),
									rs.getString(2),
									rs.getString(3),
									rs.getObject(4, OffsetDateTime.class).toInstant()))
							.optional()
							.orElse(null);
					loaded = true;
				}
			}
		}
		return active;
	}

	/** Like {@link #active()}, for requests that can't be answered without a timetable. */
	public Active require() {
		Active current = active();
		if (current == null) throw new NotReadyException();
		return current;
	}

	/** No timetable imported yet (first start); answered with 503. */
	public static class NotReadyException extends RuntimeException {
		NotReadyException() {
			super("The timetable is still being imported. Please try again in a few minutes.");
		}
	}

	synchronized void activate(Slot slot, String version, String source) {
		Instant now = Instant.now();
		jdbc.sql("UPDATE feed_state SET active_slot = ?, version = ?, source = ?, imported_at = ? WHERE id = 1")
				.params(slot.name().toLowerCase(), version, source, now.atOffset(ZoneOffset.UTC))
				.update();
		active = new Active(slot, version, source, now);
		loaded = true;
	}
}

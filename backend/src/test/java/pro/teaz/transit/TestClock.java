package pro.teaz.transit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** A clock tests can set, so boards are computed for a known moment. */
public class TestClock extends Clock {

	public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

	private volatile Instant now = Instant.now();

	/** Sets "now" to a local time in Berlin. */
	public void set(LocalDateTime berlinTime) {
		now = berlinTime.atZone(BERLIN).toInstant();
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

	@TestConfiguration
	public static class Config {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}
}

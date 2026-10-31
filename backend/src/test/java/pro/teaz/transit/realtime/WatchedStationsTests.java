package pro.teaz.transit.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import pro.teaz.transit.TestClock;
import pro.teaz.transit.gtfs.FeedImporter;
import pro.teaz.transit.gtfs.FeedState;

/**
 * Which trips are kept for realtime, against the bundled sample feed: bus 11
 * runs Hbf - Nordpark, RE 1 Hbf - Beispielhausen, both every day.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClock.Config.class)
class WatchedStationsTests {

	@Autowired
	WatchedStations watched;

	@Autowired
	FeedImporter importer;

	@Autowired
	FeedState feed;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestClock clock;

	@BeforeEach
	void loadSampleFeed() {
		importer.update();
		clock.set(LocalDateTime.of(2026, 10, 19, 8, 0));
		jdbc.sql("DELETE FROM user_station").update();
		watched.dropUnpicked();
	}

	private void pick(String userId, String stationId) {
		jdbc.sql("INSERT INTO user_station (user_id, station_id, station_name, lat, lon, updated_at) VALUES (?, ?, 'x', 0, 0, ?)")
				.params(userId, stationId, OffsetDateTime.now())
				.update();
	}

	@Test
	void keepsOnlyTheTripsOfTheStation() {
		watched.add(feed.require(), "nordpark");

		assertThat(watched.tripIds()).isNotEmpty().allMatch(id -> id.startsWith("bus11_"));
		assertThat(watched.shown(feed.require(), "nordpark")).isTrue();
		assertThat(watched.shown(feed.require(), "beispiel")).isFalse();
	}

	@Test
	void pickedStationsStayShownOnesAreDroppedWhenIdle() {
		pick("a", "beispiel");
		watched.add(feed.require(), "nordpark");
		watched.refresh(feed.require());
		assertThat(watched.tripIds()).anyMatch(id -> id.startsWith("re1_")).anyMatch(id -> id.startsWith("bus11_"));

		clock.set(LocalDateTime.of(2026, 10, 19, 8, 11));
		watched.refresh(feed.require());
		assertThat(watched.tripIds()).isNotEmpty().allMatch(id -> id.startsWith("re1_"));

		jdbc.sql("DELETE FROM user_station").update();
		watched.refresh(feed.require());
		assertThat(watched.tripIds()).isEmpty();
	}

	@Test
	void reloadsForANewDay() {
		watched.add(feed.require(), "nordpark");
		clock.set(LocalDateTime.of(2026, 10, 20, 8, 0));
		assertThat(watched.shown(feed.require(), "nordpark")).isFalse();
	}
}

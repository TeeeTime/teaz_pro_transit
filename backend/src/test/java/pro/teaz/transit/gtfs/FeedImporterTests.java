package pro.teaz.transit.gtfs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FeedImporterTests {

	@Autowired
	FeedImporter importer;

	@Autowired
	FeedState feed;

	@Autowired
	JdbcClient jdbc;

	private long count(Slot slot, String table) {
		return jdbc.sql("SELECT COUNT(*) FROM " + slot.table(table)).query(Long.class).single();
	}

	@Test
	void importFillsTheOtherSlotAndEmptiesTheOldOne() throws Exception {
		importer.update();
		Slot before = feed.require().slot();

		try (GtfsSource source = GtfsSource.classpath("sample-feed")) {
			importer.importFeed(source, "test-reimport", FeedImporter.SAMPLE);
		}

		Slot after = feed.require().slot();
		assertThat(after).isEqualTo(before.other());
		assertThat(feed.require().version()).isEqualTo("test-reimport");
		assertThat(count(after, "stop_times")).isEqualTo(1392);
		assertThat(count(after, "trip_ends")).isEqualTo(count(after, "trips")).isEqualTo(368);
		// The entrance (location_type 2) is left out.
		assertThat(count(after, "stops")).isEqualTo(9);
		for (String table : Slot.TABLES) assertThat(count(before, table)).as(table).isZero();

		// Back to the regular sample version, so other tests see the usual state.
		importer.update();
		assertThat(feed.require().version()).isNotEqualTo("test-reimport");
	}

	@Test
	void parsesGtfsTimesPastMidnight() {
		assertThat(FeedImporter.parseTime("07:09:00")).isEqualTo(7 * 3600 + 9 * 60);
		assertThat(FeedImporter.parseTime("7:09:30")).isEqualTo(7 * 3600 + 9 * 60 + 30);
		assertThat(FeedImporter.parseTime("25:10:00")).isEqualTo(25 * 3600 + 10 * 60);
	}

	@Test
	void searchNameIgnoresCaseAndAccents() {
		assertThat(FeedImporter.searchName("Münster (Westf) Hbf")).isEqualTo("munster (westf) hbf");
		assertThat(FeedImporter.searchName("Gießen")).isEqualTo("giessen");
	}
}

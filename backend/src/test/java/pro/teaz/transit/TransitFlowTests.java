package pro.teaz.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import pro.teaz.transit.gtfs.FeedImporter;

/**
 * Station search, picking a station and its departure board against the
 * bundled sample feed (src/main/resources/sample-feed), with the real prod
 * security and a fixed clock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"prod", "test"})
@Import(TestClock.Config.class)
class TransitFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	FeedImporter importer;

	@Autowired
	TestClock clock;

	@BeforeEach
	void loadSampleFeed() {
		importer.update();
		clock.set(LocalDateTime.of(2026, 10, 19, 8, 0));
	}

	private static JwtRequestPostProcessor user(String id) {
		return jwt().jwt(token -> token.subject(id));
	}

	private JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static List<String> texts(JsonNode array, String field) {
		List<String> values = new ArrayList<>();
		array.forEach(node -> values.add(node.path(field).asString()));
		return values;
	}

	@Test
	void searchIgnoresCaseAndAccentsAndRanksBestMatchFirst() throws Exception {
		JsonNode hbf = body(mvc.perform(get("/api/stations").param("query", "musterstadt hbf").with(user("a"))));
		assertThat(texts(hbf, "id")).containsExactly("hbf");

		JsonNode uni = body(mvc.perform(get("/api/stations").param("query", "UNIVERSITAT").with(user("a"))));
		assertThat(texts(uni, "name")).containsExactly("Musterstadt Universität");

		// Platforms and entrances are not stations of their own.
		JsonNode all = body(mvc.perform(get("/api/stations").param("query", "muster").param("limit", "30").with(user("a"))));
		assertThat(texts(all, "id")).doesNotContain("hbf_1", "hbf_bus").contains("hbf", "markt", "west");
		assertThat(body(mvc.perform(get("/api/stations").param("query", "beispiel").with(user("a")))).size()).isEqualTo(1);
	}

	@Test
	void platformNamesFindTheirStation() throws Exception {
		// The bus bay is called "Musterstadt Hbf/Busbahnhof", its station "Musterstadt Hbf".
		JsonNode found = body(mvc.perform(get("/api/stations").param("query", "busbahnhof").with(user("a"))));
		assertThat(texts(found, "id")).containsExactly("hbf");
		assertThat(texts(found, "name")).containsExactly("Musterstadt Hbf");
	}

	@Test
	void nearbyIsSortedByDistance() throws Exception {
		JsonNode nearby = body(mvc.perform(get("/api/stations/nearby").param("lat", "51.0039").param("lon", "10.0049")
				.with(user("a"))));
		assertThat(texts(nearby, "id")).startsWith("markt", "hbf");
		assertThat(nearby.get(0).path("distanceMeters").asInt()).isLessThan(50);
	}

	@Test
	void boardShowsAllPlatformsOfTheStationInTimeOrder() throws Exception {
		JsonNode board = body(mvc.perform(get("/api/stations/hbf/departures").param("minutes", "30").with(user("a"))));
		assertThat(board.path("station").path("name").asString()).isEqualTo("Musterstadt Hbf");
		assertThat(board.path("realtime").asBoolean()).isFalse();
		assertThat(board.path("attribution").asString()).contains("Sample");

		JsonNode departures = board.path("departures");
		// Bus every 10 min from bay A, RE 1 every 30 min from platform 1; RE 1 from Beispielhausen ends here.
		assertThat(texts(departures, "plannedTime")).containsExactly(
				"2026-10-19T08:00:00+02:00", "2026-10-19T08:01:00+02:00", "2026-10-19T08:10:00+02:00",
				"2026-10-19T08:20:00+02:00", "2026-10-19T08:30:00+02:00");
		JsonNode bus = departures.get(0), train = departures.get(1);
		assertThat(bus.path("line").asString()).isEqualTo("11");
		assertThat(bus.path("mode").asString()).isEqualTo("BUS");
		assertThat(bus.path("destination").asString()).isEqualTo("Nordpark");
		assertThat(bus.path("platform").asString()).isEqualTo("A");
		assertThat(bus.path("agency").asString()).isEqualTo("Musterstadt Verkehr");
		assertThat(bus.path("expectedTime").isNull()).isTrue();
		assertThat(bus.path("serviceDay").asString()).isEqualTo("2026-10-19");
		assertThat(train.path("line").asString()).isEqualTo("RE 1");
		assertThat(train.path("mode").asString()).isEqualTo("TRAIN");
		// No headsign in the feed: the trip's last stop.
		assertThat(train.path("destination").asString()).isEqualTo("Beispielhausen Bahnhof");
		assertThat(train.path("platform").asString()).isEqualTo("1");
	}

	@Test
	void afterMidnightShowsTripsOfTheDayBefore() throws Exception {
		// The last RE 1 leaves at "24:31:00" of the 18th, i.e. 00:31 on the 19th.
		clock.set(LocalDateTime.of(2026, 10, 19, 0, 25));
		JsonNode departures = body(mvc.perform(get("/api/stations/hbf/departures").param("minutes", "10")
				.with(user("a")))).path("departures");
		assertThat(texts(departures, "line")).containsExactly("11", "RE 1");
		assertThat(departures.get(1).path("plannedTime").asString()).isEqualTo("2026-10-19T00:31:00+02:00");
		assertThat(departures.get(1).path("serviceDay").asString()).isEqualTo("2026-10-18");
	}

	private JsonNode journey(String tripId, String day) throws Exception {
		return body(mvc.perform(get("/api/trips/" + tripId).param("day", day).param("station", "hbf").with(user("a"))));
	}

	@Test
	void journeyListsTheTripsStopsWithArrivalAndDeparture() throws Exception {
		clock.set(LocalDateTime.of(2026, 10, 19, 8, 4));
		JsonNode journey = journey("re1_0_480", "2026-10-19");
		assertThat(journey.path("line").asString()).isEqualTo("RE 1");
		assertThat(journey.path("mode").asString()).isEqualTo("TRAIN");
		assertThat(journey.path("destination").asString()).isEqualTo("Beispielhausen Bahnhof");
		assertThat(journey.path("realtime").asBoolean()).isFalse();
		assertThat(journey.path("tracked").asBoolean()).isFalse();
		assertThat(journey.path("canceled").asBoolean()).isFalse();
		assertThat(journey.path("attribution").asString()).contains("Sample");

		JsonNode stops = journey.path("stops");
		assertThat(texts(stops, "name")).containsExactly("Musterstadt Hbf", "Musterstadt West", "Beispielhausen Bahnhof");
		assertThat(texts(stops, "stationId")).containsExactly("hbf", "west", "beispiel");
		assertThat(stops.get(0).path("platform").asString()).isEqualTo("1");
		assertThat(stops.get(1).path("plannedArrival").asString()).isEqualTo("2026-10-19T08:07:00+02:00");
		assertThat(stops.get(1).path("plannedDeparture").asString()).isEqualTo("2026-10-19T08:08:00+02:00");
		assertThat(stops.get(1).path("expectedArrival").isNull()).isTrue();
		assertThat(stops.get(1).path("delayMinutes").isNull()).isTrue();
		assertThat(texts(stops, "passed")).containsExactly("true", "false", "false");

		// Left Hbf at 08:01, due at West at 08:07: half way there.
		JsonNode position = journey.path("position");
		assertThat(position.path("status").asString()).isEqualTo("BETWEEN");
		assertThat(position.path("stopIndex").asInt()).isZero();
		assertThat(position.path("nextStopIndex").asInt()).isEqualTo(1);
		assertThat(position.path("progress").asDouble()).isEqualTo(0.5);
	}

	@Test
	void journeyPositionFollowsTheClock() throws Exception {
		record Moment(int hour, int minute, int second, String status, int stopIndex) {
		}
		for (Moment moment : List.of(
				new Moment(7, 55, 0, "NOT_STARTED", 0),
				new Moment(8, 0, 30, "AT_STOP", 0),
				new Moment(8, 7, 30, "AT_STOP", 1),
				// Departure 08:08 plus a little dwell.
				new Moment(8, 8, 20, "AT_STOP", 1),
				new Moment(8, 9, 0, "BETWEEN", 1),
				new Moment(8, 20, 0, "ARRIVED", 2))) {
			clock.set(LocalDateTime.of(2026, 10, 19, moment.hour(), moment.minute(), moment.second()));
			JsonNode position = journey("re1_0_480", "2026-10-19").path("position");
			assertThat(position.path("status").asString()).as(moment.toString()).isEqualTo(moment.status());
			assertThat(position.path("stopIndex").asInt()).as(moment.toString()).isEqualTo(moment.stopIndex());
		}
	}

	@Test
	void journeyAfterMidnightBelongsToTheDayBefore() throws Exception {
		clock.set(LocalDateTime.of(2026, 10, 19, 0, 35));
		JsonNode journey = journey("re1_0_1470", "2026-10-18");
		assertThat(journey.path("stops").get(0).path("plannedDeparture").asString()).isEqualTo("2026-10-19T00:31:00+02:00");
		assertThat(journey.path("position").path("status").asString()).isEqualTo("BETWEEN");
	}

	@Test
	void journeyOfUnknownTripsOrDaysWithoutServiceIsNotFound() throws Exception {
		mvc.perform(get("/api/trips/nope").with(user("a"))).andExpect(status().isNotFound());
		// The sample's only service doesn't run on 2099-12-31.
		mvc.perform(get("/api/trips/re1_0_480").param("day", "2099-12-31").with(user("a"))).andExpect(status().isNotFound());
		// Without a day: today's service day.
		assertThat(body(mvc.perform(get("/api/trips/re1_0_480").with(user("a")))).path("serviceDay").asString())
				.isEqualTo("2026-10-19");
	}

	@Test
	void eachUserHasTheirOwnStation() throws Exception {
		JsonNode empty = body(mvc.perform(get("/api/board").with(user("new-user"))));
		assertThat(empty.path("station").isNull()).isTrue();
		assertThat(empty.path("departures").size()).isZero();

		mvc.perform(put("/api/me/station").with(user("alice"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"uni\"}"))
			.andExpect(status().isOk());
		mvc.perform(put("/api/me/station").with(user("bob"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"hbf\"}"))
			.andExpect(status().isOk());

		assertThat(body(mvc.perform(get("/api/me/station").with(user("alice")))).path("station").path("id").asString())
				.isEqualTo("uni");
		JsonNode board = body(mvc.perform(get("/api/board").param("limit", "3").with(user("bob"))));
		assertThat(board.path("station").path("id").asString()).isEqualTo("hbf");
		assertThat(board.path("departures").size()).isEqualTo(3);

		mvc.perform(delete("/api/me/station").with(user("bob"))).andExpect(status().isNoContent());
		assertThat(body(mvc.perform(get("/api/me/station").with(user("bob")))).path("station").isNull()).isTrue();
	}

	@Test
	void unknownStationIsNotFound() throws Exception {
		mvc.perform(put("/api/me/station").with(user("a"))
				.contentType(MediaType.APPLICATION_JSON).content("{\"stationId\":\"hbf_1\"}"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/stations/nope/departures").with(user("a"))).andExpect(status().isNotFound());
	}
}

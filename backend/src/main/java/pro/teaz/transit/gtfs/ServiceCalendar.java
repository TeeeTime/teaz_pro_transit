package pro.teaz.transit.gtfs;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which services (GTFS service_id) run on a given day, from calendar.txt and
 * calendar_dates.txt. Both are small (a few thousand rows for all of
 * Germany), so they're held in memory per loaded timetable.
 */
@Component
public class ServiceCalendar {

	private record Service(String id, int days, LocalDate start, LocalDate end) {
	}

	private record ExceptionDate(String serviceId, LocalDate date, boolean added) {
	}

	/** The calendar of one imported timetable, with the days asked for so far. */
	private record Loaded(FeedState.Active feed, List<Service> services, List<ExceptionDate> exceptions,
			Map<LocalDate, Set<String>> days) {
	}

	private final JdbcClient jdbc;
	private volatile Loaded loaded;

	ServiceCalendar(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The service_ids running on that day in the given timetable. */
	public Set<String> activeServices(FeedState.Active feed, LocalDate day) {
		Loaded current = loaded;
		if (current == null || !current.feed().equals(feed)) {
			current = load(feed);
			loaded = current;
		}
		// Boards only ever ask for yesterday, today and tomorrow.
		if (current.days().size() > 16) current.days().clear();
		Loaded calendar = current;
		return calendar.days().computeIfAbsent(day, d -> compute(calendar, d));
	}

	private Loaded load(FeedState.Active feed) {
		Slot slot = feed.slot();
		List<Service> services = jdbc.sql("SELECT service_id, days, start_date, end_date FROM " + slot.table("calendar"))
				.query((rs, i) -> new Service(rs.getString(1), rs.getInt(2), rs.getObject(3, LocalDate.class),
						rs.getObject(4, LocalDate.class)))
				.list();
		List<ExceptionDate> exceptions = jdbc.sql("SELECT service_id, service_date, added FROM " + slot.table("calendar_dates"))
				.query((rs, i) -> new ExceptionDate(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getBoolean(3)))
				.list();
		return new Loaded(feed, services, exceptions, new ConcurrentHashMap<>());
	}

	private static Set<String> compute(Loaded calendar, LocalDate day) {
		int weekdayBit = 1 << (day.getDayOfWeek().getValue() - 1);
		Set<String> active = new HashSet<>();
		for (Service service : calendar.services()) {
			if ((service.days() & weekdayBit) != 0 && !day.isBefore(service.start()) && !day.isAfter(service.end())) {
				active.add(service.id());
			}
		}
		for (ExceptionDate exception : calendar.exceptions()) {
			if (!exception.date().equals(day)) continue;
			if (exception.added()) active.add(exception.serviceId());
			else active.remove(exception.serviceId());
		}
		return Set.copyOf(active);
	}
}

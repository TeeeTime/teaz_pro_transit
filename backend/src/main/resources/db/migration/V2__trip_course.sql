-- A trip's course (all its stops, in order, with arrival and departure),
-- for the journey view of a departure (board/JourneyService.java).
--
-- arrival_secs: like departure_secs; null in timetables imported before this
-- migration (then the departure time stands in), filled by the next import.
-- The index on trip_id is dropped and recreated around each load like the
-- other one; keep the definitions in FeedImporter in sync. With Postgres'
-- B-tree deduplication it stores each trip_id once (about 20 stop times per
-- trip), so it is far smaller than one entry per row.

ALTER TABLE stop_times_a ADD COLUMN arrival_secs integer;
ALTER TABLE stop_times_b ADD COLUMN arrival_secs integer;

CREATE INDEX stop_times_a_trip ON stop_times_a (trip_id);
CREATE INDEX stop_times_b_trip ON stop_times_b (trip_id);

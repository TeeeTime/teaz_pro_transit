-- The GTFS timetable is kept twice, in slot "a" and slot "b" (same tables
-- with suffix _a/_b). An import fills the inactive slot while the active one
-- keeps serving departures, then switches feed_state.active_slot and empties
-- the old slot. See gtfs/FeedImporter.java.

CREATE TABLE feed_state (
    id          smallint PRIMARY KEY,
    active_slot char(1),
    version     varchar,
    source      varchar,
    imported_at timestamp with time zone
);
INSERT INTO feed_state (id) VALUES (1);

-- The one station each user picked. Name and position are kept so the
-- choice survives a new feed in which the station got a different id.
CREATE TABLE user_station (
    user_id      varchar PRIMARY KEY,
    station_id   varchar NOT NULL,
    station_name varchar NOT NULL,
    lat          double precision NOT NULL,
    lon          double precision NOT NULL,
    updated_at   timestamp with time zone NOT NULL
);

-- Slot a --------------------------------------------------------------

CREATE TABLE agencies_a (
    agency_id varchar PRIMARY KEY,
    name      varchar NOT NULL
);

CREATE TABLE routes_a (
    route_id   varchar PRIMARY KEY,
    agency_id  varchar,
    short_name varchar,
    long_name  varchar,
    route_type integer NOT NULL
);

-- Every stop, platform and station. station_id: the station a stop belongs
-- to (its parent_station, or the stop itself if it has none).
CREATE TABLE stops_a (
    stop_id     varchar PRIMARY KEY,
    station_id  varchar NOT NULL,
    name        varchar NOT NULL,
    search_name varchar NOT NULL,
    lat         double precision NOT NULL,
    lon         double precision NOT NULL,
    platform    varchar,
    is_station  boolean NOT NULL
);
CREATE INDEX stops_a_station ON stops_a (station_id);
CREATE INDEX stops_a_lat ON stops_a (lat);

CREATE TABLE trips_a (
    trip_id    varchar PRIMARY KEY,
    route_id   varchar NOT NULL,
    service_id varchar NOT NULL,
    headsign   varchar,
    short_name varchar
);

-- Last stop of every trip: its name is the fallback destination, and no
-- departure is shown there (the trip ends).
CREATE TABLE trip_ends_a (
    trip_id       varchar PRIMARY KEY,
    last_stop_id  varchar NOT NULL,
    last_sequence integer NOT NULL
);

-- Distinct stop_headsign values, so stop_times stores a number instead.
CREATE TABLE headsigns_a (
    headsign_id integer PRIMARY KEY,
    text        varchar NOT NULL
);

-- By far the largest table (tens of millions of rows for all of Germany).
-- departure_secs: seconds after the service day's start, may exceed 24h.
CREATE TABLE stop_times_a (
    trip_id        varchar NOT NULL,
    stop_id        varchar NOT NULL,
    stop_sequence  integer NOT NULL,
    departure_secs integer NOT NULL,
    headsign_id    integer,
    no_pickup      boolean NOT NULL
);
-- The importer drops and recreates this index around loading (much faster
-- than updating it row by row); keep the definition in FeedImporter in sync.
CREATE INDEX stop_times_a_stop_dep ON stop_times_a (stop_id, departure_secs);

-- days: bit 0 = Monday ... bit 6 = Sunday.
CREATE TABLE calendar_a (
    service_id varchar PRIMARY KEY,
    days       smallint NOT NULL,
    start_date date NOT NULL,
    end_date   date NOT NULL
);

CREATE TABLE calendar_dates_a (
    service_id   varchar NOT NULL,
    service_date date NOT NULL,
    added        boolean NOT NULL,
    PRIMARY KEY (service_id, service_date)
);

-- Slot b --------------------------------------------------------------

CREATE TABLE agencies_b (
    agency_id varchar PRIMARY KEY,
    name      varchar NOT NULL
);

CREATE TABLE routes_b (
    route_id   varchar PRIMARY KEY,
    agency_id  varchar,
    short_name varchar,
    long_name  varchar,
    route_type integer NOT NULL
);

-- Every stop, platform and station. station_id: the station a stop belongs
-- to (its parent_station, or the stop itself if it has none).
CREATE TABLE stops_b (
    stop_id     varchar PRIMARY KEY,
    station_id  varchar NOT NULL,
    name        varchar NOT NULL,
    search_name varchar NOT NULL,
    lat         double precision NOT NULL,
    lon         double precision NOT NULL,
    platform    varchar,
    is_station  boolean NOT NULL
);
CREATE INDEX stops_b_station ON stops_b (station_id);
CREATE INDEX stops_b_lat ON stops_b (lat);

CREATE TABLE trips_b (
    trip_id    varchar PRIMARY KEY,
    route_id   varchar NOT NULL,
    service_id varchar NOT NULL,
    headsign   varchar,
    short_name varchar
);

-- Last stop of every trip: its name is the fallback destination, and no
-- departure is shown there (the trip ends).
CREATE TABLE trip_ends_b (
    trip_id       varchar PRIMARY KEY,
    last_stop_id  varchar NOT NULL,
    last_sequence integer NOT NULL
);

-- Distinct stop_headsign values, so stop_times stores a number instead.
CREATE TABLE headsigns_b (
    headsign_id integer PRIMARY KEY,
    text        varchar NOT NULL
);

-- By far the largest table (tens of millions of rows for all of Germany).
-- departure_secs: seconds after the service day's start, may exceed 24h.
CREATE TABLE stop_times_b (
    trip_id        varchar NOT NULL,
    stop_id        varchar NOT NULL,
    stop_sequence  integer NOT NULL,
    departure_secs integer NOT NULL,
    headsign_id    integer,
    no_pickup      boolean NOT NULL
);
-- The importer drops and recreates this index around loading (much faster
-- than updating it row by row); keep the definition in FeedImporter in sync.
CREATE INDEX stop_times_b_stop_dep ON stop_times_b (stop_id, departure_secs);

-- days: bit 0 = Monday ... bit 6 = Sunday.
CREATE TABLE calendar_b (
    service_id varchar PRIMARY KEY,
    days       smallint NOT NULL,
    start_date date NOT NULL,
    end_date   date NOT NULL
);

CREATE TABLE calendar_dates_b (
    service_id   varchar NOT NULL,
    service_date date NOT NULL,
    added        boolean NOT NULL,
    PRIMARY KEY (service_id, service_date)
);

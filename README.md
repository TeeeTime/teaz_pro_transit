# teaz_pro_transit
Teaz Transit, at `https://transit.teaz.pro`: every user picks their bus stop
or train station and gets its live departure board.

Monorepo: `frontend/` (Vue 3 + TypeScript) and `backend/` (Spring Boot, `pro.teaz:transit`).

Stack, conventions, style guide and color tokens shared by every Teaz app
live in **`teaz_pro`**: start with `docs/TECH_STACK.md` there. This
README only covers what is specific to this app.

## How it works

| Part | Source | When |
| ---- | ------ | ---- |
| Timetable | GTFS feed **Germany Full** from gtfs.de (DELFI e.V., CC BY 4.0): local transport, regional and long-distance trains, `https://download.gtfs.de/germany/free/latest.zip` (~250 MB zipped) | On first start, then daily at 04:30 (`transit.gtfs.update-cron`). Only downloaded when the server reports a new version (ETag). |
| Delays, cancellations | GTFS-Realtime TripUpdates from gtfs.de (CC BY-SA 4.0, beta), `https://realtime.gtfs.de/realtime-free.pb` (~4.5 MB gzipped) | Every minute, but only while someone looks at a board (stops after 10 minutes without a request). |

- **Import** (`gtfs/FeedImporter.java`): streams the zip's files into the
  database, Postgres via `COPY`. The timetable exists twice, in slot `a` and
  slot `b` (tables `stop_times_a`, `stop_times_b`, …): an import fills the
  inactive slot while the active one keeps serving, then switches over
  (`feed_state`) and empties the old slot. A failed import changes nothing.
  Only what boards need is kept (no shapes, transfers, fares).
- **Boards** (`board/DepartureService.java`): departures of all platforms of
  a station within a time window, from yesterday's, today's and tomorrow's
  service day (GTFS times run past midnight, `25:10:00`), filtered to the
  services running that day. Trips ending at the station and drop-off-only
  stops are left out. Destination: the stop's headsign, else the trip's,
  else the trip's last stop.
- **Realtime** (`realtime/`): kept in memory per trip and service day;
  delays carry over to later stops without their own update, as the
  GTFS-Realtime spec defines. Without realtime (feed down, or older than 5
  minutes) boards show planned times and say so.
- **Stations**: a GTFS station (`location_type` 1) with its platforms, or a
  stop without a parent. Search ignores case and accents (`munster` finds
  `Münster`); "Stops near me" uses the browser's location. Each user picks
  one; the pick survives a new feed in which the station got another id
  (matched by name and position).

Measured with the full Germany feed (2026-10-18) on Postgres 18, on a
desktop: 34.4 million stop times, 597,000 stops, 1.73 million trips;
import about 2.5 minutes, 3.2 GB in the database (twice that while an
import runs, both slots full), boards in 30-300 ms, station search 50-150
ms. Platforms come from the feed's `platform_code`, which gtfs.de has for
many but not all stations (München Hbf: none), so `platform` is often null.

## API for other apps

`GET https://transit.teaz.pro/api/board?limit=5&minutes=60`, with the
caller's Keycloak access token (any Teaz app's token works, same realm; no
role needed). The logged-in user's station and its next departures:

```json
{
  "station": { "id": "540281", "name": "Münster Hauptbahnhof", "lat": 51.9567, "lon": 7.63444 },
  "generatedAt": "2026-10-19T08:00:12+02:00",
  "realtime": true,
  "departures": [
    {
      "tripId": "2380213",
      "line": "RE 7",
      "mode": "TRAIN",
      "destination": "Krefeld Hbf",
      "platform": "11",
      "plannedTime": "2026-10-19T08:04:00+02:00",
      "expectedTime": "2026-10-19T08:07:00+02:00",
      "delayMinutes": 3,
      "canceled": false,
      "agency": "DB Regio NRW"
    }
  ],
  "attribution": "Timetable: DELFI e.V. via GTFS.de (CC BY 4.0), Realtime: GTFS.de (CC BY-SA 4.0)"
}
```

| Field | Meaning |
| ----- | ------- |
| `station` | `null` if the user hasn't picked one yet (then `departures` is empty): link to `https://transit.teaz.pro` to pick one. |
| `realtime` | `false`: no realtime data right now, every time is a planned time. |
| `departures` | Soonest first, by expected time (planned time without realtime). Departures that already left are gone; canceled ones stay until their planned time. |
| `mode` | `TRAIN`, `SUBURBAN` (S-Bahn), `SUBWAY` (U-Bahn), `TRAM`, `BUS`, `FERRY`, `OTHER`. |
| `platform` | Platform or bay, `null` if the timetable has none. |
| `expectedTime`, `delayMinutes` | `null` when realtime knows nothing about this trip (not "on time"). |
| `canceled` | Trip canceled, or it skips this stop. |
| `attribution` | Must be shown with the departures (license terms of the data). |

Parameters: `limit` 1-50 (default 5), `minutes` 1-360 (default 60).
Errors are RFC 9457 problem details; `503` means the timetable is still
being imported (first start only), try again later. All times are ISO 8601
with the offset of `Europe/Berlin`.

CORS allows the origins in `transit.cors.allowed-origins`
(`application-prod.properties`, currently `https://portal.teaz.pro`), for
`/api/board` only. A Portal tile works like its Study tile
(`teaz_pro_portal/frontend/src/data/studyOverview.ts`): fetch with the
user's token, refresh every minute, e.g. one row per departure with line,
destination and time (`+3` in the critical color when delayed).

Fields are only ever added to this response, never renamed or removed.

### Other endpoints (used by the Transit UI)

| Endpoint | What |
| -------- | ---- |
| `GET /api/stations?query=…&limit=10` | Station search by name (at least 2 characters). |
| `GET /api/stations/nearby?lat=…&lon=…&limit=10` | Stations within 2 km, nearest first, with `distanceMeters`. |
| `GET /api/stations/{id}` | One station. |
| `GET /api/stations/{id}/departures?limit=20&minutes=90` | Board of any station (same shape as `/api/board`). |
| `GET /api/me/station` | `{ "station": … }`, `null` if none picked. |
| `PUT /api/me/station` | Body `{ "stationId": "…" }`: picks it. `404` for an unknown id. |
| `DELETE /api/me/station` | Forgets the pick. |
| `GET /api/feed` | Loaded timetable version, import status and last error, realtime freshness. |

## Running locally

```sh
cd backend && ./mvnw spring-boot:run
cd frontend && npm run dev
```

Or use the IntelliJ run configs in `.idea/runConfigurations/`. Locally:

- **No Docker, no database install:** the backend uses a file-based H2
  database in PostgreSQL mode (`backend/data/`, gitignored). Delete that
  folder for a fresh start.
- **Offline:** without `transit.gtfs.feed-url` the backend imports a small
  made-up feed (`backend/src/main/resources/sample-feed/`: "Musterstadt", bus
  11 every 10 minutes around the clock, RE 1 every 30 minutes until 00:30), and
  realtime stays off. Bump `SAMPLE_VERSION` in `FeedImporter` after changing
  the sample.
- **With the real feed:** set `TRANSIT_GTFS_FEED_URL` (a URL or a `file:`
  URL of a downloaded zip) and `TRANSIT_REALTIME_URL` in the backend's
  environment. For all of Germany use Postgres, not H2: H2 takes far
  longer for 34 million rows.
- Auth is mocked on both sides; there's one local user.
- Backend port 8080 is also the other apps' default: only one of them can
  run locally at a time.

## Shared UI

`TopBar`, `Icon`, `useTheme()` and the theme tokens come from
**`teaz_pro_ui`** (pinned in `frontend/package.json`), see
`teaz_pro/docs/UI_LIBRARY.md`.

## Auth

- Frontend `frontend/src/auth/`: Keycloak (`.env.production`: `auth.teaz.pro`,
  realm `teazpro`, client `transit`) in builds, mock user in `npm run dev`.
- Backend `backend/src/main/java/pro/teaz/transit/security/`, with the
  `prod` profile: every request needs a Keycloak Bearer JWT. **No role**:
  every Teaz user may use Transit. Each user's pick is keyed by the token's
  `sub`.

Keycloak client setup: see the Auth section in `teaz_pro/docs/TECH_STACK.md`.

## Database

Transit uses the shared Postgres server on the VPS (`/opt/teazpro/postgres`),
reached over the external Docker network `db`, with its **own database
`transit` and own user `transit`** (owner of that database, no superuser
rights). It never reads another app's database.

- Schema: Flyway migrations in `backend/src/main/resources/db/migration`.
  Exception: the importer drops and recreates the index on
  `stop_times_<slot>` around each load (building it once is much faster
  than updating it for every row); its definition in `FeedImporter` must
  match `V1__init.sql`.
- Connection only from the environment: `SPRING_DATASOURCE_URL`,
  `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` in `.env`.
- The only data that isn't re-downloaded is `user_station` (each user's
  pick); back up that table, the timetable comes back with the next import.

## Deployment

Once, on the Postgres server: create the user and database, exactly as in
`teaz_pro_study`'s README ("First deploy"), with `transit` instead of
`study`. Then `.env` next to `compose.yml` (gitignored):

```sh
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/transit
SPRING_DATASOURCE_USERNAME=transit
SPRING_DATASOURCE_PASSWORD=<the password given to the transit role>
```

Then, as for every app:

```sh
git pull && docker compose up -d --build
```

`compose.yml` routes `transit.teaz.pro` to the frontend (nginx) and
`transit.teaz.pro/api/*` to the backend, via the shared Traefik (network
`proxy`, cert resolver `le`). The backend also joins the external network
`db` for Postgres.

Needs about 7 GB free on the Postgres server's disk (3.2 GB timetable,
twice while importing) and 300 MB on the backend's (the zip in `/tmp`, removed
after the import). On the first start the backend downloads and imports the
feed in the background, a few minutes; until it's done the UI says so and `/api/board` answers `503`.
Progress is in the backend's log (`docker compose logs -f backend`) and at
`/api/feed`.

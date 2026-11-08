// Shapes of the backend's JSON responses (see the records in backend/.../board and station).

export interface Station {
  id: string
  name: string
  lat: number
  lon: number
}

export interface NearbyStation extends Station {
  distanceMeters: number
}

export type Mode = 'TRAIN' | 'SUBURBAN' | 'SUBWAY' | 'TRAM' | 'BUS' | 'FERRY' | 'OTHER'

export interface Departure {
  tripId: string
  line: string
  mode: Mode
  destination: string
  platform: string | null
  /** ISO 8601 with offset, e.g. 2026-10-19T08:01:00+02:00 */
  plannedTime: string
  /** Null without realtime data for this trip. */
  expectedTime: string | null
  delayMinutes: number | null
  canceled: boolean
  agency: string | null
  /** The trip's service day, e.g. 2026-10-19 (GTFS times may run past midnight): opens its Journey. */
  serviceDay: string
}

export interface Board {
  station: Station | null
  generatedAt: string
  /** False: every time is a planned time. */
  realtime: boolean
  departures: Departure[]
  attribution: string
}

export interface JourneyStop {
  stopId: string
  stationId: string
  name: string
  platform: string | null
  plannedArrival: string
  expectedArrival: string | null
  plannedDeparture: string
  expectedDeparture: string | null
  /** At the departure (last stop: arrival); null without realtime. */
  delayMinutes: number | null
  /** The vehicle doesn't stop here. */
  canceled: boolean
  /** The vehicle has already left. */
  passed: boolean
}

export type PositionStatus = 'NOT_STARTED' | 'AT_STOP' | 'BETWEEN' | 'ARRIVED' | 'CANCELED'

export interface Position {
  status: PositionStatus
  /** NOT_STARTED: first stop; AT_STOP: that stop; BETWEEN: the one it left; ARRIVED: last stop. */
  stopIndex: number | null
  /** BETWEEN: the stop it heads for. */
  nextStopIndex: number | null
  /** BETWEEN: 0..1 of the way to the next stop. */
  progress: number | null
}

/** A trip's course, from GET /api/trips/{tripId}. */
export interface Journey {
  tripId: string
  serviceDay: string
  line: string
  mode: Mode
  destination: string
  agency: string | null
  generatedAt: string
  /** Realtime data available at all. */
  realtime: boolean
  /** Realtime knows this trip; false: every time is a planned time. */
  tracked: boolean
  canceled: boolean
  stops: JourneyStop[]
  position: Position
  attribution: string
}

export interface MyStation {
  station: Station | null
}

export interface FeedInfo {
  ready: boolean
  source: string | null
  version: string | null
  importedAt: string | null
  importing: boolean
  lastCheck: string | null
  lastError: string | null
  realtime: boolean
  realtimeUpdatedAt: string | null
}

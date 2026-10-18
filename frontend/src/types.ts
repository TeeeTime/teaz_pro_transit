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
}

export interface Board {
  station: Station | null
  generatedAt: string
  /** False: every time is a planned time. */
  realtime: boolean
  departures: Departure[]
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

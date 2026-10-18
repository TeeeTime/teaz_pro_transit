import type { Departure, Mode } from '@/types'

const TIME = new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin' })

/** "08:01", in the timetable's time zone wherever the viewer is. */
export function clock(iso: string): string {
  return TIME.format(new Date(iso))
}

/** "now", "4 min", or null when it's more than an hour away (the clock time says enough). */
export function countdown(departure: Departure, now: number): string | null {
  const minutes = Math.floor((Date.parse(departure.expectedTime ?? departure.plannedTime) - now) / 60_000)
  if (minutes <= 0) return 'now'
  return minutes < 60 ? `${minutes} min` : null
}

export const MODE_LABELS: Record<Mode, string> = {
  TRAIN: 'Train',
  SUBURBAN: 'S-Bahn',
  SUBWAY: 'U-Bahn',
  TRAM: 'Tram',
  BUS: 'Bus',
  FERRY: 'Ferry',
  OTHER: 'Other',
}

export function distance(meters: number): string {
  return meters < 1000 ? `${Math.round(meters / 10) * 10} m` : `${(meters / 1000).toFixed(1)} km`
}

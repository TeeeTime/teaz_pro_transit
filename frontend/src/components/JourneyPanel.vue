<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, errorMessage } from '@/api'
import { clock, minutesUntil, MODE_LABELS } from '@/format'
import type { Departure, Journey, JourneyStop } from '@/types'

// Where the vehicle is changes by the minute; the backend's realtime once a minute.
const REFRESH_MS = 20_000

const props = defineProps<{
  departure: Departure
  /** The board it was opened from: highlighted in the course, and its realtime covers this trip. */
  stationId: string
  now: number
}>()

const emit = defineEmits<{ close: [] }>()

const journey = ref<Journey | null>(null)
const error = ref<string | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const course = ref<HTMLOListElement | null>(null)

let loadedFor = ''
let request = 0

async function load() {
  const { tripId, serviceDay } = props.departure
  const current = ++request
  const query = new URLSearchParams({ day: serviceDay, station: props.stationId })
  try {
    const result = await api.get<Journey>(`/api/trips/${encodeURIComponent(tripId)}?${query}`)
    if (current !== request) return // a newer request (other departure) is on its way
    journey.value = result
    error.value = null
    const key = `${tripId}@${serviceDay}`
    if (loadedFor !== key) {
      loadedFor = key
      await nextTick()
      scrollToVehicle()
    }
  } catch (e) {
    if (current === request) error.value = errorMessage(e)
  }
}

/** Brings the vehicle's stop into view, once per opened trip (later refreshes leave the scroll alone). */
function scrollToVehicle() {
  const target = course.value?.querySelector<HTMLElement>('[data-vehicle]')
  target?.scrollIntoView({ block: 'center' })
}

watch(
  () => `${props.departure.tripId}@${props.departure.serviceDay}`,
  () => {
    journey.value = null
    error.value = null
    load()
  },
)

const position = computed(() => journey.value?.position ?? null)

/** Whether the vehicle is at this stop (or waits at its first, or has arrived at its last). */
function vehicleAt(index: number): boolean {
  const p = position.value
  return !!p && p.status !== 'BETWEEN' && p.status !== 'CANCELED' && p.stopIndex === index
}

function movingFrom(index: number): boolean {
  const p = position.value
  return p?.status === 'BETWEEN' && p.stopIndex === index
}

/** The track below a stop's dot is done once the vehicle is past it (or on its way from it). */
function segmentDone(index: number): boolean {
  const p = position.value
  if (!p || p.stopIndex === null) return false
  if (p.status === 'ARRIVED') return true
  // BETWEEN: the segment it's on shows the marker instead.
  return (p.status === 'AT_STOP' || p.status === 'BETWEEN') && index < p.stopIndex
}

function stopName(index: number | null): string {
  return index === null ? '' : (journey.value?.stops[index]?.name ?? '')
}

// Named apart from the departure prop, which the template uses.
function arrives(stop: JourneyStop): string {
  return stop.expectedArrival ?? stop.plannedArrival
}

function departs(stop: JourneyStop): string {
  return stop.expectedDeparture ?? stop.plannedDeparture
}

/** One line on where the vehicle is, e.g. "Between Markt and Uni · there in 2 min". */
const summary = computed(() => {
  const j = journey.value
  const p = position.value
  if (!j || !p) return ''
  if (j.canceled || p.status === 'CANCELED') return 'This trip is canceled.'
  switch (p.status) {
    case 'NOT_STARTED': {
      const first = j.stops[p.stopIndex!]!
      return `Not on its way yet · departs ${first.name} at ${clock(departs(first))}`
    }
    case 'AT_STOP': {
      const stop = j.stops[p.stopIndex!]!
      const last = p.stopIndex === j.stops.length - 1
      return last ? `Arriving at ${stop.name}` : `At ${stop.name} · departs ${clock(departs(stop))}`
    }
    case 'BETWEEN': {
      const next = j.stops[p.nextStopIndex!]!
      const minutes = minutesUntil(arrives(next), props.now)
      const due = minutes === 0 ? 'arriving now' : `there in ${minutes} min`
      return `Between ${stopName(p.stopIndex)} and ${next.name} · ${due}`
    }
    case 'ARRIVED':
      return `Arrived at ${stopName(p.stopIndex)}`
  }
  return ''
})

/** Show arrival and departure separately only where they differ (the vehicle waits there). */
function waits(stop: JourneyStop): boolean {
  return stop.plannedArrival !== stop.plannedDeparture
}

function onKey(event: KeyboardEvent) {
  if (event.key === 'Escape') emit('close')
}

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  load()
  timer = setInterval(load, REFRESH_MS)
  document.addEventListener('keydown', onKey)
  closeButton.value?.focus()
})
onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div class="journey-backdrop" @click="emit('close')" />
  <aside class="journey" role="dialog" aria-modal="true" aria-labelledby="journey-title">
    <header class="journey__head">
      <div class="journey__heading">
        <span class="journey__line">{{ departure.line }}</span>
        <div class="journey__titles">
          <h2 id="journey-title" class="journey__title">{{ journey?.destination || departure.destination }}</h2>
          <p class="journey__meta">
            {{ MODE_LABELS[departure.mode] }}
            <template v-if="departure.agency"> · {{ departure.agency }}</template>
          </p>
        </div>
      </div>
      <button ref="closeButton" type="button" class="button" @click="emit('close')">Close</button>
    </header>

    <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>

    <template v-if="journey">
      <div class="journey__position" :class="{ 'journey__position--alert': journey.canceled }" role="status">
        <span class="label">{{ journey.tracked ? 'Live position' : 'Per timetable' }}</span>
        <span class="journey__summary">{{ summary }}</span>
        <span class="journey__updated">
          <template v-if="journey.tracked">Realtime</template>
          <template v-else-if="journey.realtime">No realtime for this trip, planned times</template>
          <template v-else>Planned times only</template>
          · updated {{ clock(journey.generatedAt) }}
        </span>
      </div>

      <ol ref="course" class="course">
        <li
          v-for="(stop, index) in journey.stops"
          :key="`${stop.stopId}-${index}`"
          class="stop"
          :class="{
            'stop--passed': stop.passed,
            'stop--canceled': stop.canceled,
            'stop--here': stop.stationId === stationId,
            'stop--vehicle': vehicleAt(index),
          }"
          :data-vehicle="vehicleAt(index) || movingFrom(index) ? '' : undefined"
        >
          <span class="stop__track" aria-hidden="true">
            <span
              v-if="index > 0"
              class="stop__rail stop__rail--up"
              :class="{ 'stop__rail--done': segmentDone(index - 1) }"
            />
            <span
              v-if="index < journey.stops.length - 1"
              class="stop__rail stop__rail--down"
              :class="{ 'stop__rail--done': segmentDone(index) }"
            />
            <span class="stop__dot" />
            <span
              v-if="movingFrom(index)"
              class="stop__vehicle"
              :style="{ top: `calc(var(--dot-y) + ${(position?.progress ?? 0) * 100}%)` }"
            />
          </span>

          <span class="stop__times">
            <template v-if="index === 0 || index === journey.stops.length - 1 || !waits(stop)">
              <span class="stop__time">
                {{ clock(index === journey.stops.length - 1 ? stop.plannedArrival : stop.plannedDeparture) }}
              </span>
            </template>
            <template v-else>
              <span class="stop__time stop__time--minor">{{ clock(stop.plannedArrival) }}</span>
              <span class="stop__time">{{ clock(stop.plannedDeparture) }}</span>
            </template>
            <span v-if="!stop.canceled && stop.delayMinutes !== null && stop.delayMinutes > 0" class="stop__delay">
              +{{ stop.delayMinutes }}
            </span>
          </span>

          <span class="stop__main">
            <span class="stop__name">{{ stop.name }}</span>
            <span class="stop__meta">
              <span v-if="stop.canceled" class="stop__alert">Stop canceled</span>
              <template v-else-if="stop.platform">Platform {{ stop.platform }}</template>
              <span v-if="stop.stationId === stationId" class="stop__tag">Your board</span>
            </span>
            <span v-if="vehicleAt(index)" class="visually-hidden">Vehicle is here</span>
            <span v-if="movingFrom(index)" class="visually-hidden">Vehicle has left, on its way to the next stop</span>
          </span>
        </li>
      </ol>

      <p class="journey__attribution">{{ journey.attribution }}</p>
    </template>
    <p v-else-if="!error" class="journey__loading">Loading journey…</p>
  </aside>
</template>

<style scoped>
.journey-backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
  background-color: var(--color-shadow-strong);
}

.journey {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 100;
  display: flex;
  flex-direction: column;
  width: min(460px, 100vw);
  padding: 20px 20px 32px;
  overflow-y: auto;
  background-color: var(--color-bg);
  border-left: 1px solid var(--color-border);
  box-shadow: -8px 0 24px var(--color-shadow);
}

.journey__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.journey__heading {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.journey__line {
  flex: none;
  padding: 2px 8px;
  font-size: 0.85rem;
  font-weight: 700;
  white-space: nowrap;
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.journey__titles {
  min-width: 0;
}

.journey__title {
  font-size: 1.15rem;
  font-weight: 700;
  overflow-wrap: anywhere;
  color: var(--color-text-primary);
}

.journey__meta {
  margin-top: 2px;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
}

.journey__position {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 16px;
  padding: 12px 14px;
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-left: 3px solid var(--color-accent);
}

.journey__position--alert {
  border-left-color: var(--color-status-critical);
}

.journey__summary {
  font-weight: 600;
  color: var(--color-text-primary);
}

.journey__updated {
  font-size: 0.78rem;
  color: var(--color-text-secondary);
}

/* The course: a vertical track on the left, one row per stop. */
.course {
  --dot-y: 18px;
  padding: 0;
  list-style: none;
}

.stop {
  position: relative;
  display: grid;
  grid-template-columns: 24px 3.5rem minmax(0, 1fr);
  gap: 10px;
  min-height: 52px;
  padding: 8px 0;
}

.stop__track {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 24px;
}

.stop__rail {
  position: absolute;
  left: 11px;
  width: 2px;
  background-color: var(--color-border);
}

.stop__rail--up {
  top: 0;
  height: var(--dot-y);
}

.stop__rail--down {
  top: var(--dot-y);
  bottom: 0;
}

.stop__rail--done {
  background-color: var(--color-accent);
}

.stop__dot {
  position: absolute;
  top: calc(var(--dot-y) - 6px);
  left: 6px;
  width: 12px;
  height: 12px;
  background-color: var(--color-bg);
  border: 2px solid var(--color-text-secondary);
  border-radius: 50%;
}

.stop--passed .stop__dot {
  background-color: var(--color-accent);
  border-color: var(--color-accent);
}

.stop--canceled .stop__dot {
  border-style: dashed;
}

/* The vehicle: a larger accent marker with a ring, on its stop or between two. */
.stop--vehicle .stop__dot,
.stop__vehicle {
  width: 18px;
  height: 18px;
  background-color: var(--color-accent);
  border: 3px solid var(--color-bg);
  border-radius: 50%;
  box-shadow: 0 0 0 2px var(--color-accent);
}

.stop--vehicle .stop__dot {
  top: calc(var(--dot-y) - 9px);
  left: 3px;
}

.stop__vehicle {
  position: absolute;
  left: 3px;
  z-index: 1;
  margin-top: -9px;
  transition: top var(--transition-medium);
}

@media (prefers-reduced-motion: no-preference) {
  .stop--vehicle .stop__dot,
  .stop__vehicle {
    animation: vehicle-pulse 2s ease-out infinite;
  }
}

@keyframes vehicle-pulse {
  0% {
    box-shadow: 0 0 0 2px var(--color-accent);
  }
  70% {
    box-shadow:
      0 0 0 2px var(--color-accent),
      0 0 0 8px transparent;
  }
  100% {
    box-shadow: 0 0 0 2px var(--color-accent);
  }
}

.stop__times {
  grid-column: 2;
  display: flex;
  flex-direction: column;
  font-variant-numeric: tabular-nums;
}

.stop__time {
  font-weight: 600;
  color: var(--color-text-primary);
}

.stop__time--minor {
  font-size: 0.8rem;
  font-weight: 400;
  color: var(--color-text-secondary);
}

/* Delay and cancellation always come as text, never color alone (see COLOR_SCHEMES.md). */
.stop__delay,
.stop__alert {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--color-status-critical);
}

.stop__main {
  grid-column: 3;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stop__name {
  overflow-wrap: anywhere;
  color: var(--color-text-primary);
}

.stop__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 0.78rem;
  color: var(--color-text-secondary);
}

.stop__tag {
  padding: 0 6px;
  font-weight: 600;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.stop--here .stop__name,
.stop--vehicle .stop__name {
  font-weight: 700;
}

.stop--passed .stop__time,
.stop--passed .stop__name {
  color: var(--color-text-secondary);
}

.stop--canceled .stop__time,
.stop--canceled .stop__name {
  text-decoration: line-through;
  color: var(--color-text-secondary);
}

.journey__loading {
  color: var(--color-text-secondary);
}

.journey__attribution {
  margin-top: 16px;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

@media (max-width: 420px) {
  .journey {
    padding: 16px 12px 24px;
  }
}
</style>

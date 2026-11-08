<script setup lang="ts">
import { clock, countdown, departureKey, MODE_LABELS } from '@/format'
import type { Departure } from '@/types'

defineProps<{
  departures: Departure[]
  /** Date.now() of the last tick, so countdowns move between refreshes. */
  now: number
  /** key() of the departure whose journey is open, if any. */
  selected?: string | null
}>()

const emit = defineEmits<{ select: [departure: Departure, row: HTMLElement] }>()

function key(departure: Departure): string {
  return departureKey(departure)
}
</script>

<template>
  <ol class="departures">
    <li v-for="departure in departures" :key="key(departure)">
      <button
        type="button"
        class="departure"
        :class="{ 'departure--canceled': departure.canceled, 'departure--selected': selected === key(departure) }"
        :aria-label="`${departure.line} to ${departure.destination}, ${clock(departure.plannedTime)}: show its journey`"
        @click="emit('select', departure, $event.currentTarget as HTMLElement)"
      >
      <span class="departure__time">
        <span class="departure__planned">{{ clock(departure.plannedTime) }}</span>
        <span v-if="departure.canceled" class="departure__alert">Canceled</span>
        <span v-else-if="departure.delayMinutes !== null && departure.delayMinutes > 0" class="departure__alert">
          +{{ departure.delayMinutes }}
        </span>
      </span>

      <span class="departure__line" :title="MODE_LABELS[departure.mode]">{{ departure.line }}</span>

      <span class="departure__main">
        <span class="departure__destination">{{ departure.destination }}</span>
        <span class="departure__meta">
          {{ MODE_LABELS[departure.mode] }}
          <template v-if="departure.platform"> · Platform {{ departure.platform }}</template>
        </span>
      </span>

      <span v-if="!departure.canceled" class="departure__countdown">{{ countdown(departure, now) }}</span>
      </button>
    </li>
  </ol>
</template>

<style scoped>
.departures {
  padding: 0;
  list-style: none;
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
}

.departures > li {
  border-bottom: 1px solid var(--color-border);
}

.departures > li:last-child {
  border-bottom: none;
}

.departure {
  display: grid;
  grid-template-columns: 3.75rem 4.5rem minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 16px;
  font: inherit;
  text-align: left;
  color: inherit;
  background: none;
  border: none;
  border-left: 3px solid transparent;
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.departure:hover {
  background-color: var(--color-surface-hover);
}

.departure:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}

.departure--selected {
  background-color: var(--color-surface-active);
  border-left-color: var(--color-accent);
}

.departure__time {
  display: flex;
  flex-direction: column;
  font-variant-numeric: tabular-nums;
}

.departure__planned {
  font-weight: 600;
  color: var(--color-text-primary);
}

/* Delay and cancellation always come as text, never color alone (see COLOR_SCHEMES.md). */
.departure__alert {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--color-status-critical);
}

.departure__line {
  justify-self: start;
  max-width: 100%;
  padding: 2px 8px;
  overflow: hidden;
  font-size: 0.85rem;
  font-weight: 700;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.departure__main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.departure__destination {
  overflow: hidden;
  font-weight: 500;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--color-text-primary);
}

.departure__meta {
  font-size: 0.78rem;
  color: var(--color-text-secondary);
}

.departure__countdown {
  font-size: 0.85rem;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  color: var(--color-text-secondary);
}

.departure--canceled .departure__planned,
.departure--canceled .departure__destination {
  text-decoration: line-through;
  color: var(--color-text-secondary);
}

@media (max-width: 420px) {
  .departure {
    grid-template-columns: 3.25rem 3.75rem minmax(0, 1fr) auto;
    gap: 8px;
    padding: 10px 12px;
  }
}
</style>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { Icon } from '@teeetime/teaz-pro-ui'
import { api, errorMessage } from '@/api'
import { distance } from '@/format'
import type { NearbyStation, Station } from '@/types'

defineProps<{ cancelable?: boolean }>()
const emit = defineEmits<{ select: [station: Station]; cancel: [] }>()

const query = ref('')
const results = ref<(Station | NearbyStation)[]>([])
const searched = ref(false)
const loading = ref(false)
const error = ref<string | null>(null)

// Each keystroke restarts the timer, so only a pause in typing sends a search.
const DEBOUNCE_MS = 250
let timer: ReturnType<typeof setTimeout> | undefined
// Answers can arrive out of order; only the latest request may set the results.
let latest = 0

watch(query, (value) => {
  clearTimeout(timer)
  if (value.trim().length < 2) {
    results.value = []
    searched.value = false
    return
  }
  timer = setTimeout(() => search(value.trim()), DEBOUNCE_MS)
})
onBeforeUnmount(() => clearTimeout(timer))

async function run(load: () => Promise<(Station | NearbyStation)[]>) {
  const request = ++latest
  loading.value = true
  error.value = null
  try {
    const found = await load()
    if (request !== latest) return
    results.value = found
    searched.value = true
  } catch (e) {
    if (request === latest) error.value = errorMessage(e)
  } finally {
    if (request === latest) loading.value = false
  }
}

function search(text: string) {
  run(() => api.get<Station[]>(`/api/stations?query=${encodeURIComponent(text)}&limit=10`))
}

function nearMe() {
  if (!navigator.geolocation) {
    error.value = 'This browser cannot tell your location.'
    return
  }
  loading.value = true
  error.value = null
  navigator.geolocation.getCurrentPosition(
    (position) => {
      query.value = ''
      const { latitude, longitude } = position.coords
      run(() => api.get<NearbyStation[]>(`/api/stations/nearby?lat=${latitude}&lon=${longitude}&limit=10`))
    },
    () => {
      loading.value = false
      error.value = 'Location not available. Allow it in the browser, or search by name.'
    },
    { timeout: 10_000, maximumAge: 60_000 },
  )
}

function isNearby(station: Station | NearbyStation): station is NearbyStation {
  return 'distanceMeters' in station
}
</script>

<template>
  <section class="picker">
    <div class="picker__head">
      <h1 class="view__title">Pick your stop</h1>
      <button v-if="cancelable" type="button" class="button" @click="emit('cancel')">Cancel</button>
    </div>
    <p class="picker__hint">Bus stop, tram stop or train station. Its next departures will show here.</p>

    <div class="picker__controls">
      <label class="picker__field">
        <span class="label">Search</span>
        <span class="picker__input-wrap">
          <Icon name="search" class="picker__icon" />
          <input
            v-model="query"
            class="picker__input"
            type="search"
            placeholder="e.g. Münster Hbf"
            autocomplete="off"
            maxlength="100"
          />
        </span>
      </label>
      <button type="button" class="button" @click="nearMe">Stops near me</button>
    </div>

    <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>
    <p v-else-if="loading && !results.length" class="picker__status">Searching…</p>
    <p v-else-if="searched && !results.length" class="picker__status">No stops found.</p>

    <ul v-if="results.length" class="results">
      <li v-for="station in results" :key="station.id">
        <button type="button" class="result" @click="emit('select', station)">
          <span class="result__name">{{ station.name }}</span>
          <span v-if="isNearby(station)" class="result__meta">{{ distance(station.distanceMeters) }}</span>
        </button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.picker__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.picker__hint {
  margin-top: 8px;
  color: var(--color-text-secondary);
}

.picker__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 8px;
  margin: 24px 0 16px;
}

.picker__field {
  display: flex;
  flex: 1 1 260px;
  flex-direction: column;
  gap: 4px;
}

.picker__input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.picker__icon {
  position: absolute;
  left: 10px;
  width: 16px;
  height: 16px;
  color: var(--color-text-secondary);
  pointer-events: none;
}

.picker__input {
  width: 100%;
  padding: 8px 10px 8px 34px;
  font: inherit;
  font-size: 0.9rem;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}

.picker__input:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: 1px;
}

.picker__status {
  color: var(--color-text-secondary);
}

.results {
  padding: 0;
  list-style: none;
  border: 1px solid var(--color-border);
  border-bottom: none;
}

.result {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 12px 16px;
  font: inherit;
  text-align: left;
  color: var(--color-text-primary);
  background-color: var(--color-surface);
  border: none;
  border-bottom: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color var(--transition-fast);
}

.result:hover {
  background-color: var(--color-surface-hover);
}

.result:focus-visible {
  outline: 2px solid var(--color-accent);
  outline-offset: -2px;
}

.result__name {
  font-weight: 500;
}

.result__meta {
  flex-shrink: 0;
  font-size: 0.8rem;
  color: var(--color-text-secondary);
}
</style>

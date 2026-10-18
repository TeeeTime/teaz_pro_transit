<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import DepartureList from '@/components/DepartureList.vue'
import StationPicker from '@/components/StationPicker.vue'
import { api, ApiError, errorMessage } from '@/api'
import { clock } from '@/format'
import type { Board, MyStation, Station } from '@/types'

// The backend fetches realtime at most once a minute; twice as often keeps the board current.
const REFRESH_MS = 30_000
const TICK_MS = 15_000

const station = ref<Station | null>(null)
const loaded = ref(false)
const picking = ref(false)
const board = ref<Board | null>(null)
const error = ref<string | null>(null)
// "The timetable is being imported": not an error, just wait.
const notReady = ref<string | null>(null)
const now = ref(Date.now())

async function loadStation() {
  try {
    station.value = (await api.get<MyStation>('/api/me/station')).station
    notReady.value = null
  } catch (e) {
    handle(e)
  } finally {
    loaded.value = true
  }
}

async function loadBoard() {
  if (!station.value) {
    if (notReady.value) await loadStation()
    return
  }
  try {
    board.value = await api.get<Board>(`/api/stations/${encodeURIComponent(station.value.id)}/departures?limit=20&minutes=90`)
    error.value = null
    notReady.value = null
  } catch (e) {
    handle(e)
  }
}

function handle(e: unknown) {
  if (e instanceof ApiError && e.status === 503) notReady.value = e.message
  else error.value = errorMessage(e)
}

async function choose(chosen: Station) {
  error.value = null
  try {
    station.value = (await api.put<MyStation>('/api/me/station', { stationId: chosen.id })).station
    picking.value = false
    board.value = null
    await loadBoard()
  } catch (e) {
    handle(e)
  }
}

const showPicker = computed(() => loaded.value && !notReady.value && (picking.value || !station.value))

let refreshTimer: ReturnType<typeof setInterval> | undefined
let tickTimer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  await loadStation()
  await loadBoard()
  refreshTimer = setInterval(loadBoard, REFRESH_MS)
  tickTimer = setInterval(() => (now.value = Date.now()), TICK_MS)
})
onBeforeUnmount(() => {
  clearInterval(refreshTimer)
  clearInterval(tickTimer)
})
</script>

<template>
  <div class="view">
    <p v-if="notReady" class="notice notice--working" role="status">{{ notReady }}</p>
    <p v-if="error" class="notice notice--error" role="alert">{{ error }}</p>

    <StationPicker v-if="showPicker" :cancelable="!!station" @select="choose" @cancel="picking = false" />

    <section v-else-if="station">
      <header class="board__head">
        <div>
          <h1 class="view__title">{{ station.name }}</h1>
          <p v-if="board" class="board__status">
            <template v-if="board.realtime">Live</template>
            <template v-else>Planned times only</template>
            · updated {{ clock(board.generatedAt) }}
          </p>
        </div>
        <button type="button" class="button" @click="picking = true">Change stop</button>
      </header>

      <template v-if="board">
        <DepartureList v-if="board.departures.length" :departures="board.departures" :now="now" />
        <p v-else class="board__empty">No departures in the next 90 minutes.</p>
        <p class="board__attribution">{{ board.attribution }}</p>
      </template>
      <p v-else-if="!error && !notReady" class="board__empty">Loading departures…</p>
    </section>
  </div>
</template>

<style scoped>
.board__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 24px;
}

.board__status {
  margin-top: 4px;
  font-size: 0.85rem;
  color: var(--color-text-secondary);
}

.board__empty {
  color: var(--color-text-secondary);
}

.board__attribution {
  margin-top: 16px;
  font-size: 0.75rem;
  color: var(--color-text-secondary);
}
</style>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RefreshCw, Trophy } from 'lucide-vue-next'
import { getTotalLeaderboard } from '../services/api'
import { getStoredParticipantId } from '../services/storage'

const props = defineProps({
  open: { type: Boolean, default: false },
  eventId: { type: String, required: true },
  eventName: { type: String, default: '' },
  participantId: { type: String, default: '' },
})
const emit = defineEmits(['update:open'])

const entries = ref([])
const generatedAt = ref(null)
const loading = ref(false)
const refreshing = ref(false)
const error = ref('')
let refreshTimer = 0
let requestSequence = 0

const visible = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})
const selfId = computed(() => props.participantId || getStoredParticipantId())

function formatClock(value) {
  if (!value) return '--:--:--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--:--:--'
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

function formatDuration(value) {
  const total = Math.max(0, Number(value) || 0)
  const minutes = Math.floor(total / 60000)
  const seconds = ((total % 60000) / 1000).toFixed(1)
  return `${minutes}:${seconds.padStart(4, '0')}`
}

function rankClass(rank) {
  if (rank === 1) return 'is-first'
  if (rank === 2) return 'is-second'
  if (rank === 3) return 'is-third'
  return ''
}

function isSelf(entry) {
  return Boolean(selfId.value) && entry.participantId === selfId.value
}

async function load({ silent = false } = {}) {
  const sequence = ++requestSequence
  if (silent) refreshing.value = true
  else loading.value = true
  error.value = ''
  try {
    const data = await getTotalLeaderboard(props.eventId)
    if (sequence !== requestSequence) return
    entries.value = data?.entries || []
    generatedAt.value = data?.generatedAt || null
  } catch (err) {
    if (sequence !== requestSequence) return
    error.value = err.response?.data?.message || '榜单暂时无法加载，请稍后重试'
  } finally {
    if (sequence === requestSequence) {
      loading.value = false
      refreshing.value = false
    }
  }
}

function stopTimer() {
  if (!refreshTimer) return
  window.clearInterval(refreshTimer)
  refreshTimer = 0
}

function startTimer() {
  stopTimer()
  refreshTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') load({ silent: true })
  }, 20000)
}

watch([() => props.open, () => props.eventId], ([open], [previousOpen, previousEventId]) => {
  if (previousEventId !== props.eventId) {
    entries.value = []
    generatedAt.value = null
    error.value = ''
  }
  stopTimer()
  if (!open) {
    if (previousOpen) requestSequence += 1
    return
  }
  load()
  startTimer()
}, { immediate: true })

onBeforeUnmount(() => {
  stopTimer()
  requestSequence += 1
})
</script>

<template>
  <a-modal
    v-model:open="visible"
    :footer="null"
    :centered="true"
    :width="'min(94vw, 540px)'"
    title="总榜"
    class="total-board-modal"
    wrap-class-name="total-board-modal-wrap"
  >
    <div class="total-board">
      <header class="total-board-head">
        <div class="total-board-kicker"><Trophy :size="14" /> 全部成绩</div>
        <div class="total-board-title-row">
          <h3>总榜</h3>
          <a-button type="text" size="small" class="total-board-refresh" :loading="refreshing" @click="load({ silent: true })">
            <RefreshCw :size="15" /> 刷新
          </a-button>
        </div>
        <div class="total-board-meta">{{ eventName || eventId }} · 更新于 {{ formatClock(generatedAt) }}</div>
        <p class="total-board-rule">线下各轮最佳成绩 + 线上锁定成绩，按总分排名</p>
      </header>

      <div class="total-board-body">
        <template v-if="loading">
          <div v-for="index in 5" :key="index" class="total-board-skeleton">
            <span class="skeleton-rank"></span>
            <span class="skeleton-lines"><i></i><i></i></span>
            <span class="skeleton-score"></span>
          </div>
        </template>

        <template v-else-if="error && !entries.length">
          <div class="total-board-state">
            <span class="total-board-state-icon"><Trophy :size="22" /></span>
            <p>{{ error }}</p>
            <a-button size="small" @click="load()">重新加载</a-button>
          </div>
        </template>

        <template v-else-if="!entries.length">
          <div class="total-board-state">
            <span class="total-board-state-icon"><Trophy :size="22" /></span>
            <p>暂无总榜成绩</p>
            <small>完成线上锁定或等待线下结算后，成绩会出现在这里。</small>
          </div>
        </template>

        <template v-else>
          <a-alert v-if="error" class="total-board-error" type="warning" :message="error" show-icon>
            <template #action><a-button size="small" type="link" @click="load({ silent: true })">重试</a-button></template>
          </a-alert>
          <div
            v-for="(entry, index) in entries"
            :key="entry.participantId || index"
            class="total-board-row"
            :class="{ 'is-self': isSelf(entry) }"
          >
            <div class="total-board-rank" :class="rankClass(entry.rank)">{{ entry.rank }}</div>
            <div class="total-board-person">
              <div class="total-board-name">
                {{ entry.nickname }}
                <span v-if="isSelf(entry)" class="total-board-self">我</span>
              </div>
              <div class="total-board-detail">线下 {{ entry.offlineScore }} 分 · 线上 {{ entry.onlineLocked ? `${entry.onlineScore} 分` : '未锁定' }} · {{ entry.offlineRoundCount }} 轮</div>
              <div class="total-board-detail">累计耗时 {{ formatDuration(entry.totalDurationMs) }}</div>
            </div>
            <div class="total-board-score"><strong>{{ entry.totalScore }}</strong><span>分</span></div>
          </div>
        </template>
      </div>

      <footer class="total-board-foot">榜单每 20 秒自动刷新 · 名次由服务端结算</footer>
    </div>
  </a-modal>
</template>

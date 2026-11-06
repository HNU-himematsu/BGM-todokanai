<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, ChevronRight, Copy, Eye, KeyRound, ListRestart, LockKeyhole, Pause, Play, Radio, RefreshCw, UsersRound } from 'lucide-vue-next'
import { message, Modal } from 'ant-design-vue'
import QRCode from 'qrcode'
import { useRoute } from 'vue-router'
import {
  getAudioUrl,
  getHostRounds,
  getHostState,
  getLeaderboard,
  getOnlineLeaderboard,
  getTotalLeaderboard,
  hostAction,
  hostLogin,
  hostLogout,
  selectHostRound,
} from '../services/api'
import { createActivitySocket } from '../services/socket'
import { clearHostToken, getHostToken, getStoredEventId, saveHostToken } from '../services/storage'

const route = useRoute()
const eventId = String(route.query.event || getStoredEventId() || 'demo')
const hostToken = ref(getHostToken())
const authenticated = ref(Boolean(hostToken.value))
const password = ref('')
const state = ref(null)
const canAdvanceQuestion = ref(false)
const canFinishRound = ref(false)
const players = ref([])
const rounds = ref([])
const currentRoundId = ref(null)
const nextRoundId = ref(null)
const timelineRunning = ref(false)
const timelineStarted = ref(false)
const roundEnded = ref(false)
const endedRoundId = ref(null)
const error = ref('')
const busy = ref(false)
const socket = ref(null)
const qrCanvas = ref(null)
const audio = ref(null)
const audioPosition = ref(0)
const volume = ref(1)
const roundLeaderboard = ref([])
const onlineLeaderboard = ref([])
const totalLeaderboard = ref([])
const activeLeaderboardTab = ref('round')
const leaderboardLoading = ref(false)
let lastAppliedRevision = 0
let refreshTimer = 0
let refreshPromise = null
let refreshQueued = false
let leaderboardTimer = 0
let leaderboardPromise = null
let leaderboardQueued = false
let lastPositionUpdate = 0

const question = computed(() => state.value?.question)
const round = computed(() => rounds.value.find((item) => item.id === currentRoundId.value))
const publicBaseUrl = (import.meta.env.VITE_PUBLIC_BASE_URL || window.location.origin).replace(/\/+$/, '')
const joinUrl = `${publicBaseUrl}/offline?event=${eventId}`
const summaryTxtUrl = `${publicBaseUrl}/api/events/${eventId}/leaderboard/summary-txt`
const displayQuestionNo = computed(() => Math.max(0, (state.value?.currentQuestionIndex ?? -1) + 1))
const statusLabel = computed(() => ({ WAITING: '等待开始', ANSWERING: '答题中', ANSWER_LOCKED: '已锁卷', ANSWER_REVEALED: '答案揭晓', FINISHED: '活动结束' }[state.value?.status] || '连接中'))
const statusColor = computed(() => ({ WAITING: 'default', ANSWERING: 'processing', ANSWER_LOCKED: 'warning', ANSWER_REVEALED: 'warning', FINISHED: 'success' }[state.value?.status] || 'default'))
const playbackLabel = computed(() => roundEnded.value ? '已结束' : timelineRunning.value ? '运行中' : timelineStarted.value ? '已暂停' : '待启动')
const canSwitchRound = computed(() => !timelineRunning.value)
const canStart = computed(() => state.value?.status === 'WAITING' && !timelineRunning.value && !timelineStarted.value)
const audioProgress = computed(() => {
  if (!round.value) return 0
  const duration = round.value.endMs - round.value.startMs
  return duration > 0 ? Math.min(100, Math.max(0, (audioPosition.value * 1000 - round.value.startMs) / duration * 100)) : 0
})

onMounted(async () => {
  if (authenticated.value) await openConsole()
})
onBeforeUnmount(() => {
  socket.value?.deactivate()
  audio.value?.pause()
  window.clearTimeout(refreshTimer)
  window.clearTimeout(leaderboardTimer)
})

function noteRevision(value) {
  const revision = Number(value)
  if (Number.isFinite(revision) && revision > lastAppliedRevision) lastAppliedRevision = revision
}

function isStaleRevision(value) {
  const revision = Number(value)
  return Number.isFinite(revision) && revision < lastAppliedRevision
}

function roundById(roundId) {
  return rounds.value.find((item) => item.id === roundId) || null
}

function nextRoundAfter(roundId) {
  const index = rounds.value.findIndex((item) => item.id === roundId)
  if (index < 0 || rounds.value.length === 0) return roundId ?? null
  return rounds.value[(index + 1) % rounds.value.length].id
}

function markRoundEnded(roundId = currentRoundId.value) {
  const endedId = roundId ?? null
  roundEnded.value = true
  if (endedId === endedRoundId.value && nextRoundId.value) return
  endedRoundId.value = endedId
  nextRoundId.value = rounds.value.some((item) => item.id === endedId) ? nextRoundAfter(endedId) : null
}

function clearRoundEnded() {
  roundEnded.value = false
  endedRoundId.value = null
}

function updateAudioPosition(seconds) {
  const now = performance.now()
  if (now - lastPositionUpdate < 250) return
  lastPositionUpdate = now
  audioPosition.value = seconds
}

function handleTimeUpdate(event) {
  updateAudioPosition(event.target.currentTime)
}

function handleSeeked(event) {
  lastPositionUpdate = 0
  updateAudioPosition(event.target.currentTime)
}

function seekTo(ms) {
  if (audio.value && Number.isFinite(ms)) audio.value.currentTime = ms / 1000
  audioPosition.value = ms / 1000
}

function waitForAudioEvent(eventName, timeoutMs = 2000) {
  return new Promise((resolve) => {
    const element = audio.value
    if (!element) {
      resolve(false)
      return
    }
    let settled = false
    let timeout = 0
    const finish = (value) => {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      element.removeEventListener(eventName, handler)
      element.removeEventListener('error', onError)
      resolve(value)
    }
    const handler = () => finish(true)
    const onError = () => finish(false)
    timeout = window.setTimeout(() => finish(false), timeoutMs)
    element.addEventListener(eventName, handler, { once: true })
    element.addEventListener('error', onError, { once: true })
  })
}

async function seekToReady(positionMs) {
  const element = audio.value
  if (!element || !Number.isFinite(positionMs)) return false
  const targetMs = Math.max(0, positionMs)
  if (Math.abs(element.currentTime * 1000 - targetMs) >= 50) {
    const seeked = waitForAudioEvent('seeked')
    seekTo(targetMs)
    if (!await seeked) return false
  } else {
    audioPosition.value = targetMs / 1000
  }
  return element.readyState >= 2 || waitForAudioEvent('canplay')
}

function syncAudio(eventType, eventRoundId = currentRoundId.value) {
  if (!audio.value) return
  if (eventRoundId && currentRoundId.value && eventRoundId !== currentRoundId.value) return
  const targetRound = roundById(eventRoundId) || (eventRoundId ? null : round.value)
  if (!targetRound) return
  if (eventType === 'ACTIVITY_RESET') {
    audio.value.pause()
    seekTo(targetRound.startMs)
  } else if (eventType === 'ROUND_ENDED' || eventType === 'ACTIVITY_FINISHED') {
    audio.value.pause()
    seekTo(targetRound.endMs)
  } else if (eventType === 'ACTIVITY_PAUSED') {
    audio.value.pause()
  } else if (eventType === 'ACTIVITY_RESUMED') {
    audio.value.play().catch(() => { error.value = '音频无法恢复播放，请检查浏览器播放权限' })
  }
}

function scheduleRefresh() {
  if (refreshTimer) return
  refreshTimer = window.setTimeout(() => {
    refreshTimer = 0
    refresh()
  }, 150)
}

function scheduleLeaderboardRefresh() {
  if (leaderboardTimer) return
  leaderboardTimer = window.setTimeout(() => {
    leaderboardTimer = 0
    refreshLeaderboards()
  }, 300)
}

function onSocketEvent(event) {
  if (event.type === 'SOCKET_ERROR') {
    error.value = '实时连接异常，正在重连'
    return
  }
  if (event.type === 'SOCKET_CONNECTED') {
    lastAppliedRevision = 0
    refresh()
    return
  }
  if (isStaleRevision(event.revision)) return
  noteRevision(event.revision)
  if (event.type === 'ACTIVITY_RESET' && event.roundId) currentRoundId.value = event.roundId
  if (event.type === 'ROUND_ENDED' || event.type === 'ACTIVITY_FINISHED') {
    timelineStarted.value = true
    markRoundEnded(currentRoundId.value)
  }
  if (event.type === 'ACTIVITY_RESET') {
    timelineStarted.value = false
    clearRoundEnded()
  }
  if (event.type === 'ACTIVITY_STARTED' || event.type === 'ACTIVITY_RESUMED') {
    timelineStarted.value = true
    clearRoundEnded()
  }
  syncAudio(event.type, event.roundId)
  scheduleRefresh()
  if (event.type === 'ONLINE_SCORE_LOCKED' || event.type === 'ACTIVITY_FINISHED' || event.type === 'ROUND_ENDED') {
    scheduleLeaderboardRefresh()
  }
}

async function openConsole() {
  await refresh()
  await refreshLeaderboards()
  await drawJoinQr()
  if (!socket.value) socket.value = createActivitySocket(eventId, onSocketEvent)
}

function refresh() {
  if (refreshPromise) {
    refreshQueued = true
    return refreshPromise
  }
  refreshPromise = runRefresh().finally(() => {
    refreshPromise = null
    if (refreshQueued) {
      refreshQueued = false
      refresh()
    }
  })
  return refreshPromise
}

async function runRefresh() {
  try {
    const [snapshot, roundList, currentLeaderboard] = await Promise.all([
      getHostState(eventId, hostToken.value),
      getHostRounds(eventId, hostToken.value),
      getLeaderboard(eventId),
    ])
    const snapshotRevision = Number(snapshot?.state?.revision)
    if (Number.isFinite(snapshotRevision) && snapshotRevision < lastAppliedRevision) return snapshot
    noteRevision(snapshotRevision)
    state.value = snapshot?.state ?? null
    canAdvanceQuestion.value = Boolean(snapshot?.canAdvanceQuestion)
    canFinishRound.value = Boolean(snapshot?.canFinishRound)
    players.value = snapshot?.players ?? []
    rounds.value = snapshot?.rounds ?? roundList?.rounds ?? []
    currentRoundId.value = snapshot?.currentRoundId ?? roundList?.currentRoundId ?? null
    timelineRunning.value = Boolean(snapshot?.timelineRunning)
    if (state.value?.status === 'FINISHED' && !timelineRunning.value) markRoundEnded(currentRoundId.value)
    roundLeaderboard.value = currentLeaderboard || state.value?.leaderboard || []
    if (timelineRunning.value || ['ANSWERING', 'ANSWER_LOCKED', 'ANSWER_REVEALED', 'FINISHED'].includes(state.value?.status)) timelineStarted.value = true
    if (state.value?.status === 'WAITING' && !timelineRunning.value && !roundEnded.value) timelineStarted.value = false
    error.value = ''
    return snapshot
  } catch (err) {
    if (err.response?.status === 401) {
      socket.value?.deactivate()
      socket.value = null
      clearHostToken()
      hostToken.value = ''
      authenticated.value = false
      error.value = '主持人登录已失效，请重新输入密码'
    } else {
      error.value = err.response?.data?.message || '无法获取主持人状态，请确认服务已启动'
    }
    return null
  }
}

function refreshLeaderboards() {
  if (leaderboardPromise) {
    leaderboardQueued = true
    return leaderboardPromise
  }
  leaderboardPromise = runLeaderboardRefresh().finally(() => {
    leaderboardPromise = null
    if (leaderboardQueued) {
      leaderboardQueued = false
      refreshLeaderboards()
    }
  })
  return leaderboardPromise
}

async function runLeaderboardRefresh() {
  leaderboardLoading.value = true
  try {
    const [online, total] = await Promise.all([
      getOnlineLeaderboard(eventId),
      getTotalLeaderboard(eventId),
    ])
    onlineLeaderboard.value = online?.entries || []
    totalLeaderboard.value = total?.entries || []
  } catch (err) {
    error.value = err.response?.data?.message || '无法获取线上榜或总榜'
  } finally {
    leaderboardLoading.value = false
  }
}

async function refreshAll() {
  await refresh()
  await refreshLeaderboards()
}

async function login() {
  if (!password.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await hostLogin(password.value.trim())
    hostToken.value = result.token
    saveHostToken(result.token)
    authenticated.value = true
    password.value = ''
    await openConsole()
  } catch (err) {
    error.value = err.response?.data?.message || '密码错误，请重新输入'
  } finally {
    busy.value = false
  }
}

async function logout() {
  try { await hostLogout(hostToken.value) } catch { /* Local session must still close. */ }
  socket.value?.deactivate()
  socket.value = null
  audio.value?.pause()
  hostToken.value = ''
  clearHostToken()
  authenticated.value = false
  state.value = null
  players.value = []
}

function showActionError(err) {
  if (err.response?.status === 401) {
    logout()
    error.value = '主持人登录已失效，请重新输入密码'
  } else {
    error.value = err.response?.data?.message || '操作失败'
  }
}

async function action(name) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await hostAction(eventId, name, hostToken.value)
    if (result) {
      state.value = result
      noteRevision(result.revision)
      timelineRunning.value = Boolean(result.timelineRunning)
    }
    if (name === 'start') {
      timelineStarted.value = true
      clearRoundEnded()
      seekTo(round.value?.startMs ?? 0)
      try { await audio.value?.play() } catch { error.value = '时间轴已启动，请允许浏览器播放音频' }
    }
    if (name === 'pause') audio.value?.pause()
    if (name === 'resume') syncAudio('ACTIVITY_RESUMED')
    if (name === 'reset') {
      timelineStarted.value = false
      clearRoundEnded()
      syncAudio('ACTIVITY_RESET')
    }
    if (name === 'finish') {
      timelineStarted.value = true
      markRoundEnded(currentRoundId.value)
      syncAudio('ROUND_ENDED')
      await refreshLeaderboards()
    }
    await refresh()
    if (name === 'next' && result?.audioPositionMs != null) {
      if (!await seekToReady(Number(result.audioPositionMs))) {
        audio.value?.pause()
        error.value = '题目已切换，但音频定位失败，请刷新控制台后重试'
      } else if (timelineRunning.value) {
        try { await audio.value?.play() } catch { error.value = '音频无法继续播放，请检查浏览器播放权限' }
      }
    }
  } catch (err) {
    if (name === 'start') audio.value?.pause()
    showActionError(err)
  } finally {
    busy.value = false
  }
}

async function beginSelectedRound() {
  if (!roundEnded.value || !nextRoundId.value || busy.value) return
  const targetRoundId = nextRoundId.value
  busy.value = true
  error.value = ''
  try {
    const selected = await selectHostRound(eventId, targetRoundId, hostToken.value)
    noteRevision(selected?.revision)
    currentRoundId.value = targetRoundId
    timelineStarted.value = false
    clearRoundEnded()
    await refresh()
    audio.value?.pause()
    seekTo(roundById(targetRoundId)?.startMs ?? 0)
  } catch (err) {
    showActionError(err)
    return
  } finally {
    busy.value = false
  }
  await action('start')
}

function confirmReset() {
  Modal.confirm({
    title: '重置活动',
    content: '重置将清空在线玩家与本轮作答记录，所有参与者需重新加入。',
    okText: '确认重置',
    cancelText: '取消',
    onOk: () => action('reset'),
  })
}

function changeRound(roundId) {
  if (!roundId || roundId === currentRoundId.value || !canSwitchRound.value) return
  Modal.confirm({
    title: '切换轮次',
    content: '切换轮次将清空在线玩家与当前作答记录，所有参与者需重新加入。',
    okText: '确认切换',
    cancelText: '取消',
    async onOk() {
      busy.value = true
      try {
        const selected = await selectHostRound(eventId, roundId, hostToken.value)
        noteRevision(selected?.revision)
        currentRoundId.value = roundId
        timelineStarted.value = false
        clearRoundEnded()
        await refresh()
        audio.value?.pause()
        seekTo(roundById(roundId)?.startMs ?? 0)
      } catch (err) {
        showActionError(err)
      } finally {
        busy.value = false
      }
    },
  })
}

async function drawJoinQr() {
  await nextTick()
  if (qrCanvas.value) await QRCode.toCanvas(qrCanvas.value, joinUrl, { width: 156, margin: 1, color: { dark: '#171717', light: '#ffffff' } })
}

async function copyJoinUrl() {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(joinUrl)
    } else {
      throw new Error('Clipboard API unavailable')
    }
    error.value = ''
    message.success('已复制加入地址')
  } catch {
    try {
      const input = document.createElement('textarea')
      input.value = joinUrl
      input.setAttribute('readonly', '')
      input.style.position = 'fixed'
      input.style.opacity = '0'
      document.body.appendChild(input)
      let copied = false
      try {
        input.select()
        input.setSelectionRange(0, input.value.length)
        copied = document.execCommand('copy')
      } finally {
        input.remove()
      }
      if (!copied) throw new Error('Copy command failed')
      error.value = ''
      message.success('已复制加入地址')
    } catch {
      error.value = '无法自动复制，请选中下方地址并手动复制'
    }
  }
}

function formatTime(ms) {
  const seconds = Math.floor((ms || 0) / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <section v-if="!authenticated" class="host-login-page">
    <a-card class="host-login-panel">
      <div class="state-center">
        <div class="login-mark"><LockKeyhole :size="25" /></div>
        <div class="title-kicker" lang="ja">管理者ログイン</div>
        <h1 class="panel-title-lg">主持人登录</h1>
        <p class="muted">请输入主持人密码进入活动控制台。参与者无需输入密码。</p>
      </div>
      <form @submit.prevent="login">
        <div class="field-label">主持人密码</div>
        <a-input-password v-model:value="password" size="large" placeholder="请输入密码" autocomplete="current-password" />
        <a-button class="full-width" type="primary" size="large" :disabled="!password.trim()" :loading="busy" @click="login">
          <KeyRound :size="17" />进入控制台
        </a-button>
      </form>
      <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />
    </a-card>
  </section>

  <section v-else class="host-page">
    <div class="host-header">
      <div>
        <div class="title-kicker" lang="ja">進行管理</div>
        <h1>主持人控制台</h1>
        <p class="muted">现场音响播放 BGM，主持人在这里推进题目和公布答案。</p>
      </div>
      <a-space class="host-stats" :size="22">
        <span class="stat-card"><UsersRound :size="17" /><strong>{{ state?.playerCount || 0 }}</strong><small>人在线</small></span>
        <a-badge :status="statusColor" :text="statusLabel" />
      </a-space>
    </div>

    <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />

    <div v-if="state" class="host-grid">
      <a-card class="host-control">
        <div class="section-title"><span>轮次与播放</span><a-button size="small" title="刷新状态" :loading="leaderboardLoading" @click="refreshAll"><RefreshCw :size="15" /></a-button></div>
        <div class="field-label round-label">{{ roundEnded ? '开启轮次' : '当前轮次' }}</div>
        <a-select
          v-if="roundEnded"
          class="round-picker"
          v-model:value="nextRoundId"
          :disabled="busy"
          aria-label="选择要开启的轮次"
        >
          <a-select-option v-for="item in rounds" :key="item.id" :value="item.id">{{ item.name }} · {{ formatTime(item.startMs) }}–{{ formatTime(item.endMs) }}</a-select-option>
        </a-select>
        <a-select v-else class="round-picker" :value="currentRoundId" :disabled="busy || !canSwitchRound" @change="changeRound">
          <a-select-option v-for="item in rounds" :key="item.id" :value="item.id">{{ item.name }} · {{ formatTime(item.startMs) }}–{{ formatTime(item.endMs) }}</a-select-option>
        </a-select>
        <div class="host-progress">
          <a-statistic :value="displayQuestionNo" :value-style="{ color: 'var(--accent)', fontSize: '53px', fontWeight: 800, lineHeight: 1 }" />
          <div><strong>/ {{ state.totalQuestions }} 题</strong><span class="muted">{{ playbackLabel }}</span></div>
        </div>
        <div class="control-actions">
          <a-button v-if="canStart" type="primary" size="large" :loading="busy" @click="action('start')"><Play :size="18" />开始播放</a-button>
          <a-button v-if="roundEnded" type="primary" size="large" :disabled="!nextRoundId" :loading="busy" @click="beginSelectedRound"><Play :size="18" />开启下一轮</a-button>
          <a-button v-if="timelineRunning" size="large" :loading="busy" @click="action('pause')"><Pause :size="18" />暂停</a-button>
          <a-button v-if="timelineStarted && !timelineRunning && !roundEnded" type="primary" size="large" :loading="busy" @click="action('resume')"><Play :size="18" />恢复</a-button>
          <a-button size="large" :loading="busy" @click="confirmReset"><ListRestart :size="18" />重置活动</a-button>
        </div>
        <div class="manual-actions">
          <div class="section-title">手动控制</div>
          <a-space wrap>
            <a-button :disabled="busy || state.status !== 'ANSWERING'" @click="action('lock')">锁卷</a-button>
            <a-button :disabled="busy || state.status !== 'ANSWER_LOCKED'" @click="action('reveal')"><Eye :size="15" />揭晓答案</a-button>
            <a-button v-if="!canFinishRound" :disabled="busy || !canAdvanceQuestion" @click="action('next')">下一题<ChevronRight :size="15" /></a-button>
            <a-button v-else type="primary" :loading="busy" @click="action('finish')">结束本轮<ChevronRight :size="15" /></a-button>
          </a-space>
        </div>
      </a-card>

      <a-card class="host-audio">
        <div class="section-title"><span>音频与时间轴</span><a-badge :status="timelineRunning ? 'processing' : 'default'" :text="playbackLabel" /></div>
        <div class="audio-board-main"><span class="audio-board-label"><Radio :size="16" />当前轮次</span><strong class="audio-board-code">{{ round?.name || '未选择' }}</strong></div>
        <p class="audio-board-meta">{{ formatTime(round?.startMs) }} – {{ formatTime(round?.endMs) }}</p>
        <audio ref="audio" :src="getAudioUrl(eventId)" preload="metadata" @timeupdate="handleTimeUpdate" @seeked="handleSeeked" />
        <a-progress :percent="audioProgress" :show-info="false" :stroke-color="'var(--accent)'" />
        <div class="audio-time"><span>{{ formatTime(audioPosition * 1000) }}</span><span>{{ formatTime(round?.endMs) }}</span></div>
        <label class="audio-volume">音量 <input v-model.number="volume" type="range" min="0" max="1" step="0.01" @input="audio.volume = volume" /></label>
      </a-card>

      <a-card class="host-join">
        <div class="host-join-body"><div><div class="section-title"><span>扫码加入</span><span class="muted">活动房间：{{ eventId }}</span></div><p class="muted">把二维码展示给现场参与者，大家扫码后输入昵称即可加入本轮活动。</p><a-button @click="copyJoinUrl"><Copy :size="16" />复制加入地址</a-button><p class="join-url-text">{{ joinUrl }}</p></div><canvas ref="qrCanvas" class="qr-code" aria-label="活动加入二维码"></canvas></div>
      </a-card>

      <a-card class="host-preview">
        <div class="section-title"><span>题目预览</span><a-tag v-if="question">{{ question.type === 'ATMOSPHERE' ? '氛围题' : '作品题' }}</a-tag></div>
        <template v-if="question">
          <div class="preview-meta">
            <span>第 {{ displayQuestionNo }} 题</span>
            <span>{{ question.difficulty === 'EASY' ? '简单' : question.difficulty === 'MEDIUM' ? '中等' : '困难' }}</span>
          </div>
          <h2 class="question-title preview-title">{{ question.prompt }}</h2>
          <div class="preview-options">
            <div v-for="(option, index) in question.options" :key="option">
              <span>{{ String.fromCharCode(65 + index) }}</span>{{ option }}
            </div>
          </div>
          <p v-if="question.workName" class="audio-board-meta">作品提示：{{ question.workName }}</p>
          <p v-if="question.author" class="audio-board-meta">出题人：{{ question.author }}</p>
          <a-alert
            v-if="state.status === 'ANSWER_REVEALED' && question.answerIndex != null"
            class="explanation-alert"
            type="success"
            show-icon
          >
            <template #message>正确答案：{{ String.fromCharCode(65 + question.answerIndex) }} · {{ question.workName }}<span v-if="question.author"> · 出题人：{{ question.author }}</span></template>
            <template #description>
              <span v-if="question.analysis">{{ question.analysis }}</span>
            </template>
          </a-alert>
        </template>
        <a-empty v-else description="题目尚未上屏" />
      </a-card>

      <a-card class="host-players"><div class="section-title"><span>现场作答进度</span><span class="muted">已作答 {{ state.answeredCount || 0 }} / {{ state.playerCount || 0 }} 人</span></div><div v-if="players.length" class="player-wall"><div v-for="player in players" :key="player.playerId" class="player-card" :class="{ 'is-answered': player.answered }"><span class="player-name">{{ player.nickname }}</span><Check v-if="player.answered" :size="14" /></div></div><a-empty v-else description="还没有玩家在线" :image-style="{ height: '48px' }" /></a-card>

      <a-card class="host-leaderboard">
        <div class="section-title">
          <span>活动榜单</span>
          <div class="host-leader-header-right">
            <a-button size="small" :loading="leaderboardLoading" @click="refreshAll"><RefreshCw :size="14" />刷新榜单</a-button>
            <a-button size="small" type="link" :href="summaryTxtUrl" target="_blank" class="txt-export-btn">下载 / 查看 TXT 榜单</a-button>
          </div>
        </div>
        <a-tabs v-model:activeKey="activeLeaderboardTab" class="host-leaderboard-tabs">
          <a-tab-pane key="round" tab="本轮榜">
            <a-list v-if="roundLeaderboard.length" :data-source="roundLeaderboard" :grid="{ gutter: 14, xs: 1, sm: 2, md: 2, lg: 3, xl: 3 }" row-key="playerId" class="host-leaderboard-list">
              <template #renderItem="{ item }">
                <a-list-item class="host-leader-item">
                  <div class="host-leader-card">
                    <div class="host-leader-rank" :class="`rank-${item.rank}`">{{ item.rank }}</div>
                    <div class="host-leader-body">
                      <div class="host-leader-name">{{ item.nickname }}</div>
                      <div class="host-leader-time">耗时 {{ (item.durationMs / 1000).toFixed(1) }}s · 已答 {{ item.answeredCount }} / {{ item.totalQuestions }}</div>
                    </div>
                    <div class="host-leader-score"><span class="score-value">{{ item.score }}</span><span class="score-unit">分</span></div>
                  </div>
                </a-list-item>
              </template>
            </a-list>
            <div v-else class="ranking-hidden host-ranking-hidden">
              <span class="ranking-lock">?</span>
              <p>本轮结算后生成本轮榜，所有人的分数会在最后一题结束后一起揭晓。</p>
            </div>
          </a-tab-pane>
          <a-tab-pane key="online" tab="线上榜">
            <a-list v-if="onlineLeaderboard.length" :data-source="onlineLeaderboard" :grid="{ gutter: 14, xs: 1, sm: 2, md: 2, lg: 3, xl: 3 }" row-key="participantId" class="host-leaderboard-list">
              <template #renderItem="{ item }">
                <a-list-item class="host-leader-item">
                  <div class="host-leader-card">
                    <div class="host-leader-rank" :class="`rank-${item.rank}`">{{ item.rank }}</div>
                    <div class="host-leader-body">
                      <div class="host-leader-name">{{ item.nickname }}</div>
                      <div class="host-leader-time">耗时 {{ (item.durationMs / 1000).toFixed(1) }}s · {{ formatDateTime(item.lockedAt) }}</div>
                    </div>
                    <div class="host-leader-score"><span class="score-value">{{ item.score }}</span><span class="score-unit">分</span></div>
                  </div>
                </a-list-item>
              </template>
            </a-list>
            <a-empty v-else description="暂无线上锁定成绩" />
          </a-tab-pane>
          <a-tab-pane key="total" tab="总榜">
            <a-list v-if="totalLeaderboard.length" :data-source="totalLeaderboard" :grid="{ gutter: 14, xs: 1, sm: 2, md: 2, lg: 3, xl: 3 }" row-key="participantId" class="host-leaderboard-list">
              <template #renderItem="{ item }">
                <a-list-item class="host-leader-item">
                  <div class="host-leader-card total-leader-card">
                    <div class="host-leader-rank" :class="`rank-${item.rank}`">{{ item.rank }}</div>
                    <div class="host-leader-body">
                      <div class="host-leader-name">{{ item.nickname }}</div>
                      <div class="host-leader-time">线下 {{ item.offlineScore }} 分 · 线上 {{ item.onlineScore }} 分 · {{ item.offlineRoundCount }} 轮</div>
                      <div class="host-leader-time">累计耗时 {{ (item.totalDurationMs / 1000).toFixed(1) }}s</div>
                    </div>
                    <div class="host-leader-score"><span class="score-value">{{ item.totalScore }}</span><span class="score-unit">分</span></div>
                  </div>
                </a-list-item>
              </template>
            </a-list>
            <a-empty v-else description="暂无总榜成绩" />
          </a-tab-pane>
        </a-tabs>
      </a-card>
    </div>
  </section>
</template>

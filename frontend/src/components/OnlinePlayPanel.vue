<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ArrowRight, Clock3, Headphones, LogOut, Music2, RefreshCw, RotateCcw, ShieldCheck } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { MODES } from '../services/storage'
import { useActivityStore } from '../stores/activity'
import { useOnlineStore } from '../stores/online'
import OnlineProgress from './OnlineProgress.vue'
import OnlineResultPanel from './OnlineResultPanel.vue'

const route = useRoute()
const router = useRouter()
const activity = useActivityStore()
const online = useOnlineStore()
const audio = ref(null)
const audioReady = ref(false)
const leaving = ref(false)
let completing = false
let timer
let clockStartedAt = null
let activeMs = 0
const SEEK_TIMEOUT_MS = 12000
const MEDIA_HAVE_FUTURE_DATA = 3
const seekingQuestion = ref(false)
const buffering = ref(false)

const eventId = String(route.query.event || activity.eventId || 'demo')
const isStart = computed(() => online.phase === 'IDLE')
const isInProgress = computed(() => online.phase === 'IN_PROGRESS')
const isResult = computed(() => online.phase === 'RESULT_READY')
const isLocked = computed(() => online.phase === 'LOCKED')
const isError = computed(() => online.phase === 'ERROR')
const question = computed(() => online.currentQuestion)
const lastQuestion = computed(() => online.questions[online.questions.length - 1] || null)
const questionSeconds = computed(() => Math.ceil(online.questionRemainingMs / 1000))
const remainingPercent = computed(() => {
  if (!question.value) return 0
  const length = question.value.answerEndMs - question.value.answerStartMs
  return length > 0 ? Math.max(0, Math.min(100, online.questionRemainingMs / length * 100)) : 0
})
const phaseLabel = computed(() => ({
  IDLE: online.hasResumeProgress ? '等待继续' : '等待开始',
  IN_PROGRESS: '线上答题中',
  RESULT_READY: '等待确认',
  LOCKED: '成绩已锁定',
  ERROR: '需要重试',
}[online.phase] || '线上模式'))
const typeLabel = computed(() => question.value?.type === 'ATMOSPHERE' ? '氛围题' : '作品题')
const difficultyLabel = computed(() => ({
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}[question.value?.difficulty] || ''))
const questionWindowCopy = computed(() => {
  if (online.isWindowOpen) return '本题开放作答，可在结束前修改选项'
  if (online.currentIndex >= 0 && lastQuestion.value && online.audioPositionMs >= lastQuestion.value.answerEndMs) {
    return '全部题目已结束，等待音频播放完毕'
  }
  if (online.currentIndex >= 0) return '本题已结束，下一题即将开始'
  return '音频已开始，第一题即将上屏'
})

onMounted(async () => {
  activity.setSession(eventId, MODES.ONLINE)
  if (!activity.isParticipant) {
    await router.replace({ path: '/online', query: { event: eventId } })
    return
  }
  online.nickname = activity.nickname
  await online.loadQuestionSet({
    eventId,
    participantId: activity.participantId,
    nickname: activity.nickname,
  })
  await nextTick()
  if (isResult.value && !online.preview) await online.previewScore()
  window.addEventListener('visibilitychange', onVisibilityChange)
  window.addEventListener('pagehide', persistBeforeLeave)
  window.addEventListener('beforeunload', persistBeforeLeave)
  timer = window.setInterval(tick, 200)
})

onBeforeUnmount(() => {
  window.clearInterval(timer)
  window.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('pagehide', persistBeforeLeave)
  window.removeEventListener('beforeunload', persistBeforeLeave)
  stopClock()
  audio.value?.pause()
  if (isInProgress.value) {
    syncClock()
    online.persistProgress(true)
  }
})

function currentActiveDuration() {
  return activeMs + (clockStartedAt === null ? 0 : performance.now() - clockStartedAt)
}

function startClock() {
  if (clockStartedAt === null) clockStartedAt = performance.now()
}

function stopClock() {
  if (clockStartedAt === null) return
  activeMs += performance.now() - clockStartedAt
  clockStartedAt = null
}

function audioPosition() {
  return Math.max(0, Math.round((audio.value?.currentTime || 0) * 1000))
}

function syncClock() {
  if (!online.questionSet) return
  online.updateClock(audioPosition(), currentActiveDuration())
}

function seekTo(positionMs) {
  if (!audio.value) return false
  const safePosition = Math.max(0, Math.min(positionMs, online.questionSet?.audioEndMs || positionMs))
  try {
    audio.value.currentTime = safePosition / 1000
    return true
  } catch {
    // Metadata is not ready yet; loadedmetadata applies the saved position.
    return false
  }
}

function waitForMediaEvent(eventName, timeoutMs) {
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

async function seekToReady(positionMs, timeoutMs = SEEK_TIMEOUT_MS) {
  const element = audio.value
  if (!element) return false
  const targetMs = Math.max(0, Math.min(positionMs, online.questionSet?.audioEndMs || positionMs))
  if (Math.abs(element.currentTime * 1000 - targetMs) >= 50) {
    const seeked = waitForMediaEvent('seeked', timeoutMs)
    try {
      element.currentTime = targetMs / 1000
    } catch {
      return false
    }
    if (!await seeked) return false
  }
  if (element.readyState >= MEDIA_HAVE_FUTURE_DATA) return true
  return waitForMediaEvent('canplay', timeoutMs)
}

function targetPosition() {
  return online.hasResumeProgress ? online.audioPositionMs : (online.questionSet?.audioStartMs || 0)
}

function ensureMetadata() {
  if (audioReady.value && audio.value?.readyState >= 1) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const element = audio.value
    if (!element) {
      reject(new Error('音频元素尚未就绪'))
      return
    }
    const cleanup = () => {
      element.removeEventListener('loadedmetadata', onLoaded)
      element.removeEventListener('error', onError)
    }
    const onLoaded = () => {
      cleanup()
      audioReady.value = true
      resolve()
    }
    const onError = () => {
      cleanup()
      reject(new Error('音频加载失败'))
    }
    element.addEventListener('loadedmetadata', onLoaded, { once: true })
    element.addEventListener('error', onError, { once: true })
    if (!element.getAttribute('src')) element.src = online.questionSet?.audioUrl || ''
    element.load()
  })
}

async function onLoadedMetadata() {
  audioReady.value = true
  if (isStart.value || isInProgress.value) seekTo(targetPosition())
}

async function startOrResume() {
  if (!online.questionSet || !audio.value) return
  const resuming = isStart.value && online.hasResumeProgress
  try {
    await ensureMetadata()
    if (resuming) {
      activeMs = online.activeDurationMs
      if (!await seekToReady(online.audioPositionMs)) throw new Error('音频定位失败')
      online.resumeAttempt()
    } else {
      online.beginAttempt()
      activeMs = 0
      if (!await seekToReady(online.questionSet.audioStartMs)) throw new Error('音频定位失败')
    }
    buffering.value = false
    await audio.value.play()
  } catch (err) {
    stopClock()
    online.phase = 'IDLE'
    online.setVisiblePause(false)
    message.error(err?.message === '音频加载失败'
      ? '音频加载失败，请重新加载'
      : err?.message === '音频定位失败'
        ? '音频定位失败，请稍后重试'
        : '音频无法播放，请检查浏览器播放权限')
  }
}

function onPlay() {
  if (!isInProgress.value) online.phase = 'IN_PROGRESS'
  startClock()
}

function onWaiting() {
  if (!isInProgress.value || online.visiblePause) return
  buffering.value = true
  stopClock()
  if (!seekingQuestion.value) syncClock()
}

function onPlaying() {
  buffering.value = false
  if (isInProgress.value && !online.visiblePause && !seekingQuestion.value) startClock()
}

function onPause() {
  stopClock()
  if (seekingQuestion.value) return
  if (isInProgress.value) syncClock()
  if (isInProgress.value && !completing && !leaving.value) {
    online.setVisiblePause(true)
    online.persistProgress(true)
  }
}

function onTimeUpdate() {
  if (seekingQuestion.value || !isInProgress.value || !online.questionSet) return
  if (audioPosition() >= online.questionSet.audioEndMs) {
    finishPlayback()
    return
  }
  syncClock()
}

function tick() {
  if (seekingQuestion.value || !isInProgress.value || !audio.value || audio.value.paused) return
  syncClock()
  if (online.questionSet && audioPosition() >= online.questionSet.audioEndMs) finishPlayback()
}

async function finishPlayback() {
  if (completing || !online.questionSet) return
  completing = true
  stopClock()
  audio.value?.pause()
  seekTo(online.questionSet.audioEndMs)
  online.updateClock(online.questionSet.audioEndMs, currentActiveDuration())
  online.completeAttempt()
  await online.previewScore()
  completing = false
}

function onEnded() {
  if (!isInProgress.value) return
  if (online.questionSet && audioPosition() + 1000 >= online.questionSet.audioEndMs) finishPlayback()
  else online.setPhaseError('音频提前结束，请重新加载后继续')
}

function onAudioError() {
  if (!online.questionSet || leaving.value) return
  stopClock()
  online.setPhaseError('音频加载失败，请重新加载音频或重试本次答题')
}

async function resumeFromPause() {
  if (!audio.value) return
  online.setVisiblePause(false)
  activeMs = online.activeDurationMs
  if (!await seekToReady(online.audioPositionMs)) {
    online.setVisiblePause(true)
    message.error('音频定位失败，请稍后重试')
    return
  }
  buffering.value = false
  try {
    await audio.value.play()
  } catch {
    online.setVisiblePause(true)
    message.error('音频无法恢复播放，请检查浏览器播放权限')
  }
}

function onVisibilityChange() {
  if (!document.hidden) return
  if (isInProgress.value) {
    stopClock()
    audio.value?.pause()
    syncClock()
    online.setVisiblePause(true)
    online.persistProgress(true)
  }
}

function persistBeforeLeave() {
  if (!isInProgress.value) return
  stopClock()
  syncClock()
  online.persistProgress(true)
}

async function reloadAudio() {
  online.clearPhaseError()
  if (!online.questionSet) {
    await online.loadQuestionSet({
      eventId,
      participantId: activity.participantId,
      nickname: activity.nickname,
    })
    await nextTick()
    if (isResult.value && !online.preview) await online.previewScore()
    return
  }
  online.phase = 'IDLE'
  audioReady.value = false
  audio.value?.load()
  try {
    await ensureMetadata()
    if (!await seekToReady(targetPosition())) throw new Error('音频定位失败')
  } catch {
    online.setPhaseError('音频仍然无法加载，请检查网络连接')
  }
}

async function restartAudioAttempt() {
  online.retryAttempt()
  activeMs = 0
  audioReady.value = false
  audio.value?.load()
  try {
    await ensureMetadata()
    if (!await seekToReady(online.questionSet?.audioStartMs || 0)) throw new Error('音频定位失败')
  } catch {
    online.setPhaseError('音频加载失败，请稍后重试')
  }
}

function handleResultRestart() {
  activeMs = 0
  seekTo(online.questionSet?.audioStartMs || 0)
}

function chooseOption(index) {
  online.selectOption(index)
}

async function goToNextQuestion() {
  if (!online.canAdvanceQuestion || seekingQuestion.value) return
  const nextQuestion = online.questions[online.currentIndex + 1]
  if (!nextQuestion) return
  const wasPlaying = Boolean(audio.value && !audio.value.paused)
  seekingQuestion.value = true
  stopClock()
  syncClock()
  try {
    if (!await seekToReady(nextQuestion.answerStartMs)) {
      message.error('音频定位失败，请稍后重试')
      audio.value?.pause()
      online.setVisiblePause(true)
      online.persistProgress(true)
      return
    }
    if (leaving.value) return
    if (!online.advanceToNextQuestion()) {
      if (wasPlaying) startClock()
      return
    }
    buffering.value = false
    if (audio.value?.paused) {
      try {
        await audio.value.play()
      } catch {
        online.setVisiblePause(true)
        message.error('音频无法继续播放，请检查播放权限')
        return
      }
    }
    startClock()
    syncClock()
    online.persistProgress(true)
  } finally {
    seekingQuestion.value = false
  }
}

function formatDuration(ms) {
  const total = Math.max(0, Math.round((Number(ms) || 0) / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function leave() {
  leaving.value = true
  stopClock()
  if (isInProgress.value) {
    syncClock()
    online.persistProgress(true)
  }
  audio.value?.pause()
  router.push({ path: '/online', query: { event: eventId } })
}
</script>

<template>
  <section class="play-layout solo online-play-layout">
    <div class="play-main">
      <div class="topline online-topline">
        <div>
          <div class="online-title-row">
            <h1>{{ online.questionSet?.eventName || activity.eventName || '线上猜歌' }}</h1>
            <a-tag class="online-mode-tag">线上模式</a-tag>
          </div>
          <p class="muted">{{ phaseLabel }}<template v-if="online.nickname"> · {{ online.nickname }}</template></p>
        </div>
        <a-button title="退出并保存答题进度" @click="leave">
          <LogOut :size="16" />退出
        </a-button>
      </div>

      <a-alert v-if="online.questionSetUpdated" type="warning" message="题目已更新，请重新开始" show-icon class="form-alert" />
      <a-alert v-if="online.storageWarning" type="warning" :message="online.storageWarning" show-icon class="form-alert" />

      <div v-if="online.loading" class="online-loading-card">
        <a-spin size="large" />
        <span class="muted">正在加载线上题集...</span>
      </div>

      <template v-else-if="isStart">
        <a-card class="state-card online-start-card">
          <div class="state-center">
            <div class="waiting-mark"><Headphones :size="28" /></div>
            <span class="section-kicker" lang="ja">{{ online.hasResumeProgress ? '続きから' : 'オンライン挑戦' }}</span>
            <h2 class="panel-title">{{ online.hasResumeProgress ? '继续上次答题' : '准备开始答题' }}</h2>
            <p class="muted online-start-copy">
              {{ online.hasResumeProgress
                ? `已保存到第 ${online.displayQuestionNumber || 1} 题，音频将从保存位置继续。`
                : '音频开始后自动出题；已选答案后可进入下一题，不能暂停、任意跳题、回看或拖动进度。' }}
            </p>
            <div class="online-start-stats">
              <div><strong>{{ online.totalQuestions }}</strong><span>题</span></div>
              <div><strong>{{ formatDuration((online.questionSet?.audioEndMs || 0) - (online.questionSet?.audioStartMs || 0)) }}</strong><span>音频时长</span></div>
              <div><strong>{{ online.answeredCount }}</strong><span>已答</span></div>
            </div>
            <a-button type="primary" size="large" class="online-primary-action" @click="startOrResume">
              {{ online.hasResumeProgress ? '继续答题' : '开始答题' }}
            </a-button>
          </div>
        </a-card>
      </template>

      <template v-else-if="isError">
        <a-card class="state-card online-error-card">
          <div class="state-center">
            <div class="waiting-mark error-mark"><RefreshCw :size="27" /></div>
            <h2 class="panel-title">答题暂时中断</h2>
            <p class="muted">{{ online.errorMessage || '音频或网络出现异常，请重新加载。' }}</p>
            <div class="result-actions centered">
              <a-button @click="leave">返回登记页</a-button>
              <a-button type="primary" @click="reloadAudio">
                <RefreshCw :size="16" />{{ online.questionSet ? '重新加载音频' : '重新加载题集' }}
              </a-button>
              <a-button v-if="online.questionSet" @click="restartAudioAttempt">
                <RotateCcw :size="16" />重试本次答题
              </a-button>
            </div>
          </div>
        </a-card>
      </template>

      <template v-else-if="isResult || isLocked">
        <OnlineResultPanel @restart="handleResultRestart" />
      </template>

      <template v-else-if="isInProgress">
        <OnlineProgress />

        <a-alert
          v-if="buffering"
          class="form-alert"
          type="info"
          show-icon
          message="音频缓冲中，计时已暂停，请稍候…"
        />

        <a-card v-if="online.isWindowOpen && question" class="question-card online-question-card">
          <div class="countdown-track">
            <div class="countdown-fill" :style="{ width: `${remainingPercent}%` }"></div>
          </div>
          <span class="section-kicker" lang="ja">問題に挑戦</span>
          <div class="question-heading">
            <a-space :size="6">
              <a-tag color="orangered">{{ typeLabel }}</a-tag>
              <a-tag>{{ difficultyLabel }}</a-tag>
            </a-space>
            <div class="timer" :class="{ urgent: questionSeconds <= 5 }">
              <Clock3 :size="17" />本题剩余 {{ questionSeconds }} 秒
            </div>
          </div>
          <p v-if="question.author" class="author-line">出题人：{{ question.author }}</p>
          <h2 class="question-title">{{ question.prompt }}</h2>
          <div class="option-grid online-option-grid">
            <button
              v-for="(option, index) in question.options"
              :key="`${question.id}-${index}`"
              class="option-button"
              :class="{ selected: online.currentAnswer === index }"
              :disabled="seekingQuestion"
              @click="chooseOption(index)"
            >
              <span class="option-letter">{{ String.fromCharCode(65 + index) }}</span>
              <span>{{ option }}</span>
            </button>
          </div>
          <div class="question-actions">
            <div class="question-action-copy">
              <span class="muted">
                {{ online.currentAnswer === null
                  ? '选择一个答案，结束前可以修改'
                  : `已选择 ${String.fromCharCode(65 + online.currentAnswer)}，结束前可以修改` }}
              </span>
              <span class="muted">{{ online.answeredCount }} 已答 / {{ online.unansweredCount }} 未答</span>
            </div>
            <a-button
              v-if="online.currentIndex < online.questions.length - 1"
              type="primary"
              size="large"
              :disabled="!online.canAdvanceQuestion || seekingQuestion"
              :loading="seekingQuestion"
              :title="online.currentAnswer === null ? '请先选择一个答案' : seekingQuestion ? '音频定位中' : '进入下一题'"
              @click="goToNextQuestion"
            >
              {{ seekingQuestion ? '音频定位中' : '下一题' }}
              <ArrowRight v-if="!seekingQuestion" :size="16" />
            </a-button>
          </div>
        </a-card>

        <a-card v-else class="state-card online-transition-card">
          <div class="state-center">
            <div class="waiting-mark"><Music2 :size="27" /></div>
            <span class="section-kicker" lang="ja">音を聴いてください</span>
            <h2 class="panel-title">{{ questionWindowCopy }}</h2>
            <p class="muted">音频连续播放中，请留意旋律。题目会按题集顺序自动上屏。</p>
          </div>
        </a-card>
      </template>

      <a-alert
        v-if="online.errorMessage && !isError && !isResult && !isLocked"
        class="form-alert"
        type="error"
        :message="online.errorMessage"
        show-icon
      />

      <div class="online-audio-note">
        <ShieldCheck :size="14" />
        <span>答题进度保存在当前浏览器；已选答案后可点击「下一题」按顺序推进。</span>
      </div>
    </div>

    <audio
      ref="audio"
      v-if="online.questionSet && !isLocked"
      :src="online.questionSet.audioUrl"
      preload="auto"
      playsinline
      @loadedmetadata="onLoadedMetadata"
      @play="onPlay"
      @playing="onPlaying"
      @waiting="onWaiting"
      @pause="onPause"
      @timeupdate="onTimeUpdate"
      @ended="onEnded"
      @error="onAudioError"
    ></audio>

    <div v-if="online.visiblePause && isInProgress" class="online-pause-overlay">
      <div class="online-pause-card">
        <div class="waiting-mark"><Clock3 :size="28" /></div>
        <span class="section-kicker" lang="ja">一時停止</span>
        <h2>答题已暂停，进度已保存</h2>
        <p class="muted">返回后会从保存的音频位置继续，暂停期间不计入有效作答耗时。</p>
        <a-button type="primary" size="large" @click="resumeFromPause">继续答题</a-button>
      </div>
    </div>
  </section>
</template>

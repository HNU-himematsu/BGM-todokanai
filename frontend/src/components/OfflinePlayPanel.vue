<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, Clock3, Eye, LogOut, Radio, Send, Trophy } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { getLeaderboard, submitAnswer } from '../services/api'
import { createActivitySocket } from '../services/socket'
import { MODES } from '../services/storage'
import { useActivityStore } from '../stores/activity'
import { ACCENT } from '../theme'

const router = useRouter()
const activity = useActivityStore()
const selected = ref(null)
const submitting = ref(false)
const socket = ref(null)
const now = ref(Date.now())
const frozenAudioRemainingMs = ref(null)
const finalLeaderboard = ref([])
let timer

const state = computed(() => activity.state)
const question = computed(() => state.value?.question)
const isAnswering = computed(() => state.value?.status === 'ANSWERING')
const isLocked = computed(() => state.value?.status === 'ANSWER_LOCKED')
const isRevealing = computed(() => state.value?.status === 'ANSWER_REVEALED')
const isFinished = computed(() => state.value?.status === 'FINISHED')
const canSubmit = computed(() => isAnswering.value && state.value?.canAnswer && !state.value?.hasAnswered)
const audioRemainingMs = computed(() => {
  if (!state.value?.questionAudioEndEpochMs) return 0
  if (!state.value.timelineRunning && frozenAudioRemainingMs.value !== null) return frozenAudioRemainingMs.value
  return Math.max(0, state.value.questionAudioEndEpochMs - now.value)
})
const audioSecondsLeft = computed(() => Math.ceil(audioRemainingMs.value / 1000))
const audioPercent = computed(() => {
  const start = state.value?.questionAudioStartEpochMs
  const end = state.value?.questionAudioEndEpochMs
  if (!start || !end || end <= start) return 0
  return Math.max(0, Math.min(100, audioRemainingMs.value / (end - start) * 100))
})
const selectedLetter = computed(() => selected.value === null ? '' : String.fromCharCode(65 + selected.value))
const correctLetter = computed(() => {
  const index = question.value?.answerIndex
  return Number.isInteger(index) ? String.fromCharCode(65 + index) : ''
})
const hintText = computed(() => {
  const text = question.value?.analysis?.trim()
  return text && text !== '无' && text !== '無' ? text : ''
})
const displayQuestionNo = computed(() => Math.max(0, (state.value?.currentQuestionIndex ?? -1) + 1))
const progressPercent = computed(() => {
  if (!state.value?.totalQuestions) return 0
  return Math.round((displayQuestionNo.value / state.value.totalQuestions) * 100)
})
const difficultyLabel = computed(() => ({
  EASY: '简单',
  MEDIUM: '中等',
  HARD: '困难',
}[question.value?.difficulty] || ''))

onMounted(async () => {
  if (!activity.isParticipant || activity.mode !== MODES.OFFLINE) {
    await router.replace({ path: '/offline', query: { event: activity.eventId } })
    return
  }
  await refreshState()
  socket.value = createActivitySocket(activity.eventId, async (event) => {
    if (event.type === 'ACTIVITY_RESET') {
      activity.clearOfflinePlayer()
      await router.replace({ path: '/offline', query: { event: activity.eventId } })
      return
    }
    if (event.type === 'SOCKET_ERROR') return
    await refreshState()
  })
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 250)
})

onBeforeUnmount(() => {
  socket.value?.deactivate()
  window.clearInterval(timer)
})

async function refreshState() {
  const previousQuestionId = question.value?.id
  const ok = await activity.refreshState()
  if (!ok && !activity.playerId) {
    await router.replace({ path: '/offline', query: { event: activity.eventId } })
    return
  }
  if (!state.value) return
  if (state.value.question?.id !== previousQuestionId) selected.value = null
  if (state.value.hasAnswered) selected.value = state.value.selectedOption
  frozenAudioRemainingMs.value = !state.value.timelineRunning && state.value.questionAudioEndEpochMs
    ? Math.max(0, state.value.questionAudioEndEpochMs - Date.now())
    : null
  if (state.value.status === 'FINISHED') {
    try {
      finalLeaderboard.value = await getLeaderboard(activity.eventId)
    } catch {
      finalLeaderboard.value = state.value.leaderboard || []
    }
  }
}

function chooseOption(index) {
  if (!canSubmit.value) return
  selected.value = index
}

async function handleSubmit() {
  if (selected.value === null || submitting.value || !canSubmit.value) return
  submitting.value = true
  try {
    await submitAnswer(activity.eventId, {
      playerId: activity.playerId,
      questionId: question.value.id,
      optionIndex: selected.value,
    })
    await refreshState()
    message.success('答案已提交')
  } catch (err) {
    const text = err.response?.data?.message || '提交失败，请稍后再试'
    await refreshState()
    activity.error = text
    message.error(text)
  } finally {
    submitting.value = false
  }
}

function leave() {
  activity.clearOfflinePlayer()
  router.push({ path: '/offline', query: { event: activity.eventId } })
}
</script>

<template>
  <section v-if="state" class="play-layout" :class="{ solo: !isFinished }">
    <div class="play-main">
      <div class="topline">
        <div>
          <div class="online-title-row">
            <h1>{{ state.eventName || activity.eventName }}</h1>
            <a-tag color="processing" class="online-mode-tag">线下模式</a-tag>
          </div>
          <p v-if="state.roundName" class="muted">{{ state.roundName }}</p>
        </div>
        <a-button title="退出本次参与" @click="leave">
          <LogOut :size="16" />退出
        </a-button>
      </div>

      <div class="progress-row">
        <span>第 {{ displayQuestionNo }} / {{ state.totalQuestions }} 题</span>
        <a-progress :percent="progressPercent" :show-info="false" :stroke-color="ACCENT" size="small" />
        <span>{{ activity.nickname }}</span>
      </div>

      <a-card v-if="state.status === 'WAITING'" class="state-card">
        <div class="state-center">
          <div class="waiting-mark"><Radio :size="28" /></div>
          <a-tag class="waiting-badge" color="processing">等待题目上屏...</a-tag>
          <span class="section-kicker" lang="ja">開始を待っています</span>
          <h2 v-if="state.currentQuestionIndex >= 0" class="panel-title">下一题：第 {{ displayQuestionNo + 1 }} 题</h2>
          <h2 v-else class="panel-title">等待活动开始</h2>
          <p class="muted waiting-meta">已登记昵称：{{ activity.nickname }} · 当前在线 {{ state.playerCount }} 人</p>
        </div>
      </a-card>

      <a-card v-else-if="question && !isFinished" class="question-card">
        <div v-if="state.questionAudioEndEpochMs" class="countdown-track">
          <div class="countdown-fill" :style="{ width: `${audioPercent}%` }"></div>
        </div>
        <span class="section-kicker" lang="ja">{{ isLocked ? '回答を締め切りました' : isRevealing ? '答え合わせ' : '問題に挑戦' }}</span>
        <h2 class="question-title">{{ question.prompt }}</h2>
        <p v-if="question.author" class="author-line">出题人：{{ question.author }}</p>
        <div class="question-heading">
          <a-space :size="6">
            <a-tag color="orangered">{{ question.type === 'ATMOSPHERE' ? '氛围题' : '作品题' }}</a-tag>
            <a-tag v-if="difficultyLabel">{{ difficultyLabel }}</a-tag>
          </a-space>
          <div class="timer" :class="{ urgent: audioSecondsLeft <= 5 && isAnswering }">
            <Clock3 :size="17" />
            {{ isLocked ? '已锁卷' : isRevealing ? '答案揭晓' : state.questionAudioEndEpochMs ? `音频剩余 ${audioSecondsLeft} 秒` : '答题中' }}
          </div>
        </div>
        <div class="option-grid">
          <button
            v-for="(option, index) in question.options"
            :key="option"
            class="option-button"
            :class="{
              selected: selected === index,
              correct: isRevealing && question.answerIndex === index,
              wrong: isRevealing && selected === index && question.answerIndex !== index,
            }"
            :disabled="!canSubmit"
            @click="chooseOption(index)"
          >
            <span class="option-letter">{{ String.fromCharCode(65 + index) }}</span>
            <span>{{ option }}</span>
            <Check v-if="isRevealing && question.answerIndex === index" :size="18" />
          </button>
        </div>
        <div class="question-actions">
          <span v-if="isLocked" class="submitted-copy">答题已结束，等待答案揭晓</span>
          <span v-else-if="isRevealing" class="submitted-copy">{{ selected === null ? '本题未作答' : `你选择了 ${selectedLetter}` }}</span>
          <span v-else-if="!state.hasAnswered && !state.canAnswer" class="spectator-copy"><Eye :size="16" /> 本题无法提交</span>
          <span v-else-if="!state.hasAnswered" class="muted">请选择一个你认为合适的答案</span>
          <a-button
            type="primary"
            size="large"
            :disabled="selected === null || !canSubmit"
            :loading="submitting"
            @click="handleSubmit"
          >
            确认答案
            <Send :size="16" />
          </a-button>
        </div>
        <a-alert v-if="isRevealing && (question.workName || correctLetter)" class="explanation-alert" type="info" show-icon>
          <template #message>
            <strong v-if="correctLetter">正确答案：{{ correctLetter }}<span v-if="question.workName"> · {{ question.workName }}</span></strong>
            <strong v-else>{{ question.workName }}</strong>
          </template>
        </a-alert>
        <div v-if="hintText" class="question-hint">
          <span class="question-hint-label">提示</span>
          <span>{{ hintText }}</span>
        </div>
      </a-card>

      <a-card v-else-if="isFinished" class="state-card">
        <span class="section-kicker" lang="ja">結果発表</span>
        <a-result title="本轮挑战完成" sub-title="感谢参与，最终成绩如下。">
          <template #icon><Trophy :size="28" style="color: var(--accent)" /></template>
          <template #extra>
            <div class="final-result">
              <a-statistic title="最终得分" :value="state.playerScore" :value-style="{ color: 'var(--accent)', fontSize: '42px' }" />
              <p class="muted">答错 <strong>{{ state.playerWrongCount }}</strong> 题</p>
              <p class="muted">总用时：<strong>{{ (state.playerDurationMs / 1000).toFixed(1) }}</strong> 秒 · 最终排名：第 <strong>{{ state.playerRank || '-' }}</strong> 名</p>
              <div class="scoring-rules">
                <span class="scoring-rules-label">计分规则</span>
                <p>简单题 <strong>2</strong> 分 · 中等题 <strong>3</strong> 分 · 困难题 <strong>5</strong> 分；答错与未作答均为 0 分。</p>
              </div>
            </div>
          </template>
        </a-result>
      </a-card>

      <a-alert v-if="activity.error" class="form-alert" type="error" :message="activity.error" show-icon />
    </div>

    <aside v-if="isFinished" class="score-panel">
      <div class="score-summary">
        <span class="muted">我的分数</span>
        <strong>{{ state.playerScore }}</strong>
        <span class="rank-label">用时 {{ (state.playerDurationMs / 1000).toFixed(1) }}s · 最终第 {{ state.playerRank || '-' }} 名</span>
      </div>
      <div class="leaderboard-heading">
        <span><Trophy :size="17" /> 本轮排行榜</span>
        <span class="muted">{{ state.playerCount }} 人完成</span>
      </div>
      <a-list :data-source="finalLeaderboard" size="small" :split="true" class="leaderboard-ant-list">
        <template #renderItem="{ item }">
          <a-list-item class="leader-row" :class="{ me: item.playerId === activity.playerId }">
            <span class="rank-number">{{ item.rank }}</span>
            <span class="leader-name">
              {{ item.nickname }}
              <a-tag v-if="item.playerId === activity.playerId" class="me-tag">你</a-tag>
            </span>
            <span class="leader-time muted">{{ (item.durationMs / 1000).toFixed(1) }}s</span>
            <span class="leader-score"><strong>{{ item.score }}</strong> 分</span>
          </a-list-item>
        </template>
        <template #empty>
          <a-empty description="等待大家入场" :image-style="{ height: '48px' }" />
        </template>
      </a-list>
    </aside>
  </section>
</template>

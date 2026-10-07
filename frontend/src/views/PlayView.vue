<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, Clock3, Eye, Lock, LogOut, Radio, Send, Trophy } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { submitAnswer } from '../services/api'
import { createActivitySocket } from '../services/socket'
import { useActivityStore } from '../stores/activity'

const router = useRouter()
const store = useActivityStore()
const selected = ref(null)
const submitting = ref(false)
const socket = ref(null)
const now = ref(Date.now())
let timer

const state = computed(() => store.state)
const question = computed(() => state.value?.question)
const isAnswering = computed(() => state.value?.status === 'ANSWERING')
const isRevealing = computed(() => state.value?.status === 'ANSWER_REVEALED')
const isFinished = computed(() => state.value?.status === 'FINISHED')
const isSpectator = computed(() => state.value && !state.value.canAnswer)
const secondsLeft = computed(() => {
  if (!state.value?.deadlineEpochMs) return 0
  return Math.max(0, Math.ceil((state.value.deadlineEpochMs - now.value) / 1000))
})
const countdownPercent = computed(() => {
  if (!isAnswering.value || !state.value?.deadlineEpochMs) return 0
  return Math.max(0, Math.min(100, ((state.value.deadlineEpochMs - now.value) / 20000) * 100))
})
const selectedLetter = computed(() => selected.value === null ? '' : String.fromCharCode(65 + selected.value))
const displayQuestionNo = computed(() => Math.max(0, (state.value?.currentQuestionIndex ?? -1) + 1))
const progressPercent = computed(() => {
  if (!state.value?.totalQuestions) return 0
  return Math.round((displayQuestionNo.value / state.value.totalQuestions) * 100)
})

onMounted(async () => {
  if (!store.isParticipant) {
    await router.replace('/')
    return
  }
  await store.refresh()
  socket.value = createActivitySocket(store.eventId, async () => {
    const previousQuestionId = question.value?.id
    await store.refresh()
    if (store.state?.question?.id !== previousQuestionId) {
      selected.value = null
    }
    if (store.state?.hasAnswered) {
      selected.value = store.state.selectedOption
    }
  })
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 250)
})

onBeforeUnmount(() => {
  socket.value?.deactivate()
  window.clearInterval(timer)
})

function chooseOption(index) {
  if (!isAnswering.value || isSpectator.value || state.value.hasAnswered || secondsLeft.value <= 0) return
  selected.value = index
}

async function handleSubmit() {
  if (selected.value === null || submitting.value || isSpectator.value || state.value.hasAnswered) return
  submitting.value = true
  try {
    await submitAnswer(store.eventId, store.playerId, question.value.id, selected.value)
    await store.refresh()
    message.success('答案已提交')
  } catch (err) {
    const text = err.response?.data?.message || '提交失败，请稍后再试'
    store.error = text
    message.error(text)
  } finally {
    submitting.value = false
  }
}

function leave() {
  store.clearPlayer()
  router.push('/')
}
</script>

<template>
  <section v-if="state" class="play-layout">
    <div class="play-main">
      <div class="topline">
        <div>
          <h1>{{ state.eventName }}</h1>
        </div>
        <a-button title="退出本次参与" @click="leave">
          <LogOut :size="16" />
          退出
        </a-button>
      </div>

      <div class="progress-row">
        <span>第 {{ displayQuestionNo }} / {{ state.totalQuestions }} 题</span>
        <a-progress
          :percent="progressPercent"
          :show-info="false"
          :stroke-color="'#ec5c3c'"
          size="small"
        />
        <span>{{ store.nickname }}</span>
      </div>

      <a-card v-if="state.status === 'WAITING'" class="state-card">
        <div class="state-center">
          <div class="waiting-mark"><Radio :size="28" /></div>
          <a-tag class="waiting-badge" color="processing">等待主持人发题...</a-tag>
          <h2 class="panel-title">{{ state.currentQuestionIndex >= 0 ? `下一题：第 ${displayQuestionNo} 题` : '耳朵准备好了吗？' }}</h2>
          <p class="muted">现场音响播放旋律后，主持人会开启答题。保持这个页面打开即可。</p>
          <p class="muted waiting-meta">已登记昵称：{{ store.nickname }} · 当前在线 {{ state.playerCount }} 人</p>
        </div>
      </a-card>

      <a-card v-else-if="question && !isFinished" class="question-card">
        <div v-if="isAnswering && countdownPercent > 0" class="countdown-track">
          <div class="countdown-fill" :style="{ width: `${countdownPercent}%` }"></div>
        </div>
        <div class="question-heading">
          <a-space :size="6">
            <a-tag v-if="question.audioCode" color="purple">音频 {{ question.audioCode }}</a-tag>
            <a-tag color="orangered">{{ question.type === 'ATMOSPHERE' ? '氛围题' : '作品题' }}</a-tag>
            <a-tag>{{ question.difficulty === 'EASY' ? '简单' : question.difficulty === 'MEDIUM' ? '中等' : '困难' }}</a-tag>
          </a-space>
          <div class="timer" :class="{ urgent: secondsLeft <= 5 && isAnswering }">
            <Clock3 :size="17" />
            {{ isAnswering ? `${secondsLeft}s` : isRevealing ? '揭晓中' : '已结束' }}
          </div>
        </div>
        <h2 class="question-title">{{ question.prompt }}</h2>
        <div class="option-grid">
          <button
            v-for="(option, index) in question.options"
            :key="option"
            class="option-button"
            :class="{
              selected: selected === index,
              correct: state.answerRevealed && question.answerIndex === index,
              wrong: state.answerRevealed && selected === index && question.answerIndex !== index,
            }"
            :disabled="!isAnswering || state.hasAnswered || secondsLeft <= 0"
            @click="chooseOption(index)"
          >
            <span class="option-letter">{{ String.fromCharCode(65 + index) }}</span>
            <span>{{ option }}</span>
            <Check v-if="isRevealing && question.answerIndex === index" :size="18" />
          </button>
        </div>
        <div class="question-actions">
          <span v-if="state.hasAnswered" class="submitted-copy"><Check :size="16" /> 已提交 {{ selectedLetter }}，等待结果</span>
          <span v-else-if="isSpectator" class="spectator-copy"><Eye :size="16" /> 你在答题开始后加入，本轮只能观看</span>
          <span v-else-if="isRevealing" class="submitted-copy">
            {{ state.answerRevealed ? (selected === null ? '本题未作答' : `你选择了 ${selectedLetter}`) : '答题已结束' }}
            <small v-if="state.autoMode" style="margin-left: 6px; opacity: 0.85;">(即将自动切题)</small>
          </span>
          <span v-else class="muted">请选择一个你认为合适的答案</span>
          <a-button
            type="primary"
            size="large"
            :disabled="selected === null || isSpectator || state.hasAnswered || !isAnswering || secondsLeft <= 0"
            :loading="submitting"
            @click="handleSubmit"
          >
            确认答案
            <Send :size="16" />
          </a-button>
        </div>
        <a-alert
          v-if="state.answerRevealed && question.analysis"
          class="explanation-alert"
          type="info"
          show-icon
        >
          <template #message><strong>{{ question.workName }}</strong></template>
          <template #description>{{ question.analysis }}</template>
        </a-alert>
      </a-card>

      <a-card v-else-if="isFinished" class="state-card">
        <a-result title="本轮挑战完成" sub-title="感谢参与，最终成绩如下。">
          <template #icon><Trophy :size="28" style="color: #ec5c3c" /></template>
          <template #extra>
            <div class="final-result">
              <a-statistic title="最终得分" :value="state.playerScore" :value-style="{ color: '#ec5c3c', fontSize: '42px' }" />
              <p class="muted">总用时：<strong>{{ (state.playerDurationMs / 1000).toFixed(1) }}</strong> 秒 · 最终排名：第 <strong>{{ state.playerRank || '-' }}</strong> 名</p>
            </div>
          </template>
        </a-result>
      </a-card>

      <a-alert v-if="store.error" class="form-alert" type="error" :message="store.error" show-icon />
    </div>

    <aside class="score-panel">
      <template v-if="isFinished">
        <div class="score-summary">
          <span class="muted">我的分数</span>
          <strong>{{ state.playerScore }}</strong>
          <span class="rank-label">用时 {{ (state.playerDurationMs / 1000).toFixed(1) }}s · 最终第 {{ state.playerRank || '-' }} 名</span>
        </div>
        <div class="leaderboard-heading">
          <span><Trophy :size="17" /> 最终排行榜</span>
          <span class="muted">{{ state.playerCount }} 人完成</span>
        </div>
        <a-list
          :data-source="state.leaderboard"
          size="small"
          :split="true"
          class="leaderboard-ant-list"
        >
          <template #renderItem="{ item }">
            <a-list-item class="leader-row" :class="{ me: item.playerId === store.playerId }">
              <span class="rank-number">{{ item.rank }}</span>
              <span class="leader-name">
                {{ item.nickname }}
                <a-tag v-if="item.playerId === store.playerId" class="me-tag" color="orangered">你</a-tag>
              </span>
              <span class="leader-time muted">{{ (item.durationMs / 1000).toFixed(1) }}s</span>
              <span class="leader-score"><strong>{{ item.score }}</strong> 分</span>
            </a-list-item>
          </template>
          <template #empty>
            <a-empty description="等待大家入场" :image-style="{ height: '48px' }" />
          </template>
        </a-list>
      </template>
      <div v-else class="ranking-hidden">
        <span class="ranking-lock"><Lock :size="18" /></span>
        <p>答题过程中不展示当前分数，大家的分数会在最后一题结束后一起揭晓。</p>
      </div>
    </aside>
  </section>
</template>

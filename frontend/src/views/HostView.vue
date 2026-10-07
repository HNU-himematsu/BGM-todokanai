<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Check,
  ChevronRight,
  CircleStop,
  Copy,
  Eye,
  KeyRound,
  ListRestart,
  LockKeyhole,
  LogOut,
  Pause,
  Play,
  Radio,
  RefreshCw,
  UsersRound,
} from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import QRCode from 'qrcode'
import { getHostState, hostAction, hostLogin, hostLogout } from '../services/api'
import { createActivitySocket } from '../services/socket'

const eventId = 'demo'
const hostToken = ref(sessionStorage.getItem('bgm-host-token') || '')
const authenticated = ref(Boolean(hostToken.value))
const password = ref('')
const state = ref(null)
const players = ref([])
const error = ref('')
const busy = ref(false)
const socket = ref(null)
const qrCanvas = ref(null)

const question = computed(() => state.value?.question)
const publicBaseUrl = (import.meta.env.VITE_PUBLIC_BASE_URL || window.location.origin).replace(/\/+$/, '')
const joinUrl = computed(() => `${publicBaseUrl}/?event=${eventId}`)
const summaryTxtUrl = computed(() => `${publicBaseUrl}/api/events/${eventId}/leaderboard/summary-txt`)
const displayQuestionNo = computed(() => Math.max(0, (state.value?.currentQuestionIndex ?? -1) + 1))
const statusLabel = computed(() => ({
  WAITING: '等待开始',
  ANSWERING: '答题中',
  ANSWER_REVEALED: '答案揭晓',
  FINISHED: '活动结束',
}[state.value?.status] || '连接中'))
const statusColor = computed(() => ({
  WAITING: 'default',
  ANSWERING: 'processing',
  ANSWER_REVEALED: 'warning',
  FINISHED: 'success',
}[state.value?.status] || 'default'))
const emptyPreviewTitle = computed(() => state.value?.status === 'FINISHED' ? '本轮活动已结束' : '活动尚未开始')
const emptyPreviewCopy = computed(() => state.value?.status === 'FINISHED'
  ? '点击重置活动后，可以开始下一轮。'
  : '开始第一题后，这里会显示主持人视角的题目内容。')

async function toggleAutoMode(checked) {
  await action('toggle-auto', { enabled: checked })
}

onMounted(async () => {
  if (authenticated.value) {
    await openConsole()
  }
})

onBeforeUnmount(() => socket.value?.deactivate())

async function openConsole() {
  await refresh()
  await drawJoinQr()
  if (!socket.value) {
    socket.value = createActivitySocket(eventId, refresh)
  }
}

async function refresh() {
  try {
    const snapshot = await getHostState(eventId, hostToken.value)
    state.value = snapshot?.state ?? null
    players.value = snapshot?.players ?? []
    error.value = ''
  } catch {
    error.value = '无法连接后端，请确认 Spring Boot 已启动'
  }
}

async function login() {
  if (!password.value.trim() || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await hostLogin(password.value.trim())
    hostToken.value = result.token
    sessionStorage.setItem('bgm-host-token', result.token)
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
  const token = hostToken.value
  try {
    await hostLogout(token)
  } catch {
    // Clear the local session even if the server already forgot this token.
  }
  socket.value?.deactivate()
  socket.value = null
  hostToken.value = ''
  sessionStorage.removeItem('bgm-host-token')
  authenticated.value = false
  state.value = null
  players.value = []
  password.value = ''
}

async function action(name, params = {}) {
  busy.value = true
  error.value = ''
  try {
    await hostAction(eventId, name, hostToken.value, params)
    await refresh()
  } catch (err) {
    const text = err.response?.data?.message || '操作失败'
    if (text.includes('主持人登录') || err.response?.status === 401) {
      await logout()
      error.value = '主持人登录已失效，请重新输入密码'
    } else {
      error.value = text
    }
  } finally {
    busy.value = false
  }
}

async function drawJoinQr() {
  await nextTick()
  if (qrCanvas.value) {
    await QRCode.toCanvas(qrCanvas.value, joinUrl.value, {
      width: 156,
      margin: 1,
      color: { dark: '#171717', light: '#ffffff' },
    })
  }
}

async function copyJoinUrl() {
  try {
    await navigator.clipboard.writeText(joinUrl.value)
    message.success('已复制加入地址')
  } catch {
    error.value = '复制失败，请手动复制加入地址'
  }
}
</script>

<template>
  <section v-if="!authenticated" class="host-login-page">
    <a-card class="host-login-panel">
      <div class="state-center">
        <div class="login-mark"><LockKeyhole :size="25" /></div>
        <h1 class="panel-title-lg">主持人登录</h1>
        <p class="muted">请输入主持人密码进入活动控制台。参与者无需输入密码。</p>
      </div>
      <form @submit.prevent="login">
        <div class="field-label">主持人密码</div>
        <a-input-password
          v-model:value="password"
          size="large"
          placeholder="请输入密码"
          autocomplete="current-password"
        />
        <a-button
          class="full-width"
          type="primary"
          size="large"
          :disabled="!password.trim()"
          :loading="busy"
          @click="login"
        >
          <KeyRound :size="17" />
          {{ busy ? '验证中…' : '进入控制台' }}
        </a-button>
      </form>
      <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />
    </a-card>
  </section>

  <section v-else class="host-page">
    <div class="host-header">
      <div>
        <h1>主持人控制台</h1>
        <p class="muted">现场音响播放 BGM，主持人在这里推进题目和公布答案。</p>
      </div>
      <a-space class="host-stats" :size="22">
        <span class="stat-card"><UsersRound :size="17" /><strong>{{ state?.playerCount || 0 }}</strong><small>人在线</small></span>
        <a-tag v-if="state?.autoMode" color="cyan">自动轮转模式</a-tag>
        <a-badge :status="statusColor" :text="statusLabel" />
        <a-button size="small" title="退出主持人登录" @click="logout">
          <LogOut :size="15" />退出
        </a-button>
      </a-space>
    </div>

    <div v-if="state" class="host-grid">
      <a-card class="host-control" :bordered="true">
        <div class="section-title">
          <span>当前流程</span>
          <a-button size="small" title="刷新状态" @click="refresh"><RefreshCw :size="15" /></a-button>
        </div>
        <div class="host-progress">
          <a-statistic
            :value="displayQuestionNo"
            :value-style="{ color: '#ec5c3c', fontSize: '53px', fontWeight: 800, lineHeight: 1 }"
          />
          <div>
            <strong>/ {{ state.totalQuestions }} 题</strong>
            <span class="muted">每题答题时间 20 秒</span>
          </div>
        </div>
        <div style="margin-top: 14px; display: flex; align-items: center; justify-content: space-between;">
          <span class="muted" style="font-size: 12px;">自动轮转（20 秒答题 + 6 秒公布自动切题）</span>
          <a-switch :checked="Boolean(state.autoMode)" size="small" @change="toggleAutoMode" />
        </div>
        <div class="control-actions">
          <a-button v-if="state.status === 'WAITING'" type="primary" size="large" :loading="busy" @click="action('start')">
            <Play :size="18" /> {{ state.autoMode ? '一键开始轮转 (自动)' : '开始本题' }}
          </a-button>
          <template v-if="state.status === 'ANSWERING'">
            <a-button v-if="state.autoMode" type="default" size="large" :loading="busy" @click="action('pause')">
              <Pause :size="18" /> 暂停轮转
            </a-button>
            <a-button v-else type="primary" size="large" :loading="busy" @click="action('resume')">
              <Play :size="18" /> 恢复自动
            </a-button>
            <a-button type="primary" danger size="large" :loading="busy" @click="action('end')">
              <CircleStop :size="18" /> {{ state.autoMode ? '提前公布答案' : '结束答题' }}
            </a-button>
          </template>
          <template v-if="state.status === 'ANSWER_REVEALED'">
            <a-button v-if="state.autoMode" type="default" size="large" :loading="busy" @click="action('pause')">
              <Pause :size="18" /> 暂停轮转
            </a-button>
            <a-button v-else type="primary" size="large" :loading="busy" @click="action('resume')">
              <Play :size="18" /> 恢复自动
            </a-button>
            <a-button size="large" :loading="busy" @click="action('next')">
              {{ state.currentQuestionIndex === state.totalQuestions - 1 ? '结束本轮' : '立即下一题' }}
              <ChevronRight :size="18" />
            </a-button>
          </template>
          <a-button v-if="state.status === 'FINISHED'" type="primary" size="large" :loading="busy" @click="action('reset')">
            <ListRestart :size="18" /> 重置活动
          </a-button>
        </div>
        <div class="audio-board">
          <div class="audio-board-main">
            <span class="audio-board-label"><Radio :size="16" /> 当前放音</span>
            <strong class="audio-board-code">{{ question?.audioCode || '待开始' }}</strong>
          </div>
          <p class="audio-board-meta">
            建议播放区间 15~25 秒
            <template v-if="question?.workName"> · 作品提示：{{ question.workName }}</template>
          </p>
        </div>
      </a-card>

      <a-card class="host-join" :bordered="true">
        <div class="host-join-body">
          <div>
            <div class="section-title">
              <span>扫码加入</span>
              <span class="muted">活动房间：{{ eventId }}</span>
            </div>
            <p class="muted">把这个二维码展示给现场参与者，大家扫码后输入昵称即可加入本轮活动。</p>
            <a-button @click="copyJoinUrl">
              <Copy :size="16" /> 复制加入地址
            </a-button>
          </div>
          <canvas ref="qrCanvas" class="qr-code" aria-label="活动加入二维码"></canvas>
        </div>
      </a-card>

      <a-card class="host-preview" :bordered="true">
        <div class="section-title">
          <span>题目预览</span>
          <a-space :size="6">
            <a-tag v-if="question?.audioCode" color="purple">音频：{{ question.audioCode }}</a-tag>
            <a-tag v-if="question" color="orangered">{{ question.type === 'ATMOSPHERE' ? '氛围题' : '作品题' }}</a-tag>
          </a-space>
        </div>
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
          <a-alert
            v-if="state.answerRevealed && question.answerIndex !== null"
            class="explanation-alert"
            type="success"
            show-icon
            :message="`正确答案：${String.fromCharCode(65 + question.answerIndex)} · ${question.workName}`"
          />
        </template>
        <a-empty v-else class="empty-preview" :description="emptyPreviewTitle">
          <template #image>
            <span class="empty-icon">♪</span>
          </template>
          <p class="muted">{{ emptyPreviewCopy }}</p>
        </a-empty>
      </a-card>

      <a-card class="host-players" :bordered="true">
        <div class="section-title">
          <span>现场作答进度</span>
          <span class="muted">已作答 {{ state.answeredCount || 0 }} / {{ state.playerCount || 0 }} 人</span>
        </div>
        <div v-if="players.length" class="player-wall">
          <div
            v-for="player in players"
            :key="player.playerId"
            class="player-card"
            :class="{ 'is-answered': player.answered, 'is-spectator': player.spectator }"
          >
            <span class="player-name">{{ player.nickname }}</span>
            <Check v-if="player.answered" :size="14" />
            <Eye v-else-if="player.spectator" :size="13" />
          </div>
        </div>
        <a-empty v-else description="还没有玩家在线" :image-style="{ height: '48px' }" />
      </a-card>

      <a-card class="host-leaderboard" :bordered="true">
        <div class="section-title">
          <span>{{ state.status === 'FINISHED' ? '最终排行榜' : '排行榜' }}</span>
          <div class="host-leader-header-right">
            <a-button
              v-if="state.status === 'FINISHED'"
              size="small"
              type="link"
              :href="summaryTxtUrl"
              target="_blank"
              class="txt-export-btn"
            >
              下载 / 查看 TXT 榜单
            </a-button>
            <span class="muted">{{ state.status === 'FINISHED' ? '本轮成绩已保存' : '活动结束后统一公布' }}</span>
          </div>
        </div>
        <a-list
          v-if="state.status === 'FINISHED'"
          :data-source="state.leaderboard"
          :grid="{ gutter: 14, xs: 1, sm: 2, md: 2, lg: 3, xl: 3 }"
          row-key="playerId"
          class="host-leaderboard-list"
        >
          <template #renderItem="{ item }">
            <a-list-item class="host-leader-item">
              <div class="host-leader-card">
                <div class="host-leader-rank" :class="`rank-${item.rank}`">
                  {{ item.rank }}
                </div>
                <div class="host-leader-body">
                  <div class="host-leader-name" :title="item.nickname">
                    {{ item.nickname }}
                  </div>
                  <div class="host-leader-time">
                    耗时 {{ (item.durationMs / 1000).toFixed(1) }}s
                  </div>
                </div>
                <div class="host-leader-score">
                  <span class="score-value">{{ item.score }}</span>
                  <span class="score-unit">分</span>
                </div>
              </div>
            </a-list-item>
          </template>
          <template #empty>
            <a-empty description="本轮还没有完成答题" />
          </template>
        </a-list>
        <div v-else class="ranking-hidden host-ranking-hidden">
          <span class="ranking-lock">?</span>
          <p>所有人的分数会在最后一题结束后一起揭晓。</p>
        </div>
      </a-card>
    </div>

    <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />
  </section>
</template>

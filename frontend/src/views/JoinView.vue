<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowRight, Clock3, Music, Radio, Trophy } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { getEvent } from '../services/api'
import { MODES, parseModeQuery } from '../services/storage'
import { useActivityStore } from '../stores/activity'

const router = useRouter()
const route = useRoute()
const store = useActivityStore()
const nickname = ref(store.nickname)
const loading = ref(false)
const eventLoading = ref(false)
const error = ref('')
const rulesVisible = ref(false)
const eventSummary = ref(null)

const modeQuery = computed(() => parseModeQuery(route.query.mode))
const eventId = computed(() => String(route.query.event || 'demo').trim() || 'demo')
const routeMode = computed(() => {
  if (route.path === '/offline') return MODES.OFFLINE
  if (route.path === '/online') return MODES.ONLINE
  return modeQuery.value.mode
})
const modeInvalid = computed(() => route.path === '/' && modeQuery.value.invalid)
const isOffline = computed(() => routeMode.value === MODES.OFFLINE)
const eventStatus = computed(() => eventSummary.value?.status || '')
const offlineClosed = computed(() => isOffline.value && eventStatus.value === 'FINISHED')
const canJoin = computed(() => nickname.value.trim().length >= 1
  && nickname.value.trim().length <= 20
  && !loading.value
  && !offlineClosed.value)
const statusCopy = computed(() => {
  if (!isOffline.value) return '线上答题在音频开始后自动出题'
  if (offlineClosed.value) return '本轮已结束，请等待主持人切换下一轮'
  if (eventStatus.value === 'ANSWERING') return '本轮答题进行中，仍可加入'
  return '等待开始，可加入本轮'
})

watch([eventId, routeMode], async ([nextEventId, nextMode]) => {
  store.setSession(nextEventId, nextMode)
  nickname.value = store.nickname
  await loadEventSummary()
}, { immediate: true })

async function loadEventSummary() {
  eventLoading.value = true
  try {
    eventSummary.value = await getEvent(eventId.value)
    store.eventName = eventSummary.value?.name || store.eventName
  } catch {
    eventSummary.value = null
  } finally {
    eventLoading.value = false
  }
}

function handleOpenRules() {
  if (!canJoin.value) return
  error.value = ''
  rulesVisible.value = true
}

async function handleConfirmJoin() {
  if (!canJoin.value) return
  loading.value = true
  error.value = ''
  try {
    store.nickname = nickname.value.trim()
    if (isOffline.value) await store.joinOffline()
    else await store.joinOnline()
    rulesVisible.value = false
    await router.push({
      path: '/play',
      query: {
        event: eventId.value,
        mode: isOffline.value ? 'offline' : 'online',
      },
    })
  } catch (err) {
    const text = err.response?.data?.message || '活动暂时无法连接，请稍后再试'
    error.value = text
    message.error(text)
  } finally {
    loading.value = false
  }
}

function switchToOnline() {
  router.push({ path: '/online', query: { event: eventId.value } })
}
</script>

<template>
  <section class="join-layout">
    <div class="join-copy">
      <span class="title-kicker" lang="ja">届かないメロディー</span>
      <h1><span class="title-line">旮旯原声之</span><br /><span class="title-line">届不到的</span><br /><span class="title-line"><em>BGM</em></span></h1>
    </div>

    <a-card class="join-panel" :bordered="true">
      <div class="mode-badge-row">
        <a-tag :color="isOffline ? 'processing' : 'default'" class="mode-badge">
          {{ isOffline ? '线下模式' : '线上模式' }}
        </a-tag>
        <span class="mode-event">{{ eventSummary?.name || eventId }}</span>
      </div>
      <div class="panel-kicker">准备入场</div>
      <h2 class="panel-title">先给自己起个名字</h2>
      <p class="muted">最终排行榜会显示这个昵称。别害羞，名字越有记忆点越好。</p>
      <a-alert
        v-if="modeInvalid"
        class="form-alert"
        type="warning"
        message="模式参数无效，已进入线上模式"
        show-icon
      />
      <form @submit.prevent="handleOpenRules">
        <div class="field-label">你的昵称</div>
        <a-input
          v-model:value="nickname"
          size="large"
          :maxlength="20"
          placeholder="例如：雨夜听歌人"
          autocomplete="nickname"
        />
        <a-button
          class="full-width"
          type="primary"
          size="large"
          html-type="submit"
          :disabled="!canJoin"
          :loading="loading || eventLoading"
        >
          {{ isOffline ? '加入本轮' : '进入活动' }}
          <ArrowRight :size="18" />
        </a-button>
      </form>
      <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />
      <div class="join-note">
        <span class="status-dot" :class="{ offline: isOffline }"></span>
        <span>{{ statusCopy }}</span>
      </div>
      <p v-if="!isOffline" class="join-mode-hint">现场参与者请扫描主持人展示的二维码进入线下模式。</p>
      <div v-if="store.onlineLocked && !isOffline" class="locked-entry-copy">
        当前身份已锁定线上成绩 {{ store.onlineScore }} 分，进入后可查看榜单名次。
      </div>
      <a-button v-if="isOffline" class="mode-switch" type="link" @click="switchToOnline">
        进入线上模式
        <ArrowRight :size="15" />
      </a-button>
    </a-card>

    <a-modal
      v-model:open="rulesVisible"
      :footer="null"
      :centered="true"
      class="rules-modal"
      wrap-class-name="rules-modal-wrap"
    >
      <div class="rules-modal-header">
        <div class="rules-modal-kicker">规则须知</div>
        <h3 class="rules-modal-title">{{ isOffline ? '线下答题规则说明' : '线上答题规则说明' }}</h3>
      </div>
      <div class="rules-modal-body">
        <p class="rules-intro">
          {{ isOffline ? '欢迎参与旮旯原声猜歌互动，请在作答前了解现场流程：' : '欢迎参与旮旯原声线上猜歌，请在作答前了解答题流程：' }}
        </p>
        <template v-if="isOffline">
          <div class="rule-item">
            <div class="rule-icon"><Music :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">1. 等待音乐播放</div>
              <p class="rule-desc">主持人控制台播放本轮音频，现场音响输出旋律片段，请留意旋律细节与曲风特征。</p>
            </div>
          </div>
          <div class="rule-item">
            <div class="rule-icon"><Radio :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">2. 题目上屏</div>
              <p class="rule-desc">时间轴到达题目上屏节点或主持人手动推进后，手机端同步呈现题目与四个选项。</p>
            </div>
          </div>
          <div class="rule-item">
            <div class="rule-icon"><Clock3 :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">3. 限时作答提交</div>
              <p class="rule-desc">每题的作答时间由现场编排决定，请在锁卷前选定选项并点击“确认答案”提交。</p>
            </div>
          </div>
          <div class="rule-item">
            <div class="rule-icon"><Trophy :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">4. 即时揭晓与结算</div>
              <p class="rule-desc">揭晓节点公布正确答案与解析；结算节点生成个人总分与本轮排行榜。</p>
            </div>
          </div>
        </template>
        <template v-else>
          <div class="rule-item">
            <div class="rule-icon"><Music :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">1. 连续播放音频</div>
              <p class="rule-desc">开始后按题集顺序连续播放音频，题目随播放位置自动上屏；已选答案后可点击「下一题」按顺序推进，最后一题可结束答题，不能暂停、任意跳题或回看。</p>
            </div>
          </div>
          <div class="rule-item">
            <div class="rule-icon"><Clock3 :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">2. 每道题限时选择</div>
              <p class="rule-desc">题目开放期间可修改选项，结束时刻自动锁定；未作答题目按 0 分处理。</p>
            </div>
          </div>
          <div class="rule-item">
            <div class="rule-icon"><Trophy :size="20" /></div>
            <div class="rule-content">
              <div class="rule-title">3. 预览与锁定</div>
              <p class="rule-desc">全部音频结束后显示预览分。锁定后成绩进入线上榜与总榜，且不能重试。</p>
            </div>
          </div>
        </template>
      </div>
      <div class="rules-modal-footer">
        <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon style="margin-bottom: 12px;" />
        <a-button
          class="full-width"
          type="primary"
          size="large"
          :loading="loading"
          :disabled="!canJoin"
          @click="handleConfirmJoin"
        >
          {{ isOffline ? '我已了解，加入本轮' : '我已了解，开始答题' }}
          <ArrowRight :size="18" />
        </a-button>
      </div>
    </a-modal>
  </section>
</template>

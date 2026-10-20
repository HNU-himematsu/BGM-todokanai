<script setup>
import { computed } from 'vue'
import { LockKeyhole, RefreshCw, RotateCcw, Trophy } from 'lucide-vue-next'
import { message, Modal } from 'ant-design-vue'
import { useOnlineStore } from '../stores/online'

const emit = defineEmits(['restart'])
const online = useOnlineStore()

const isLocked = computed(() => online.phase === 'LOCKED')
const result = computed(() => isLocked.value ? online.lockResult : online.preview)
const score = computed(() => result.value?.score ?? '--')
const answered = computed(() => result.value?.answeredCount ?? online.answeredCount)
const total = computed(() => result.value?.totalQuestions ?? online.totalQuestions)
const duration = computed(() => result.value?.durationMs ?? online.activeDurationMs)

function formatDuration(ms) {
  const seconds = Math.max(0, Number(ms) || 0) / 1000
  return `${seconds.toFixed(1)} 秒`
}

function formatTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}

function confirmRetry() {
  Modal.confirm({
    title: '重试本次答题',
    content: '重试会清空本次答案并重新播放全部音频，当前预览分不会入榜。',
    okText: '确认重试',
    cancelText: '取消',
    onOk() {
      online.retryAttempt()
      emit('restart')
    },
  })
}

function confirmLock() {
  Modal.confirm({
    title: '锁定本次分数',
    content: '锁定后成绩进入线上积分榜与总榜，且不能再次重试。确认提交吗？',
    okText: '确认锁定',
    cancelText: '再检查一下',
    async onOk() {
      const locked = await online.lockScore()
      if (locked) message.success('成绩已锁定')
      else if (online.errorMessage) message.error(online.errorMessage)
    },
  })
}
</script>

<template>
  <a-card class="online-result-card">
    <span class="section-kicker" lang="ja">{{ isLocked ? '記録完了' : '結果発表' }}</span>
    <div class="online-result-heading">
      <div>
        <h2>{{ isLocked ? '线上成绩已锁定' : '本次答题结束' }}</h2>
        <p class="muted">
          {{ isLocked ? '该身份已写入线上积分榜，当前页面展示锁定结果。' : '本次成绩尚未进入榜单。锁定后不可重试。' }}
        </p>
      </div>
      <Trophy :size="30" class="result-trophy" />
    </div>

    <div class="online-result-score">
      <span>总分</span>
      <strong>{{ score }}</strong>
    </div>

    <div class="result-meta-grid">
      <div><span>已答题目</span><strong>{{ answered }} / {{ total }}</strong></div>
      <div><span>有效作答耗时</span><strong>{{ formatDuration(duration) }}</strong></div>
      <div v-if="isLocked"><span>线上榜名次</span><strong>第 {{ result?.onlineRank || '-' }} 名</strong></div>
      <div v-if="isLocked"><span>总榜名次</span><strong>第 {{ result?.totalRank || '-' }} 名</strong></div>
      <div v-if="isLocked" class="result-meta-wide"><span>锁定时间</span><strong>{{ formatTime(result?.lockedAt) }}</strong></div>
    </div>

    <a-alert
      v-if="!isLocked && online.previewing"
      class="form-alert"
      type="info"
      message="正在计算预览分..."
      show-icon
    />
    <a-alert
      v-else-if="!isLocked && online.previewError"
      class="form-alert"
      type="warning"
      :message="online.previewError"
      show-icon
    >
      <template #action>
        <a-button size="small" :loading="online.previewing" @click="online.previewScore()">
          <RefreshCw :size="14" />重新获取分数
        </a-button>
      </template>
    </a-alert>

    <div v-if="!isLocked" class="result-actions">
      <a-button size="large" @click="confirmRetry">
        <RotateCcw :size="17" />重试
      </a-button>
      <a-button type="primary" size="large" :disabled="!online.canLock" :loading="online.locking" @click="confirmLock">
        <LockKeyhole :size="17" />锁定分数
      </a-button>
    </div>

    <a-alert
      v-if="online.errorMessage"
      class="form-alert"
      type="error"
      :message="online.errorMessage"
      show-icon
    />
  </a-card>
</template>

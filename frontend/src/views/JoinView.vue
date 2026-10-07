<script setup>
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CircleHelp, UsersRound } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { getEvent, joinEvent } from '../services/api'
import { useActivityStore } from '../stores/activity'

const router = useRouter()
const route = useRoute()
const store = useActivityStore()
const eventId = computed(() => String(route.query.event || 'demo'))
const event = ref(null)
const nickname = ref(store.nickname)
const loading = ref(false)
const error = ref('')

const canJoin = computed(() => nickname.value.trim().length >= 1 && !loading.value)

onMounted(async () => {
  try {
    event.value = await getEvent(eventId.value)
  } catch {
    error.value = '活动暂时无法连接，请检查后端是否已启动'
  }
})

async function handleJoin() {
  if (!canJoin.value) return
  loading.value = true
  error.value = ''
  try {
    const player = await joinEvent(eventId.value, nickname.value.trim())
    store.setPlayer(player)
    await router.push('/play')
  } catch (err) {
    const text = err.response?.data?.message || '加入失败，请稍后再试'
    error.value = text
    message.error(text)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="join-layout">
    <div class="join-copy">
      <p class="eyebrow">BGM GUESSING SESSION / 01</p>
      <h1>旮旯原声之<br /><em>届不到的 BGM</em></h1>
      <p class="lead">听现场音响里的旋律，凭直觉选出它最合适的氛围，或者猜出它属于哪部作品。</p>
      <div class="event-meta">
        <a-space :size="20">
          <span><UsersRound :size="16" /> 多人同步答题</span>
          <span><CircleHelp :size="16" /> 共 {{ event?.totalQuestions || 6 }} 题</span>
        </a-space>
      </div>
    </div>

    <a-card class="join-panel" :bordered="true">
      <div class="panel-kicker">准备入场</div>
      <h2 class="panel-title">先给自己起个名字</h2>
      <p class="muted">最终排行榜会显示这个昵称。别害羞，名字越有记忆点越好。</p>
      <form @submit.prevent="handleJoin">
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
          :disabled="!nickname.trim()"
          :loading="loading"
          @click="handleJoin"
        >
          {{ loading ? '正在入场…' : '进入活动' }}
          <ArrowRight :size="18" />
        </a-button>
      </form>
      <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />
      <div class="join-note">
        <span class="status-dot"></span>
        <span>等待主持人开始后，所有人会同时进入答题</span>
      </div>
    </a-card>
  </section>
</template>

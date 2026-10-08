<script setup>
import { computed, ref } from 'vue'
import { ArrowRight, Clock3, Music, Radio, Trophy } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { useRoute, useRouter } from 'vue-router'
import { joinEvent } from '../services/api'
import { useActivityStore } from '../stores/activity'

const router = useRouter()
const route = useRoute()
const store = useActivityStore()
const eventId = computed(() => String(route.query.event || 'demo'))
const nickname = ref(store.nickname)
const loading = ref(false)
const error = ref('')
const rulesVisible = ref(false)

const canJoin = computed(() => nickname.value.trim().length >= 1 && !loading.value)

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
    const player = await joinEvent(eventId.value, nickname.value.trim())
    store.setPlayer(player)
    rulesVisible.value = false
    await router.push('/play')
  } catch (err) {
    const text = err.response?.data?.message || '活动暂时无法连接，请稍后再试'
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
      <span class="title-kicker" lang="ja">届かないメロディー</span>
      <h1><span class="title-line">旮旯原声之</span><br /><span class="title-line">届不到的</span><br /><span class="title-line"><em>BGM</em></span></h1>
    </div>

    <a-card class="join-panel" :bordered="true">
      <div class="panel-kicker">准备入场</div>
      <h2 class="panel-title">先给自己起个名字</h2>
      <p class="muted">最终排行榜会显示这个昵称。别害羞，名字越有记忆点越好。</p>
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
          :disabled="!nickname.trim()"
          :loading="loading"
          @click="handleOpenRules"
        >
          进入活动
          <ArrowRight :size="18" />
        </a-button>
      </form>
      <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon />
      <div class="join-note">
        <span class="status-dot"></span>
        <span>等待主持人开始后，所有人会同时进入答题</span>
      </div>
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
        <h3 class="rules-modal-title">答题规则说明</h3>
      </div>
      <div class="rules-modal-body">
        <p class="rules-intro">欢迎参与旮旯原声猜歌互动，请在作答前了解现场流程：</p>
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
      </div>
      <div class="rules-modal-footer">
        <a-alert v-if="error" class="form-alert" type="error" :message="error" show-icon style="margin-bottom: 12px;" />
        <a-button
          class="full-width"
          type="primary"
          size="large"
          :loading="loading"
          @click="handleConfirmJoin"
        >
          我已了解，进入活动
          <ArrowRight :size="18" />
        </a-button>
      </div>
    </a-modal>
  </section>
</template>

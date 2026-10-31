<script setup>
import { ref } from 'vue'
import { ChevronDown, ChevronUp } from 'lucide-vue-next'
import { useOnlineStore } from '../stores/online'

const online = useOnlineStore()
const dotsOpen = ref(true)
const dotsId = 'online-question-dots'

function toggleDots() {
  dotsOpen.value = !dotsOpen.value
}
</script>

<template>
  <a-card class="online-progress-card">
    <div
      class="online-progress-head"
      role="button"
      tabindex="0"
      :aria-expanded="dotsOpen"
      :aria-controls="dotsId"
      :aria-label="dotsOpen ? '收起题号' : '展开题号'"
      @click="toggleDots"
      @keydown.enter.prevent="toggleDots"
      @keydown.space.prevent="toggleDots"
    >
      <strong>第 {{ online.displayQuestionNumber }} / {{ online.totalQuestions }} 题</strong>
      <span class="online-progress-meta">
        <span class="muted">已答 {{ online.answeredCount }} · 未答 {{ online.unansweredCount }}</span>
        <span class="online-progress-toggle">
          <span class="online-progress-toggle-label">{{ dotsOpen ? '收起题号' : '展开题号' }}</span>
          <ChevronUp v-if="dotsOpen" :size="14" />
          <ChevronDown v-else :size="14" />
        </span>
      </span>
    </div>
    <a-progress
      :percent="online.progressPercent"
      :show-info="false"
      :stroke-color="'var(--accent)'"
      size="small"
    />
    <div v-show="dotsOpen" :id="dotsId" class="question-dots" aria-label="答题进度">
      <span
        v-for="question in online.questions"
        :key="question.id"
        class="question-dot"
        :class="{
          answered: Number.isInteger(online.answers[question.id]),
          current: question.index === online.currentIndex,
          locked: question.index < online.currentIndex,
        }"
        :title="`第 ${question.index + 1} 题`"
      >{{ question.index + 1 }}</span>
    </div>
  </a-card>
</template>

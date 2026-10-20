<script setup>
import { useOnlineStore } from '../stores/online'

const online = useOnlineStore()
</script>

<template>
  <a-card class="online-progress-card">
    <div class="online-progress-head">
      <strong>第 {{ online.displayQuestionNumber }} / {{ online.totalQuestions }} 题</strong>
      <span class="muted">已答 {{ online.answeredCount }} · 未答 {{ online.unansweredCount }}</span>
    </div>
    <a-progress
      :percent="online.progressPercent"
      :show-info="false"
      :stroke-color="'var(--accent)'"
      size="small"
    />
    <div class="question-dots" aria-label="答题进度">
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

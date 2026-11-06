import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  getOnlineQuestions,
  getOnlineStatus,
  lockOnlineScore,
  previewOnlineScore,
} from '../services/api'
import {
  clearOnlineProgress,
  createId,
  isLocalStorageAvailable,
  readOnlineProgress,
  saveOnlineProgress,
} from '../services/storage'

const PROGRESS_VERSION = 1

export const useOnlineStore = defineStore('online', () => {
  const phase = ref('IDLE')
  const questionSet = ref(null)
  const attemptId = ref('')
  const answers = ref({})
  const currentIndex = ref(-1)
  const audioPositionMs = ref(0)
  const activeDurationMs = ref(0)
  const questionRemainingMs = ref(0)
  const preview = ref(null)
  const lockResult = ref(null)
  const status = ref(null)
  const visiblePause = ref(false)
  const errorMessage = ref('')
  const previewError = ref('')
  const storageWarning = ref('')
  const questionSetUpdated = ref(false)
  const loading = ref(false)
  const previewing = ref(false)
  const locking = ref(false)
  const eventId = ref('jisong')
  const participantId = ref('')
  const nickname = ref('')
  const startedAt = ref('')
  const updatedAt = ref('')
  let lastPersistAt = 0

  const questions = computed(() => questionSet.value?.questions || [])
  const totalQuestions = computed(() => questionSet.value?.totalQuestions || questions.value.length)
  const currentQuestion = computed(() => questions.value[currentIndex.value] || null)
  const currentAnswer = computed(() => {
    if (!currentQuestion.value) return null
    const value = answers.value[currentQuestion.value.id]
    return Number.isInteger(value) ? value : null
  })
  const answeredCount = computed(() => questions.value.reduce((count, question) => {
    const value = answers.value[question.id]
    return count + (Number.isInteger(value) && value >= 0 && value <= 3 ? 1 : 0)
  }, 0))
  const unansweredCount = computed(() => Math.max(0, totalQuestions.value - answeredCount.value))
  const displayQuestionNumber = computed(() => Math.max(0, currentIndex.value + 1))
  const progressPercent = computed(() => totalQuestions.value
    ? Math.round((displayQuestionNumber.value / totalQuestions.value) * 100)
    : 0)
  const isWindowOpen = computed(() => phase.value === 'IN_PROGRESS'
    && Boolean(currentQuestion.value)
    && audioPositionMs.value >= currentQuestion.value.audioStartMs
    && audioPositionMs.value < currentQuestion.value.audioEndMs)
  const canAdvanceQuestion = computed(() => isWindowOpen.value
    && currentIndex.value >= 0
    && currentIndex.value < questions.value.length - 1)
  const canFinishAttempt = computed(() => phase.value === 'IN_PROGRESS'
    && currentIndex.value === questions.value.length - 1)
  const canLock = computed(() => phase.value === 'RESULT_READY' && !locking.value)
  const hasResumeProgress = computed(() => Boolean(attemptId.value)
    && (currentIndex.value >= 0 || answeredCount.value > 0))
  const questionSetVersion = computed(() => questionSet.value?.questionSetVersion || '')

  function setContext(nextEventId, nextParticipantId, nextNickname) {
    eventId.value = String(nextEventId || 'jisong').trim() || 'jisong'
    participantId.value = String(nextParticipantId || '')
    nickname.value = String(nextNickname || '').trim()
  }

  function attemptDurationMs() {
    if (!startedAt.value) return activeDurationMs.value
    const startedTime = new Date(startedAt.value).getTime()
    if (!Number.isFinite(startedTime)) return activeDurationMs.value
    return Math.max(0, Date.now() - startedTime)
  }

  function resetAttemptState() {
    answers.value = {}
    currentIndex.value = -1
    audioPositionMs.value = questionSet.value?.audioStartMs || 0
    activeDurationMs.value = 0
    questionRemainingMs.value = 0
    preview.value = null
    previewError.value = ''
    visiblePause.value = false
  }

  async function loadQuestionSet(context) {
    setContext(context.eventId, context.participantId, context.nickname)
    loading.value = true
    errorMessage.value = ''
    previewError.value = ''
    questionSetUpdated.value = false
    try {
      const [set, remoteStatus] = await Promise.all([
        getOnlineQuestions(eventId.value),
        getOnlineStatus(eventId.value, participantId.value),
      ])
      questionSet.value = set
      status.value = remoteStatus
      if (remoteStatus?.status === 'LOCKED') {
        recoverLockedState(remoteStatus)
        return
      }
      restoreProgress()
    } catch (err) {
      phase.value = 'ERROR'
      errorMessage.value = err.response?.data?.message || '题集加载失败，请检查网络后重试'
    } finally {
      loading.value = false
    }
  }

  function restoreProgress() {
    const result = readOnlineProgress()
    if (!result.value) {
      resetAttemptState()
      phase.value = 'IDLE'
      if (result.invalid) clearOnlineProgress()
      if (!isLocalStorageAvailable()) {
        storageWarning.value = '当前浏览器无法保存进度，刷新后需重新开始'
      }
      return
    }
    const progress = result.value
    if (progress.version === PROGRESS_VERSION
      && progress.eventId === eventId.value
      && progress.participantId === participantId.value
      && progress.questionSetVersion
      && progress.questionSetVersion !== questionSetVersion.value) {
      questionSetUpdated.value = true
    }
    if (progress.version !== PROGRESS_VERSION
      || progress.eventId !== eventId.value
      || progress.participantId !== participantId.value
      || progress.questionSetVersion !== questionSetVersion.value
      || !['IN_PROGRESS', 'RESULT_READY'].includes(progress.status)
      || !progress.attemptId
      || !Number.isInteger(progress.currentIndex)
      || progress.currentIndex < -1
      || progress.currentIndex >= totalQuestions.value
      || !progress.answers
      || typeof progress.answers !== 'object'
      || !Number.isFinite(progress.audioPositionMs)
      || progress.audioPositionMs < 0
      || progress.audioPositionMs > questionSet.value.audioEndMs) {
      clearOnlineProgress()
      resetAttemptState()
      phase.value = 'IDLE'
      return
    }
    const validIds = new Set(questions.value.map((question) => question.id))
    const restoredAnswers = {}
    for (const [questionId, optionIndex] of Object.entries(progress.answers)) {
      if (!validIds.has(questionId)
        || !Number.isInteger(optionIndex)
        || optionIndex < 0
        || optionIndex > 3) {
        clearOnlineProgress()
        resetAttemptState()
        phase.value = 'IDLE'
        return
      }
      restoredAnswers[questionId] = optionIndex
    }
    attemptId.value = progress.attemptId
    answers.value = restoredAnswers
    currentIndex.value = progress.currentIndex
    audioPositionMs.value = Math.round(progress.audioPositionMs)
    activeDurationMs.value = Math.max(0, Number(progress.activeDurationMs) || 0)
    questionRemainingMs.value = Math.max(0, Number(progress.questionRemainingMs) || 0)
    startedAt.value = progress.startedAt || ''
    updatedAt.value = progress.updatedAt || ''
    if (progress.status === 'RESULT_READY') {
      phase.value = 'RESULT_READY'
      if (Number.isInteger(progress.previewScore)) {
        preview.value = {
          score: progress.previewScore,
          answeredCount: progress.previewAnsweredCount ?? answeredCount.value,
          totalQuestions: totalQuestions.value,
          durationMs: attemptDurationMs(),
          questionSetVersion: questionSetVersion.value,
        }
      }
    } else {
      phase.value = 'IDLE'
    }
  }

  function beginAttempt() {
    if (!attemptId.value) attemptId.value = createId()
    answers.value = {}
    currentIndex.value = -1
    audioPositionMs.value = questionSet.value?.audioStartMs || 0
    activeDurationMs.value = 0
    questionRemainingMs.value = 0
    preview.value = null
    previewError.value = ''
    lockResult.value = null
    visiblePause.value = false
    startedAt.value = new Date().toISOString()
    phase.value = 'IN_PROGRESS'
    persistProgress(true)
  }

  function resumeAttempt() {
    if (!attemptId.value) return false
    visiblePause.value = false
    phase.value = 'IN_PROGRESS'
    persistProgress(true)
    return true
  }

  function updateClock(positionMs, durationMs) {
    const position = Math.max(0, Math.round(Number(positionMs) || 0))
    audioPositionMs.value = position
    activeDurationMs.value = Math.max(0, Math.round(Number(durationMs) || 0))
    derivePosition(position)
    persistProgress(false)
  }

  function derivePosition(position) {
    let nextIndex = -1
    for (let index = 0; index < questions.value.length; index += 1) {
      if (position >= questions.value[index].audioStartMs) nextIndex = index
      else break
    }
    if (nextIndex > currentIndex.value) currentIndex.value = nextIndex
    const question = questions.value[currentIndex.value]
    questionRemainingMs.value = question
      ? Math.max(0, question.audioEndMs - position)
      : 0
    if (question && position >= question.audioEndMs
      && currentIndex.value < questions.value.length - 1) {
      currentIndex.value += 1
      audioPositionMs.value = questions.value[currentIndex.value].audioStartMs
    }
  }

  function selectOption(optionIndex) {
    if (!isWindowOpen.value || !currentQuestion.value) return false
    if (!Number.isInteger(optionIndex) || optionIndex < 0 || optionIndex > 3) return false
    answers.value = {
      ...answers.value,
      [currentQuestion.value.id]: optionIndex,
    }
    persistProgress(true)
    return true
  }

  function advanceToNextQuestion() {
    if (!canAdvanceQuestion.value) return false
    return goToQuestion(currentIndex.value + 1)
  }

  function goToQuestion(index) {
    if (!isWindowOpen.value) return false
    if (!Number.isInteger(index) || index < 0 || index >= questions.value.length) return false
    const nextQuestion = questions.value[index]
    if (!nextQuestion) return false
    currentIndex.value = index
    audioPositionMs.value = Math.max(0, Math.round(nextQuestion.audioStartMs))
    questionRemainingMs.value = Math.max(0, nextQuestion.audioEndMs - nextQuestion.audioStartMs)
    persistProgress(true)
    return true
  }

  function completeAttempt() {
    const last = questions.value[questions.value.length - 1]
    phase.value = 'RESULT_READY'
    if (last) {
      currentIndex.value = last.index
      audioPositionMs.value = last.audioEndMs
    }
    questionRemainingMs.value = 0
    const submittedAt = new Date().toISOString()
    const startedTime = Date.parse(startedAt.value)
    if (Number.isFinite(startedTime)) {
      activeDurationMs.value = Math.max(0, Date.parse(submittedAt) - startedTime)
    }
    persistProgress(true)
  }

  async function previewScore() {
    if (!['RESULT_READY', 'IDLE'].includes(phase.value) || previewing.value) return
    previewing.value = true
    previewError.value = ''
    const requestAttemptId = attemptId.value
    try {
      const result = await previewOnlineScore(eventId.value, buildAttemptPayload())
      if (requestAttemptId !== attemptId.value) return
      preview.value = result
      persistProgress(true)
    } catch (err) {
      previewError.value = err.response?.data?.message || '预览分获取失败，请稍后重试'
    } finally {
      previewing.value = false
    }
  }

  async function lockScore() {
    if (!canLock.value) return null
    locking.value = true
    errorMessage.value = ''
    const requestAttemptId = attemptId.value
    try {
      const result = await lockOnlineScore(eventId.value, buildAttemptPayload())
      if (requestAttemptId !== attemptId.value) return null
      lockResult.value = result
      status.value = { ...result, status: 'LOCKED' }
      phase.value = 'LOCKED'
      visiblePause.value = false
      clearOnlineProgress()
      return result
    } catch (err) {
      const statusCode = err.response?.status
      if (statusCode === 409 || statusCode == null || statusCode >= 500) {
        try {
          const remoteStatus = await getOnlineStatus(eventId.value, participantId.value)
          if (remoteStatus?.status === 'LOCKED') {
            recoverLockedState(remoteStatus)
            return lockResult.value
          }
        } catch { /* 状态查询失败时按原请求结果提示 */ }
      }
      errorMessage.value = statusCode === 409
        ? (err.response?.data?.message || '该参与者已经锁定成绩')
        : (err.response?.data?.message || '锁定失败，请稍后重试')
      return null
    } finally {
      locking.value = false
    }
  }

  function recoverLockedState(remoteStatus) {
    status.value = remoteStatus
    lockResult.value = {
      participantId: remoteStatus.participantId,
      score: remoteStatus.score,
      answeredCount: remoteStatus.answeredCount,
      totalQuestions: remoteStatus.totalQuestions,
      durationMs: remoteStatus.durationMs,
      onlineRank: remoteStatus.onlineRank,
      totalRank: remoteStatus.totalRank,
      lockedAt: remoteStatus.lockedAt,
    }
    phase.value = 'LOCKED'
    visiblePause.value = false
    clearOnlineProgress()
  }

  function retryAttempt() {
    clearOnlineProgress()
    resetAttemptState()
    attemptId.value = createId()
    startedAt.value = ''
    updatedAt.value = ''
    lockResult.value = null
    errorMessage.value = ''
    phase.value = 'IDLE'
  }

  function setPhaseError(message) {
    phase.value = 'ERROR'
    errorMessage.value = message
    visiblePause.value = false
    persistProgress(true)
  }

  function clearPhaseError() {
    errorMessage.value = ''
  }

  function setVisiblePause(value) {
    visiblePause.value = Boolean(value)
  }

  function buildAttemptPayload() {
    return {
      participantId: participantId.value,
      nickname: nickname.value,
      attemptId: attemptId.value,
      questionSetVersion: questionSetVersion.value,
      durationMs: Math.max(0, Math.round(attemptDurationMs())),
      answers: questions.value
        .filter((question) => Number.isInteger(answers.value[question.id]))
        .map((question) => ({
          questionId: question.id,
          optionIndex: answers.value[question.id],
        })),
    }
  }

  function persistProgress(force) {
    if (!attemptId.value || !questionSet.value) return false
    const now = Date.now()
    if (!force && now - lastPersistAt < 500) return false
    lastPersistAt = now
    updatedAt.value = new Date(now).toISOString()
    const savedStatus = phase.value === 'RESULT_READY' ? 'RESULT_READY' : 'IN_PROGRESS'
    const saved = saveOnlineProgress({
      version: PROGRESS_VERSION,
      eventId: eventId.value,
      participantId: participantId.value,
      attemptId: attemptId.value,
      questionSetVersion: questionSetVersion.value,
      status: savedStatus,
      currentIndex: currentIndex.value,
      answers: { ...answers.value },
      audioPositionMs: Math.max(0, Math.round(audioPositionMs.value)),
      activeDurationMs: Math.max(0, Math.round(activeDurationMs.value)),
      questionRemainingMs: Math.max(0, Math.round(questionRemainingMs.value)),
      startedAt: startedAt.value || new Date(now).toISOString(),
      updatedAt: updatedAt.value,
      previewScore: preview.value?.score ?? null,
      previewAnsweredCount: preview.value?.answeredCount ?? null,
    })
    if (!saved) storageWarning.value = '当前浏览器无法保存进度，刷新后需重新开始'
    return saved
  }

  return {
    phase,
    questionSet,
    attemptId,
    answers,
    currentIndex,
    audioPositionMs,
    activeDurationMs,
    questionRemainingMs,
    preview,
    lockResult,
    status,
    visiblePause,
    errorMessage,
    previewError,
    storageWarning,
    questionSetUpdated,
    loading,
    previewing,
    locking,
    eventId,
    participantId,
    nickname,
    updatedAt,
    questions,
    totalQuestions,
    currentQuestion,
    currentAnswer,
    answeredCount,
    unansweredCount,
    displayQuestionNumber,
    progressPercent,
    isWindowOpen,
    canAdvanceQuestion,
    canFinishAttempt,
    canLock,
    hasResumeProgress,
    questionSetVersion,
    loadQuestionSet,
    beginAttempt,
    resumeAttempt,
    updateClock,
    selectOption,
    advanceToNextQuestion,
    goToQuestion,
    completeAttempt,
    previewScore,
    lockScore,
    retryAttempt,
    setPhaseError,
    clearPhaseError,
    setVisiblePause,
    persistProgress,
  }
})

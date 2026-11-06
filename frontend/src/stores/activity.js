import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getEventState, getLeaderboard, getLeaderboardHistory, joinEvent } from '../services/api'
import {
  MODES,
  clearPlayerId,
  getParticipantId,
  getStoredEventId,
  getStoredMode,
  getStoredNickname,
  getStoredPlayerId,
  normalizeMode,
  saveEventId,
  saveMode,
  saveNickname,
  saveParticipantId,
  savePlayerId,
} from '../services/storage'

export const useActivityStore = defineStore('activity', () => {
  const eventId = ref(getStoredEventId())
  const eventName = ref('')
  const mode = ref(getStoredMode() || MODES.ONLINE)
  const participantId = ref(getParticipantId())
  const playerId = ref(getStoredPlayerId())
  const nickname = ref(getStoredNickname())
  const onlineLocked = ref(false)
  const onlineScore = ref(null)
  const lockedAt = ref(null)
  const state = ref(null)
  const leaderboard = ref([])
  const leaderboardHistory = ref([])
  const loading = ref(false)
  const error = ref('')
  let refreshSequence = 0

  const isOnline = computed(() => mode.value === MODES.ONLINE)
  const isOffline = computed(() => mode.value === MODES.OFFLINE)
  const isParticipant = computed(() => isOnline.value
    ? Boolean(participantId.value && nickname.value)
    : Boolean(playerId.value && nickname.value))
  const currentQuestion = computed(() => state.value?.question)

  function setSession(nextEventId, nextMode) {
    eventId.value = String(nextEventId || 'jisong').trim() || 'jisong'
    mode.value = normalizeMode(nextMode)
    saveEventId(eventId.value)
    saveMode(mode.value)
  }

  function setParticipantId(value) {
    if (!value) return
    participantId.value = value
    saveParticipantId(value)
  }

  function applyJoinResult(result) {
    setSession(result.eventId || eventId.value, result.mode || mode.value)
    eventName.value = result.eventName || eventName.value
    setParticipantId(result.participantId)
    nickname.value = result.nickname || nickname.value
    saveNickname(nickname.value)
    onlineLocked.value = Boolean(result.onlineLocked)
    onlineScore.value = result.onlineScore ?? null
    lockedAt.value = result.lockedAt ?? null
    if (result.mode === MODES.OFFLINE && result.playerId) {
      playerId.value = result.playerId
      savePlayerId(result.playerId)
    }
  }

  async function joinOnline() {
    const result = await joinEvent(eventId.value, {
      nickname: nickname.value.trim(),
      mode: MODES.ONLINE,
      participantId: participantId.value,
    })
    applyJoinResult(result)
    return result
  }

  async function joinOffline() {
    const result = await joinEvent(eventId.value, {
      nickname: nickname.value.trim(),
      mode: MODES.OFFLINE,
      participantId: participantId.value,
    })
    applyJoinResult(result)
    return result
  }

  async function refreshState() {
    const sequence = ++refreshSequence
    loading.value = true
    error.value = ''
    try {
      const next = await getEventState(eventId.value, isOffline.value ? playerId.value : '')
      if (sequence !== refreshSequence) return true
      state.value = next
      if (state.value?.eventName) eventName.value = state.value.eventName
      return true
    } catch (err) {
      if (sequence !== refreshSequence) return true
      const text = err.response?.data?.message || '暂时无法连接活动'
      if (isOffline.value && err.response?.status === 400 && text.includes('身份无效')) {
        clearOfflinePlayer()
        error.value = '参与者身份已失效，请重新加入活动'
        return false
      }
      error.value = text
      return false
    } finally {
      if (sequence === refreshSequence) loading.value = false
    }
  }

  async function refreshLeaderboards() {
    try {
      [leaderboard.value, leaderboardHistory.value] = await Promise.all([
        getLeaderboard(eventId.value),
        getLeaderboardHistory(eventId.value),
      ])
    } catch (err) {
      error.value = err.response?.data?.message || '暂时无法读取线下榜单'
    }
  }

  function clearOfflinePlayer() {
    playerId.value = ''
    state.value = null
    leaderboard.value = []
    leaderboardHistory.value = []
    clearPlayerId()
  }

  return {
    eventId,
    eventName,
    mode,
    participantId,
    playerId,
    nickname,
    onlineLocked,
    onlineScore,
    lockedAt,
    state,
    leaderboard,
    leaderboardHistory,
    loading,
    error,
    isOnline,
    isOffline,
    isParticipant,
    currentQuestion,
    setSession,
    setParticipantId,
    applyJoinResult,
    joinOnline,
    joinOffline,
    refreshState,
    refreshLeaderboards,
    clearOfflinePlayer,
  }
})

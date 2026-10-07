import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getEventState } from '../services/api'

export const useActivityStore = defineStore('activity', () => {
  const eventId = ref('demo')
  const playerId = ref(localStorage.getItem('bgm-player-id') || '')
  const nickname = ref(localStorage.getItem('bgm-nickname') || '')
  const state = ref(null)
  const loading = ref(false)
  const error = ref('')

  const isParticipant = computed(() => Boolean(playerId.value))
  const currentQuestion = computed(() => state.value?.currentQuestion)

  function setPlayer(payload) {
    eventId.value = payload.eventId
    playerId.value = payload.playerId
    nickname.value = payload.nickname
    localStorage.setItem('bgm-player-id', payload.playerId)
    localStorage.setItem('bgm-nickname', payload.nickname)
  }

  async function refresh() {
    loading.value = true
    error.value = ''
    try {
      state.value = await getEventState(eventId.value, playerId.value)
    } catch (err) {
      error.value = err.response?.data?.message || '暂时无法连接活动'
    } finally {
      loading.value = false
    }
  }

  function clearPlayer() {
    playerId.value = ''
    nickname.value = ''
    state.value = null
    localStorage.removeItem('bgm-player-id')
    localStorage.removeItem('bgm-nickname')
  }

  return {
    eventId,
    playerId,
    nickname,
    state,
    loading,
    error,
    isParticipant,
    currentQuestion,
    setPlayer,
    refresh,
    clearPlayer,
  }
})

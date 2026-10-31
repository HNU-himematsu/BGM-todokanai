import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 8000,
})

export async function getEvent(eventId = 'demo') {
  const { data } = await api.get(`/events/${eventId}`)
  return data.data
}

export async function joinEvent(eventId, payload) {
  const body = typeof payload === 'string' ? { nickname: payload } : payload
  const { data } = await api.post(`/events/${eventId}/join`, body)
  return data.data
}

export async function getOnlineQuestions(eventId) {
  const { data } = await api.get(`/events/${eventId}/online/questions`)
  return data.data
}

export async function getOnlineStatus(eventId, participantId) {
  const { data } = await api.get(`/events/${eventId}/online/status`, {
    params: { participantId },
  })
  return data.data
}

export async function previewOnlineScore(eventId, payload) {
  const { data } = await api.post(`/events/${eventId}/online/preview`, payload)
  return data.data
}

export async function lockOnlineScore(eventId, payload) {
  const { data } = await api.post(`/events/${eventId}/online/lock`, payload)
  return data.data
}

export async function getEventState(eventId, playerId) {
  const { data } = await api.get(`/events/${eventId}/state`, {
    params: playerId ? { playerId } : {},
  })
  return data.data
}

export async function submitAnswer(eventId, playerId, questionId, optionIndex) {
  const payload = typeof playerId === 'object'
    ? playerId
    : { playerId, questionId, optionIndex }
  const { data } = await api.post(`/events/${eventId}/answers`, payload)
  return data.data
}

export async function hostLogin(password) {
  const { data } = await api.post('/host/login', { password })
  return data.data
}

export async function hostLogout(token) {
  await api.post('/host/logout', null, {
    headers: { 'X-Host-Token': token },
  })
}

export function getAudioUrl(eventId) {
  const configured = import.meta.env.VITE_AUDIO_URL
  if (configured) return configured
  const base = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '')
  return `${base}/events/${encodeURIComponent(eventId)}/audio`
}

export async function getLeaderboard(eventId) {
  const { data } = await api.get(`/events/${eventId}/leaderboard`)
  return data.data
}

export async function getLeaderboardHistory(eventId) {
  const { data } = await api.get(`/events/${eventId}/leaderboards/history`)
  return data.data
}

export async function getOnlineLeaderboard(eventId) {
  const { data } = await api.get(`/events/${eventId}/leaderboards/online`)
  return data.data
}

export async function getTotalLeaderboard(eventId) {
  const { data } = await api.get(`/events/${eventId}/leaderboards/total`)
  return data.data
}

export async function getHostRounds(eventId, token) {
  const { data } = await api.get(`/events/${eventId}/host/rounds`, {
    headers: { 'X-Host-Token': token },
  })
  return data.data
}

export async function selectHostRound(eventId, roundId, token) {
  const { data } = await api.post(`/events/${eventId}/host/round`, { roundId }, {
    headers: { 'X-Host-Token': token },
  })
  return data.data
}

export async function getHostState(eventId, token) {
  const { data } = await api.get(`/events/${eventId}/host/state`, {
    headers: { 'X-Host-Token': token },
  })
  return data.data
}

export async function hostAction(eventId, action, token) {
  const { data } = await api.post(`/events/${eventId}/host/${action}`, null, {
    headers: { 'X-Host-Token': token },
  })
  return data.data
}

import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: 8000,
})

export async function getEvent(eventId = 'demo') {
  const { data } = await api.get(`/events/${eventId}`)
  return data.data
}

export async function joinEvent(eventId, nickname) {
  const { data } = await api.post(`/events/${eventId}/join`, { nickname })
  return data.data
}

export async function getEventState(eventId, playerId) {
  const { data } = await api.get(`/events/${eventId}/state`, {
    params: playerId ? { playerId } : {},
  })
  return data.data
}

export async function submitAnswer(eventId, playerId, questionId, optionIndex) {
  const { data } = await api.post(`/events/${eventId}/answers`, {
    playerId,
    questionId,
    optionIndex,
  })
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
  const base = (import.meta.env.VITE_API_BASE || '/api').replace(/\/$/, '')
  return `${base}/events/${encodeURIComponent(eventId)}/audio`
}

export async function getLeaderboard(eventId) {
  const { data } = await api.get(`/events/${eventId}/leaderboard`)
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

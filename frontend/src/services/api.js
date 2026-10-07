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

export async function hostAction(eventId, action, token, params = {}) {
  const { data } = await api.post(`/events/${eventId}/host/${action}`, null, {
    headers: { 'X-Host-Token': token },
    params,
  })
  return data.data
}

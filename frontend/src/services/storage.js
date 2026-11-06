export const MODES = Object.freeze({
  ONLINE: 'ONLINE',
  OFFLINE: 'OFFLINE',
})

export const STORAGE_KEYS = Object.freeze({
  participantId: 'bgm.participant-id.v1',
  nickname: 'bgm.nickname.v1',
  nicknameSession: 'bgm.nickname.session',
  mode: 'bgm.mode',
  eventId: 'bgm.event-id',
  playerId: 'bgm.player-id',
  onlineProgress: 'bgm.online.progress.v1',
  hostToken: 'bgm.host-token',
})

const LEGACY_KEYS = Object.freeze({
  participantId: 'bgm-participant-id',
  nickname: 'bgm-nickname',
  playerId: 'bgm-player-id',
  hostToken: 'bgm-host-token',
})

function storage(name) {
  try {
    return window[name]
  } catch {
    return null
  }
}

export function isLocalStorageAvailable() {
  const target = storage('localStorage')
  if (!target) return false
  const key = 'bgm.storage.probe'
  try {
    target.setItem(key, '1')
    target.removeItem(key)
    return true
  } catch {
    return false
  }
}

function read(storageName, key) {
  try {
    return storage(storageName)?.getItem(key) ?? null
  } catch {
    return null
  }
}

function write(storageName, key, value) {
  try {
    storage(storageName)?.setItem(key, value)
    return true
  } catch {
    return false
  }
}

function remove(storageName, key) {
  try {
    storage(storageName)?.removeItem(key)
    return true
  } catch {
    return false
  }
}

export function createId(prefix = '') {
  const randomUuid = globalThis.crypto?.randomUUID?.()
  if (randomUuid) return randomUuid
  const random = Math.random().toString(36).slice(2, 12)
  return `${prefix}${Date.now().toString(36)}-${random}`
}

export function getParticipantId() {
  const existing = read('localStorage', STORAGE_KEYS.participantId)
    || read('localStorage', LEGACY_KEYS.participantId)
  if (existing) {
    if (!read('localStorage', STORAGE_KEYS.participantId)) {
      write('localStorage', STORAGE_KEYS.participantId, existing)
    }
    return existing
  }
  const participantId = createId('p-')
  write('localStorage', STORAGE_KEYS.participantId, participantId)
  return participantId
}

export function getStoredParticipantId() {
  return read('localStorage', STORAGE_KEYS.participantId)
    || read('localStorage', LEGACY_KEYS.participantId)
    || ''
}

export function saveParticipantId(participantId) {
  if (!participantId) return false
  return write('localStorage', STORAGE_KEYS.participantId, participantId)
}

export function getStoredNickname() {
  return read('localStorage', STORAGE_KEYS.nickname)
    || read('sessionStorage', STORAGE_KEYS.nicknameSession)
    || read('localStorage', LEGACY_KEYS.nickname)
    || read('sessionStorage', LEGACY_KEYS.nickname)
    || ''
}

export function saveNickname(nickname) {
  const value = String(nickname || '').trim()
  if (!value) return false
  write('localStorage', STORAGE_KEYS.nickname, value)
  write('sessionStorage', STORAGE_KEYS.nicknameSession, value)
  write('localStorage', LEGACY_KEYS.nickname, value)
  write('sessionStorage', LEGACY_KEYS.nickname, value)
  return true
}

export function normalizeMode(value) {
  return String(value || '').trim().toUpperCase() === MODES.OFFLINE
    ? MODES.OFFLINE
    : MODES.ONLINE
}

export function parseModeQuery(value) {
  const raw = String(value || '').trim().toLowerCase()
  if (!raw) return { mode: MODES.ONLINE, invalid: false }
  if (raw === 'offline') return { mode: MODES.OFFLINE, invalid: false }
  if (raw === 'online') return { mode: MODES.ONLINE, invalid: false }
  return { mode: MODES.ONLINE, invalid: true }
}

export function getStoredMode() {
  const mode = normalizeMode(read('sessionStorage', STORAGE_KEYS.mode))
  return read('sessionStorage', STORAGE_KEYS.mode) ? mode : null
}

export function saveMode(mode) {
  return write('sessionStorage', STORAGE_KEYS.mode, normalizeMode(mode))
}

const LEGACY_EVENT_IDS = new Set(['demo'])

export function getStoredEventId() {
  const stored = read('sessionStorage', STORAGE_KEYS.eventId)
  if (!stored || LEGACY_EVENT_IDS.has(stored)) return 'jisong'
  return stored
}

export function saveEventId(eventId) {
  return write('sessionStorage', STORAGE_KEYS.eventId, String(eventId || 'jisong').trim() || 'jisong')
}

export function getStoredPlayerId() {
  return read('sessionStorage', STORAGE_KEYS.playerId)
    || read('sessionStorage', LEGACY_KEYS.playerId)
    || ''
}

export function savePlayerId(playerId) {
  if (!playerId) return false
  return write('sessionStorage', STORAGE_KEYS.playerId, playerId)
}

export function clearPlayerId() {
  remove('sessionStorage', STORAGE_KEYS.playerId)
  remove('sessionStorage', LEGACY_KEYS.playerId)
}

export function readOnlineProgress() {
  const raw = read('localStorage', STORAGE_KEYS.onlineProgress)
  if (!raw) return { value: null, invalid: false }
  try {
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { value: null, invalid: true }
    }
    return { value: parsed, invalid: false }
  } catch {
    return { value: null, invalid: true }
  }
}

export function saveOnlineProgress(progress) {
  try {
    return write('localStorage', STORAGE_KEYS.onlineProgress, JSON.stringify(progress))
  } catch {
    return false
  }
}

export function clearOnlineProgress() {
  remove('localStorage', STORAGE_KEYS.onlineProgress)
}

export function getHostToken() {
  return read('sessionStorage', STORAGE_KEYS.hostToken)
    || read('sessionStorage', LEGACY_KEYS.hostToken)
    || ''
}

export function saveHostToken(token) {
  if (!token) return false
  write('sessionStorage', LEGACY_KEYS.hostToken, token)
  return write('sessionStorage', STORAGE_KEYS.hostToken, token)
}

export function clearHostToken() {
  remove('sessionStorage', STORAGE_KEYS.hostToken)
  remove('sessionStorage', LEGACY_KEYS.hostToken)
}

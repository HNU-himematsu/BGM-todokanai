import { createRouter, createWebHistory } from 'vue-router'
import JoinView from '../views/JoinView.vue'
import PlayView from '../views/PlayView.vue'
import HostView from '../views/HostView.vue'
import {
  MODES,
  getStoredMode,
  getStoredNickname,
  getStoredParticipantId,
  getStoredPlayerId,
} from '../services/storage'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: JoinView },
    { path: '/play', component: PlayView },
    { path: '/host', component: HostView },
  ],
})

router.beforeEach((to) => {
  if (to.path !== '/play') return true
  const mode = getStoredMode()
  const eventId = String(to.query.event || 'demo').trim() || 'demo'
  const nickname = getStoredNickname()
  const hasIdentity = mode === MODES.OFFLINE
    ? Boolean(nickname && getStoredPlayerId())
    : Boolean(nickname && getStoredParticipantId())
  if (mode && hasIdentity) return true
  return {
    path: '/',
    query: {
      mode: mode === MODES.OFFLINE ? 'offline' : 'online',
      event: eventId,
    },
  }
})

export default router

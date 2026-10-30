<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import OfflinePlayPanel from '../components/OfflinePlayPanel.vue'
import OnlinePlayPanel from '../components/OnlinePlayPanel.vue'
import { MODES, getStoredMode, normalizeMode } from '../services/storage'
import { useActivityStore } from '../stores/activity'

const route = useRoute()
const activity = useActivityStore()
const mode = computed(() => {
  const nextEventId = String(route.query.event || activity.eventId || 'demo')
  const queryMode = String(route.query.mode || '').trim().toLowerCase()
  const stored = queryMode === 'online' || queryMode === 'offline'
    ? normalizeMode(queryMode)
    : (getStoredMode() || activity.mode || MODES.ONLINE)
  activity.setSession(nextEventId, stored)
  return stored
})
</script>

<template>
  <OnlinePlayPanel v-if="mode === MODES.ONLINE" />
  <OfflinePlayPanel v-else />
</template>

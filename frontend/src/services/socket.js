import { Client } from '@stomp/stompjs'

export function createActivitySocket(eventId, onMessage) {
  const configuredWsBase = import.meta.env.VITE_WS_BASE
  const configuredApiBase = import.meta.env.VITE_API_BASE
  let wsBase = configuredWsBase

  if (!wsBase && configuredApiBase?.startsWith('http')) {
    wsBase = configuredApiBase.replace(/\/api\/?$/, '').replace(/^http/, 'ws') + '/ws'
  }

  if (!wsBase) {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    wsBase = `${protocol}//${window.location.host}/ws`
  }

  const client = new Client({
    brokerURL: wsBase,
    reconnectDelay: 3000,
    debug: () => {},
  })

  client.onConnect = () => {
    client.subscribe(`/topic/events/${eventId}`, (message) => {
      try {
        onMessage(JSON.parse(message.body))
      } catch {
        onMessage({ type: 'QUESTION_STARTED' })
      }
    })
  }

  client.onStompError = () => {
    onMessage({ type: 'SOCKET_ERROR' })
  }

  client.activate()
  return client
}

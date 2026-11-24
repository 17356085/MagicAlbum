export function connectChatRealtime(
  token: string,
  isCurrent: () => boolean,
  onReady: (ready: boolean) => void,
  onChange: () => void,
) {
  if (import.meta.env.VITE_USE_API_MOCK === 'true' || typeof WebSocket === 'undefined') return

  const endpoint = new URL(import.meta.env.VITE_API_BASE_URL || '/api/v1', window.location.origin)
  endpoint.protocol = endpoint.protocol === 'https:' ? 'wss:' : 'ws:'
  endpoint.pathname = endpoint.pathname.replace(/\/$/, '') + '/chat/realtime'
  endpoint.search = ''
  endpoint.hash = ''

  let socket: WebSocket | undefined
  let retry: number | undefined
  let watchdog: number | undefined
  let stopped = false
  let delay = 1000

  function reconnect() {
    if (stopped || !isCurrent()) return
    retry = window.setTimeout(open, delay)
    delay = Math.min(delay * 2, 30_000)
  }

  function drop(connection: WebSocket) {
    if (socket !== connection) return
    socket = undefined
    window.clearTimeout(watchdog)
    connection.close()
    onReady(false)
    reconnect()
  }

  function watchConnection(connection: WebSocket, timeout: number) {
    window.clearTimeout(watchdog)
    watchdog = window.setTimeout(() => drop(connection), timeout)
  }

  function open() {
    if (stopped || !isCurrent()) return
    let connection: WebSocket
    try {
      connection = new WebSocket(endpoint)
    } catch {
      reconnect()
      return
    }
    socket = connection
    let ready = false
    watchConnection(connection, 8000)
    connection.onopen = () => {
      if (stopped || socket !== connection || !isCurrent()) { connection.close(); return }
      connection.send(token)
    }
    connection.onmessage = event => {
      if (stopped || socket !== connection || !isCurrent()) return
      if (!ready) {
        if (event.data !== 'ready') return
        ready = true
        watchConnection(connection, 45_000)
        delay = 1000
        onReady(true)
        onChange()
      } else if (event.data === 'changed' || event.data === 'heartbeat') {
        watchConnection(connection, 45_000)
        if (event.data === 'changed') onChange()
      }
    }
    connection.onclose = () => { if (!stopped) drop(connection) }
  }

  open()
  return () => {
    stopped = true
    window.clearTimeout(retry)
    window.clearTimeout(watchdog)
    socket?.close()
    onReady(false)
  }
}

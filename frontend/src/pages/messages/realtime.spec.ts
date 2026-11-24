import { afterEach, describe, expect, it, vi } from 'vitest'
import { connectChatRealtime } from './realtime'

class TestSocket {
  static connections: TestSocket[] = []
  onopen?: () => void
  onclose?: () => void
  onmessage?: (event: { data: string }) => void
  send = vi.fn()
  close = vi.fn()

  constructor(readonly endpoint: URL) {
    TestSocket.connections.push(this)
  }
}

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  TestSocket.connections = []
})

describe('chat realtime connection', () => {
  it('keeps the authentication deadline until the server acknowledges the session', () => {
    vi.stubEnv('VITE_USE_API_MOCK', 'false')
    vi.stubGlobal('WebSocket', TestSocket)
    vi.useFakeTimers()
    const online = vi.fn()
    const refresh = vi.fn()
    const stop = connectChatRealtime('account-token', () => true, online, refresh)
    try {
      const first = TestSocket.connections[0]!
      first.onopen?.()
      vi.advanceTimersByTime(7000)
      first.onmessage?.({ data: 'heartbeat' })
      first.onmessage?.({ data: 'changed' })
      vi.advanceTimersByTime(1000)
      expect(first.close).toHaveBeenCalledOnce()
      expect(online).not.toHaveBeenCalledWith(true)
      expect(refresh).not.toHaveBeenCalled()
      vi.advanceTimersByTime(1000)
      expect(TestSocket.connections).toHaveLength(2)
    } finally { stop?.() }
  })

  it('authenticates in the first frame and reconnects after a stalled connection', () => {
    vi.stubEnv('VITE_USE_API_MOCK', 'false')
    vi.stubEnv('VITE_API_BASE_URL', 'https://forum.test/api/v1')
    vi.stubGlobal('WebSocket', TestSocket)
    vi.useFakeTimers()
    const online = vi.fn()
    const refresh = vi.fn()
    const stop = connectChatRealtime('account-token', () => true, online, refresh)
    try {
      const first = TestSocket.connections[0]!
      expect(first.endpoint.href).toBe('wss://forum.test/api/v1/chat/realtime')
      expect(first.send).not.toHaveBeenCalled()
      first.onopen?.()
      expect(first.send).toHaveBeenCalledWith('account-token')
      first.onmessage?.({ data: 'ready' })
      first.onmessage?.({ data: 'changed' })
      expect(refresh).toHaveBeenCalledTimes(2)
      expect(online).toHaveBeenLastCalledWith(true)

      vi.advanceTimersByTime(44_000)
      first.onmessage?.({ data: 'heartbeat' })
      vi.advanceTimersByTime(44_000)
      expect(first.close).not.toHaveBeenCalled()
      first.onmessage?.({ data: 'unexpected' })
      vi.advanceTimersByTime(2000)
      expect(first.close).toHaveBeenCalledOnce()
      expect(online).toHaveBeenLastCalledWith(false)
      const second = TestSocket.connections[1]!
      second.onopen?.()
      expect(second.send).toHaveBeenCalledWith('account-token')
      first.onmessage?.({ data: 'changed' })
      expect(refresh).toHaveBeenCalledTimes(2)
      second.onmessage?.({ data: 'ready' })
      expect(refresh).toHaveBeenCalledTimes(3)
    } finally {
      stop?.()
    }
    vi.advanceTimersByTime(60_000)
    expect(TestSocket.connections).toHaveLength(2)
  })
})

import { captureAuthGeneration, handleCurrentTokenUnauthorized } from './client'
import { getStoredAccessToken } from '@/utils/authStorage'

export interface NotificationChange {
  cursor: string
  kind: 'reset' | 'created' | 'read'
  notificationId?: string
}

export class NotificationStreamError extends Error {
  constructor(readonly status: number) { super('通知连接暂不可用') }
}

/** One bounded connection; the owner controls reconnect and account lifecycle. */
export async function readNotificationStream(
  token: string,
  cursor: string | undefined,
  signal: AbortSignal,
  onChange: (change: NotificationChange) => void,
): Promise<void> {
  const generation = captureAuthGeneration()
  const headers: Record<string, string> = { Accept: 'text/event-stream', Authorization: `Bearer ${token}` }
  if (cursor !== undefined) headers['Last-Event-ID'] = cursor
  const base = String(import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/$/, '')
  const response = await fetch(`${base}/notifications/stream`, { headers, signal, cache: 'no-store', credentials: 'omit' })
  if (response.status === 401) handleCurrentTokenUnauthorized(token, generation)
  if (!response.ok) throw new NotificationStreamError(response.status)
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
    throw new NotificationStreamError(0)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let pending = ''
  let lastCursor = cursor
  let eventName = ''
  let eventId = ''
  let data: string[] = []
  let dataLength = 0
  const dispatch = () => {
    if (!data.length) { eventName = ''; eventId = ''; return }
    if (!/^(0|[1-9]\d{0,18})$/.test(eventId) || BigInt(eventId) > 9223372036854775807n) throw new NotificationStreamError(0)
    const value: unknown = JSON.parse(data.join('\n'))
    if (!value || typeof value !== 'object') throw new NotificationStreamError(0)
    const event = value as Record<string, unknown>
    const reset = eventName === 'reset' && event.kind === 'reset'
    if (!reset && (eventName !== 'notification' || !['created', 'read'].includes(String(event.kind))
      || event.cursor !== eventId || typeof event.notificationId !== 'string' || !/^[1-9]\d{0,18}$/.test(event.notificationId))) {
      throw new NotificationStreamError(0)
    }
    if (!signal.aborted && getStoredAccessToken() === token
      && (reset || lastCursor === undefined || BigInt(eventId) > BigInt(lastCursor))) {
      onChange({ cursor: eventId, kind: event.kind as NotificationChange['kind'], notificationId: event.notificationId as string | undefined })
      lastCursor = eventId
    }
    data = []; dataLength = 0; eventName = ''; eventId = ''
  }
  try {
    while (!signal.aborted) {
      const chunk = await reader.read()
      if (chunk.done) break
      pending += decoder.decode(chunk.value, { stream: true })
      let end: number
      while ((end = pending.indexOf('\n')) >= 0) {
        let line = pending.slice(0, end)
        pending = pending.slice(end + 1)
        if (line.endsWith('\r')) line = line.slice(0, -1)
        if (line.length > 8192) throw new NotificationStreamError(0)
        if (!line) { dispatch(); continue }
        if (line.startsWith(':')) continue
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'event') eventName = value
        if (field === 'id') eventId = value
        if (field === 'data') {
          dataLength += value.length + (data.length ? 1 : 0)
          if (dataLength > 16384) throw new NotificationStreamError(0)
          data.push(value)
        }
      }
      // Fetch chunks can contain many complete events. Bound the unfinished
      // line and each event instead of rejecting a valid coalesced chunk.
      // Allow a trailing CR until the next chunk supplies its LF.
      if (pending.length > 8193) throw new NotificationStreamError(0)
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readNotificationStream, NotificationStreamError } from '../notificationStream'

const unauthorized = vi.hoisted(() => vi.fn())
vi.mock('../client', () => ({ handleCurrentTokenUnauthorized: unauthorized }))

function response(chunks: string[], beforeRead?: () => void): Response {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      beforeRead?.()
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

describe('authenticated notification stream', () => {
  beforeEach(() => { localStorage.setItem('accessToken', 'owner-token'); unauthorized.mockReset() })
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear() })

  it('parses split CRLF frames and replays only newer durable cursors', async () => {
    const fetcher = vi.fn().mockResolvedValue(response([
      ':heartbeat\r\nevent:notifi', 'cation\r\nid:2\r\ndata:{"cursor":"2","notificationId":"12","kind":"created"}\r\n\r',
      '\nevent:notification\nid:2\ndata:{"cursor":"2","notificationId":"12","kind":"created"}\n\n',
      'event:notification\nid:3\ndata:{"cursor":"3","notificationId":"12","kind":"read"}\n\n',
    ]))
    vi.stubGlobal('fetch', fetcher)
    const changes = vi.fn()
    await readNotificationStream('owner-token', '1', new AbortController().signal, changes)
    expect(changes.mock.calls.map(call => call[0])).toEqual([
      { cursor: '2', notificationId: '12', kind: 'created' },
      { cursor: '3', notificationId: '12', kind: 'read' },
    ])
    expect(fetcher.mock.calls[0]?.[1].headers).toMatchObject({ Authorization: 'Bearer owner-token', 'Last-Event-ID': '1' })
  })

  it('rejects malformed metadata and oversized frames', async () => {
    for (const payload of [
      'event:notification\nid:9223372036854775808\ndata:{}\n\n',
      'event:notification\nid:1\ndata:{"cursor":"2","notificationId":"12","kind":"read"}\n\n',
      'data:' + 'x'.repeat(65537),
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([payload])))
      await expect(readNotificationStream('owner-token', undefined, new AbortController().signal, vi.fn())).rejects.toBeInstanceOf(NotificationStreamError)
    }
  })

  it('discards frames from an old token or an aborted owner', async () => {
    const payload = 'event:reset\nid:0\ndata:{"kind":"reset"}\n\n'
    const changes = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([payload], () => localStorage.setItem('accessToken', 'new-owner'))))
    await readNotificationStream('owner-token', undefined, new AbortController().signal, changes)
    expect(changes).not.toHaveBeenCalled()
    localStorage.setItem('accessToken', 'owner-token')
    const controller = new AbortController()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response([payload], () => controller.abort())))
    await readNotificationStream('owner-token', undefined, controller.signal, changes)
    expect(changes).not.toHaveBeenCalled()
  })

  it('passes the captured token into the existing protected unauthorized handler', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 401 })))
    await expect(readNotificationStream('owner-token', undefined, new AbortController().signal, vi.fn())).rejects.toMatchObject({ status: 401 })
    expect(unauthorized).toHaveBeenCalledWith('owner-token')
  })
})

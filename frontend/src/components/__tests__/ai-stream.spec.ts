import { afterEach, describe, expect, it, vi } from 'vitest'
import { createChatStream } from '@/api/ai'

const authState = vi.hoisted(() => ({ handleUnauthorized: vi.fn() }))
vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ handleUnauthorized: authState.handleUnauthorized }),
}))

function responseForReader(reader: ReadableStreamDefaultReader<Uint8Array>): Response {
  return {
    ok: true,
    headers: new Headers({ 'content-type': 'text/event-stream' }),
    body: { getReader: () => reader },
  } as unknown as Response
}

describe('AI chat SSE protocol', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    authState.handleUnauthorized.mockClear()
  })

  it('sends the current Bearer token and parses split SSE events', async () => {
    localStorage.setItem('accessToken', 'current-jwt')
    const messages: unknown[] = []
    const complete = vi.fn()
    const chunks = [
      'event:message\n',
      'data: {"content":"hel',
      'lo"}\n\n',
      'data: [DONE]\n\n',
    ]
    const reader = {
      read: vi.fn(async () => {
        const chunk = chunks.shift()
        return chunk === undefined
          ? { done: true, value: undefined }
          : { done: false, value: new TextEncoder().encode(chunk) }
      }),
      cancel: vi.fn(async () => undefined),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>
    const fetchMock = vi.fn().mockResolvedValue(responseForReader(reader))
    vi.stubGlobal('fetch', fetchMock)

    const handle = createChatStream([{ role: 'user', content: 'hello' }], (data) => messages.push(data), undefined, complete)
    await handle.done

    expect(messages).toEqual([{ content: 'hello' }])
    expect(complete).toHaveBeenCalledOnce()
    const [, request] = fetchMock.mock.calls[0]
    expect((request.headers as Record<string, string>).Authorization).toBe('Bearer current-jwt')
  })

  it('reports named SSE errors instead of treating them as chat content', async () => {
    const messages: unknown[] = []
    const onError = vi.fn()
    const reader = {
      read: vi.fn()
        .mockResolvedValueOnce({ done: false, value: new TextEncoder().encode('event:error\ndata: {"message":"internal"}\n\n') })
        .mockResolvedValueOnce({ done: true, value: undefined }),
      cancel: vi.fn(async () => undefined),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(responseForReader(reader)))

    const handle = createChatStream([{ role: 'user', content: 'hello' }], (data) => messages.push(data), onError)
    await handle.done

    expect(messages).toEqual([])
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'AI 服务暂不可用' }))
  })

  it('aborts and cancels an in-flight stream', async () => {
    let finishRead: (() => void) | undefined
    const reader = {
      read: vi.fn(() => new Promise<{ done: boolean; value?: Uint8Array }>((resolve) => {
        finishRead = () => resolve({ done: true, value: undefined })
      })),
      cancel: vi.fn(async () => {
        finishRead?.()
      }),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>
    const fetchMock = vi.fn().mockResolvedValue(responseForReader(reader))
    vi.stubGlobal('fetch', fetchMock)

    const handle = createChatStream([{ role: 'user', content: 'hello' }])
    await Promise.resolve()
    await Promise.resolve()
    handle.cancel()
    await handle.done

    expect(fetchMock).toHaveBeenCalledOnce()
    expect(reader.cancel).toHaveBeenCalledOnce()
  })

  it('expires the session only when the SSE handshake still uses the current token', async () => {
    localStorage.setItem('accessToken', 'old-token')
    const fetchMock = vi.fn().mockImplementation(async () => {
      localStorage.setItem('accessToken', 'new-token')
      return { ok: false, status: 401 } as Response
    })
    vi.stubGlobal('fetch', fetchMock)

    const handle = createChatStream([{ role: 'user', content: 'hello' }])
    await handle.done

    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
  })
})

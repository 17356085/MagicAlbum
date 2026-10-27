import { afterEach, describe, expect, it, vi } from 'vitest'
import { createChatStream } from '@/api/ai'
import { AuthSessionChangedError } from '@/api/client'

const authState = vi.hoisted(() => ({ handleUnauthorized: vi.fn(), memoryToken: undefined as string | undefined }))
vi.mock('@/stores/authSession', () => ({
  useAuthSessionStore: () => ({ token: authState.memoryToken, handleUnauthorized: authState.handleUnauthorized }),
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
    authState.memoryToken = undefined
  })

  it('rejects a storage session change at the transport boundary before sending private context', async () => {
    authState.memoryToken = 'actor-a'
    localStorage.setItem('accessToken', 'actor-b')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const onError = vi.fn()
    const handle = createChatStream([{ role: 'user', content: 'A private context' }], undefined, onError)
    await handle.done

    expect(fetchMock).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledWith(expect.any(AuthSessionChangedError))
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
  })

  it('sends the current Bearer token and parses split SSE events', async () => {
    authState.memoryToken = 'current-jwt'
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

  it('suppresses a chunk that resolves after cancellation and cancels only once', async () => {
    let resolveRead: ((result: { done: boolean; value?: Uint8Array }) => void) | undefined
    const onMessage = vi.fn()
    const onError = vi.fn()
    const onComplete = vi.fn()
    const reader = {
      read: vi.fn(() => new Promise<{ done: boolean; value?: Uint8Array }>((resolve) => {
        resolveRead = resolve
      })),
      cancel: vi.fn(async () => {
        resolveRead?.({ done: false, value: new TextEncoder().encode('data: {"content":"late"}\n\n') })
      }),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(responseForReader(reader)))

    const handle = createChatStream([{ role: 'user', content: 'hello' }], onMessage, onError, onComplete)
    await Promise.resolve()
    await Promise.resolve()
    handle.cancel()
    handle.cancel()
    await handle.done

    expect(reader.cancel).toHaveBeenCalledOnce()
    expect(onMessage).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('freezes the request body snapshot before stream callbacks run', async () => {
    const messages = [{ role: 'user', content: 'original' }]
    const reader = {
      read: vi.fn(async () => ({ done: true, value: undefined })),
      cancel: vi.fn(async () => undefined),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>
    const fetchMock = vi.fn().mockResolvedValue(responseForReader(reader))
    vi.stubGlobal('fetch', fetchMock)

    const handle = createChatStream(messages)
    messages[0].content = 'mutated-after-start'
    await handle.done

    const [, request] = fetchMock.mock.calls[0]
    expect(JSON.parse(String(request.body))).toEqual({ messages: [{ role: 'user', content: 'original' }] })
  })

  it.each([
    ['completion marker', 'data: [DONE]\n\n'],
    ['protocol error', 'data: not-json\n\n'],
  ])('releases the reader after a %s without waiting for network EOF', async (_label, chunk) => {
    const reader = {
      read: vi.fn().mockResolvedValue({ done: false, value: new TextEncoder().encode(chunk) }),
      cancel: vi.fn(async () => undefined),
      releaseLock: vi.fn(),
    } as unknown as ReadableStreamDefaultReader<Uint8Array>
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(responseForReader(reader)))

    const handle = createChatStream([{ role: 'user', content: 'hello' }])
    await handle.done
    await Promise.resolve()
    expect(reader.cancel).toHaveBeenCalledOnce()
    expect(reader.releaseLock).toHaveBeenCalledOnce()
    handle.cancel()
    expect(reader.cancel).toHaveBeenCalledOnce()
    expect(reader.read).toHaveBeenCalledOnce()
  })

  it('cancels a late response body when the transport ignores abort', async () => {
    let resolveResponse!: (response: Response) => void
    const body = { cancel: vi.fn(async () => undefined), getReader: vi.fn() }
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(resolve => { resolveResponse = resolve })))
    const onMessage = vi.fn()
    const onError = vi.fn()
    const onComplete = vi.fn()
    const handle = createChatStream([{ role: 'user', content: 'hello' }], onMessage, onError, onComplete)
    handle.cancel()
    resolveResponse({ ok: true, body } as unknown as Response)
    await handle.done

    expect(body.cancel).toHaveBeenCalledOnce()
    expect(body.getReader).not.toHaveBeenCalled()
    expect(onMessage).not.toHaveBeenCalled()
    expect(onError).not.toHaveBeenCalled()
    expect(onComplete).not.toHaveBeenCalled()
  })

  it('cancels and unlocks a real readable stream after the completion marker', async () => {
    const cancel = vi.fn()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('data: [DONE]\n\n'))
      },
      cancel,
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, {
      headers: { 'content-type': 'text/event-stream' },
    })))

    const handle = createChatStream([{ role: 'user', content: 'hello' }])
    await handle.done
    await Promise.resolve()

    expect(cancel).toHaveBeenCalledOnce()
    expect(body.locked).toBe(false)
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

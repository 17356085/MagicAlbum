import client from './client'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Id } from '@/types'
import type { AxiosResponse } from 'axios'

export interface AiChatMessage {
  role: string
  content: string
}

export interface AiSummaryData {
  summary?: string
  status?: string
}

export interface AiSummaryResponse {
  data?: AiSummaryData
  [key: string]: unknown
}

type ChatStreamMessageHandler = (data: unknown) => void
type ChatStreamErrorHandler = (error: Error) => void
type ChatStreamCompleteHandler = () => void

export interface ChatStreamHandle {
  cancel: () => void
  done: Promise<void>
}

type SseEvent = {
  event: string
  data: string
}

export function getSummary(threadId: Id): Promise<AxiosResponse<AiSummaryResponse>> {
  return client.get(`/ai/summary/${threadId}`)
}

export function triggerSummary(threadId: Id, force = false): Promise<AxiosResponse<unknown>> {
  return client.post(`/ai/summary/${threadId}?force=${force}`)
}

export function createChatStream(
  messages: AiChatMessage[],
  onMessage?: ChatStreamMessageHandler,
  onError?: ChatStreamErrorHandler,
  onComplete?: ChatStreamCompleteHandler,
): ChatStreamHandle {
  const apiBase = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/+$/, '')
  const controller = new AbortController()
  let cancelled = false
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null

  const dispatchEvent = (event: SseEvent): boolean => {
    if (event.data.trim() === '[DONE]') return true
    if (event.event === 'error') {
      throw new Error('AI 服务暂不可用')
    }

    let data: unknown
    try {
      data = JSON.parse(event.data)
    } catch {
      throw new Error('流式响应格式错误')
    }
    if (!isRecord(data) || typeof data.content !== 'string') {
      throw new Error('流式响应格式错误')
    }
    onMessage?.(data)
    return false
  }

  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
    'X-Request-Id': createRequestId(),
  }

  const done = (async (): Promise<void> => {
    let completed = false
    let sawDoneMarker = false
    try {
      const token = getStoredAccessToken()
      if (hasRealToken(token)) {
        headers.Authorization = `Bearer ${token}`
      }

      const response = await fetch(apiBase + '/ai/chat/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify({ messages }),
        signal: controller.signal,
      })
      if (!response.ok) {
        throw new Error('AI 请求失败')
      }

      const contentType = response.headers?.get?.('content-type') || ''
      if (contentType && !contentType.toLowerCase().includes('text/event-stream')) {
        throw new Error('流式响应格式错误')
      }

      reader = response.body?.getReader() || null
      if (!reader) {
        throw new Error('流式响应不可用')
      }
      const decoder = new TextDecoder()
      let buffer = ''

      const consumeEventBlock = (block: string): void => {
        const event = parseSseEventBlock(block)
        if (!event) return
        sawDoneMarker = dispatchEvent(event) || sawDoneMarker
      }

      while (!cancelled) {
        const result = await reader.read()
        if (result.done) break
        buffer += decoder.decode(result.value, { stream: true })
        let separator: RegExpMatchArray | null
        while ((separator = buffer.match(/\r?\n\r?\n/)) !== null) {
          const start = separator.index ?? 0
          const block = buffer.slice(0, start)
          buffer = buffer.slice(start + separator[0].length)
          consumeEventBlock(block)
          if (sawDoneMarker) break
        }
        if (sawDoneMarker) break
      }
      buffer += decoder.decode()
      if (!sawDoneMarker && buffer.trim()) consumeEventBlock(buffer)
      if (!cancelled && !completed) {
        completed = true
        onComplete?.()
      }
    } catch (error) {
      if (!cancelled && !controller.signal.aborted) {
        onError?.(error instanceof Error ? error : new Error('AI 服务暂不可用'))
      }
    }
  })()

  return {
    cancel: () => {
      cancelled = true
      controller.abort()
      if (reader) {
        void reader.cancel().catch(() => undefined)
      }
    },
    done,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseSseEventBlock(block: string): SseEvent | null {
  const dataLines: string[] = []
  let event = ''
  let hasUnknownField = false

  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue
    const separator = line.indexOf(':')
    const field = separator === -1 ? line : line.slice(0, separator)
    let value = separator === -1 ? '' : line.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
    else if (field !== 'id' && field !== 'retry') hasUnknownField = true
  }

  if (hasUnknownField) throw new Error('流式响应格式错误')
  if (dataLines.length === 0) return null
  return { event, data: dataLines.join('\n') }
}

function createRequestId(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return 'ui-' + Date.now() + '-' + Math.random().toString(36).slice(2)
  }
}

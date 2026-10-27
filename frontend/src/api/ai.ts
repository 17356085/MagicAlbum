import client, { assertAutomaticRequestOwnsCurrentSession, captureAuthGeneration, handleCurrentTokenUnauthorized } from './client'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Id } from '@/types'

export interface AiChatMessage {
  role: string
  content: string
}

export type AiSummaryStatus =
  | 'IDLE'
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SUPERSEDED'

export interface AiSummary {
  threadId: Id
  taskId: string | null
  contentVersion: number
  taskContentVersion: number | null
  status: AiSummaryStatus
  summary: string
  canManage: boolean
  attempts: number
  errorCode: string | null
  provider: string | null
  model: string | null
  inputTokens: number | null
  outputTokens: number | null
  usageEstimated: boolean
  chargedTokens: number
  requestId: string | null
  updatedAt: string | null
}

// Keep the old names available to callers while making the direct response
// shape explicit at the API boundary.
export type AiSummaryData = AiSummary
export type AiSummaryResponse = AiSummary

export interface SummaryRequestOptions {
  key?: string
  signal?: AbortSignal
}

export class AiSummaryResponseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AiSummaryResponseError'
  }
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

export async function getSummary(threadId: Id, signal?: AbortSignal): Promise<AiSummary> {
  const endpoint = `/ai/summary/${encodeURIComponent(String(threadId))}`
  const response = signal
    ? await client.get(endpoint, { signal })
    : await client.get(endpoint)
  return decodeAiSummary(response.data, endpoint)
}

export function triggerSummary(
  threadId: Id,
  force?: boolean,
  key?: string,
  signal?: AbortSignal,
): Promise<AiSummary>
export function triggerSummary(
  threadId: Id,
  force?: boolean,
  options?: SummaryRequestOptions,
): Promise<AiSummary>
export async function triggerSummary(
  threadId: Id,
  force = false,
  keyOrOptions?: string | SummaryRequestOptions,
  signal?: AbortSignal,
): Promise<AiSummary> {
  const options = normalizeSummaryRequestOptions(keyOrOptions, signal)
  const idempotencyKey = options.key ?? createSummaryRequestKey()
  if (!isUuidV4(idempotencyKey)) {
    throw new Error('摘要请求幂等键格式无效')
  }

  const endpoint = `/ai/summary/${encodeURIComponent(String(threadId))}?force=${force}`
  const config = {
    headers: { 'Idempotency-Key': idempotencyKey },
    ...(options.signal ? { signal: options.signal } : {}),
  }
  const response = await client.post(endpoint, undefined, config)
  return decodeAiSummary(response.data, endpoint)
}

export async function getSummaryTask(taskId: string, signal?: AbortSignal): Promise<AiSummary> {
  const normalizedTaskId = String(taskId || '').trim()
  if (!normalizedTaskId) throw new Error('摘要任务 ID 不能为空')
  const endpoint = `/ai/summary/tasks/${encodeURIComponent(normalizedTaskId)}`
  const response = signal
    ? await client.get(endpoint, { signal })
    : await client.get(endpoint)
  return decodeAiSummary(response.data, endpoint)
}

export async function cancelSummary(
  threadId: Id,
  taskId: string,
  signal?: AbortSignal,
): Promise<AiSummary> {
  const normalizedTaskId = String(taskId || '').trim()
  if (!normalizedTaskId) throw new Error('摘要任务 ID 不能为空')
  const endpoint = `/ai/summary/${encodeURIComponent(String(threadId))}/tasks/${encodeURIComponent(normalizedTaskId)}/cancel`
  const response = signal
    ? await client.post(endpoint, undefined, { signal })
    : await client.post(endpoint)
  return decodeAiSummary(response.data, endpoint)
}

export const cancelSummaryTask = cancelSummary

export function createSummaryRequestKey(): string {
  const cryptoApi = globalThis.crypto
  try {
    if (typeof cryptoApi?.randomUUID === 'function') {
      return cryptoApi.randomUUID()
    }
  } catch (_) {
    // Some browsers expose randomUUID but reject it outside a secure context.
  }

  const bytes = new Uint8Array(16)
  try {
    if (typeof cryptoApi?.getRandomValues === 'function') {
      cryptoApi.getRandomValues(bytes)
    } else {
      fillRandomBytes(bytes)
    }
  } catch (_) {
    fillRandomBytes(bytes)
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function normalizeSummaryRequestOptions(
  keyOrOptions?: string | SummaryRequestOptions,
  signal?: AbortSignal,
): SummaryRequestOptions {
  if (typeof keyOrOptions === 'string') return { key: keyOrOptions, signal }
  return { ...(keyOrOptions || {}), signal: keyOrOptions?.signal || signal }
}

function isUuidV4(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

function fillRandomBytes(bytes: Uint8Array): void {
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Math.floor(Math.random() * 256)
  }
}

export function decodeAiSummary(value: unknown, endpoint = '/ai/summary'): AiSummary {
  const record = asRecord(value, endpoint)
  return {
    threadId: decodeId(requiredField(record, 'threadId', endpoint), 'threadId', endpoint),
    taskId: decodeNullableNonEmptyString(requiredField(record, 'taskId', endpoint), 'taskId', endpoint),
    contentVersion: decodeNonNegativeInteger(requiredField(record, 'contentVersion', endpoint), 'contentVersion', endpoint),
    taskContentVersion: decodeNullableNonNegativeInteger(
      requiredField(record, 'taskContentVersion', endpoint),
      'taskContentVersion',
      endpoint,
    ),
    status: decodeStatus(requiredField(record, 'status', endpoint), endpoint),
    summary: decodeString(requiredField(record, 'summary', endpoint), 'summary', endpoint),
    canManage: decodeBoolean(requiredField(record, 'canManage', endpoint), 'canManage', endpoint),
    attempts: decodeNonNegativeInteger(requiredField(record, 'attempts', endpoint), 'attempts', endpoint),
    errorCode: decodeNullableString(requiredField(record, 'errorCode', endpoint), 'errorCode', endpoint),
    provider: decodeNullableString(requiredField(record, 'provider', endpoint), 'provider', endpoint),
    model: decodeNullableString(requiredField(record, 'model', endpoint), 'model', endpoint),
    inputTokens: decodeNullableNonNegativeInteger(requiredField(record, 'inputTokens', endpoint), 'inputTokens', endpoint),
    outputTokens: decodeNullableNonNegativeInteger(requiredField(record, 'outputTokens', endpoint), 'outputTokens', endpoint),
    usageEstimated: decodeBoolean(requiredField(record, 'usageEstimated', endpoint), 'usageEstimated', endpoint),
    chargedTokens: decodeNonNegativeInteger(requiredField(record, 'chargedTokens', endpoint), 'chargedTokens', endpoint),
    requestId: decodeNullableString(requiredField(record, 'requestId', endpoint), 'requestId', endpoint),
    updatedAt: decodeNullableString(requiredField(record, 'updatedAt', endpoint), 'updatedAt', endpoint),
  }
}

type RecordValue = Record<string, unknown>

function responseError(endpoint: string, detail: string): AiSummaryResponseError {
  return new AiSummaryResponseError(`Invalid AI summary response for ${endpoint}: ${detail}`)
}

function asRecord(value: unknown, endpoint: string): RecordValue {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw responseError(endpoint, 'expected a direct object')
  }
  return value as RecordValue
}

function requiredField(record: RecordValue, field: string, endpoint: string): unknown {
  if (!Object.prototype.hasOwnProperty.call(record, field)) {
    throw responseError(endpoint, `missing ${field}`)
  }
  return record[field]
}

function decodeId(value: unknown, field: string, endpoint: string): Id {
  if (typeof value === 'number' && Number.isSafeInteger(value)) return value
  if (typeof value === 'string' && value.trim()) return value
  throw responseError(endpoint, `${field} must be a non-empty id`)
}

function decodeString(value: unknown, field: string, endpoint: string): string {
  if (typeof value === 'string') return value
  throw responseError(endpoint, `${field} must be a string`)
}

function decodeNullableString(value: unknown, field: string, endpoint: string): string | null {
  if (value === null) return null
  return decodeString(value, field, endpoint)
}

function decodeNullableNonEmptyString(value: unknown, field: string, endpoint: string): string | null {
  if (value === null) return null
  const decoded = decodeString(value, field, endpoint).trim()
  if (decoded) return decoded
  throw responseError(endpoint, `${field} must be null or a non-empty string`)
}

function decodeBoolean(value: unknown, field: string, endpoint: string): boolean {
  if (typeof value === 'boolean') return value
  throw responseError(endpoint, `${field} must be a boolean`)
}

function decodeNonNegativeInteger(value: unknown, field: string, endpoint: string): number {
  if (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) return value
  throw responseError(endpoint, `${field} must be a non-negative safe integer`)
}

function decodeNullableNonNegativeInteger(value: unknown, field: string, endpoint: string): number | null {
  if (value === null) return null
  return decodeNonNegativeInteger(value, field, endpoint)
}

function decodeStatus(value: unknown, endpoint: string): AiSummaryStatus {
  const statuses: AiSummaryStatus[] = ['IDLE', 'PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SUPERSEDED']
  if (typeof value === 'string' && statuses.includes(value as AiSummaryStatus)) return value as AiSummaryStatus
  throw responseError(endpoint, 'status is invalid')
}

export function createChatStream(
  messages: AiChatMessage[],
  onMessage?: ChatStreamMessageHandler,
  onError?: ChatStreamErrorHandler,
  onComplete?: ChatStreamCompleteHandler,
): ChatStreamHandle {
  const apiBase = (import.meta.env.VITE_API_BASE_URL || '/api/v1').replace(/\/+$/, '')
  const controller = new AbortController()
  // Capture the request payload before the asynchronous stream starts. The
  // chat widget owns its history, so a later route/session reset must not
  // mutate the body of a request that is already in flight.
  const requestMessages = messages.map(message => ({ role: message.role, content: message.content }))
  let cancelled = false
  let reader: ReadableStreamDefaultReader<Uint8Array> | null = null
  let responseBody: ReadableStream<Uint8Array> | null = null

  const disposeStream = (): void => {
    const activeReader = reader
    const body = responseBody
    reader = null
    responseBody = null
    // Cleanup must not delay completion or replace the original stream error.
    void (async () => {
      try {
        if (activeReader) {
          try {
            await activeReader.cancel()
          } finally {
            activeReader.releaseLock?.()
          }
        } else {
          await body?.cancel()
        }
      } catch (_) {
        // The transport may already have aborted or closed the reader.
      }
    })()
  }

  const dispatchEvent = (event: SseEvent): boolean => {
    if (cancelled) return false
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
    if (cancelled) return false
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
      const generation = captureAuthGeneration()
      assertAutomaticRequestOwnsCurrentSession(token)
      if (hasRealToken(token)) {
        headers.Authorization = `Bearer ${token}`
      }

      const response = await fetch(apiBase + '/ai/chat/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify({ messages: requestMessages }),
        signal: controller.signal,
      })
      responseBody = response.body
      if (cancelled) return
      if (!response.ok) {
        if (response.status === 401) handleCurrentTokenUnauthorized(token, generation)
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
      const activeReader = reader
      const decoder = new TextDecoder()
      let buffer = ''

      const consumeEventBlock = (block: string): void => {
        if (cancelled) return
        const event = parseSseEventBlock(block)
        if (!event) return
        sawDoneMarker = dispatchEvent(event) || sawDoneMarker
      }

      while (!cancelled) {
        const result = await activeReader.read()
        // Abort/cancel may resolve read() with a chunk that was already
        // queued. Check again before decoding or dispatching that chunk.
        if (cancelled) break
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
      if (cancelled) return
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
    } finally {
      disposeStream()
    }
  })()

  return {
    cancel: () => {
      if (cancelled) return
      cancelled = true
      controller.abort()
      disposeStream()
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

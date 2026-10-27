import type { Id, Thread } from '@/types'
import { isAxiosError } from 'axios'

type WriteContext = { kind: 'create' } | { kind: 'update'; threadId: Id }

export class ThreadWriteUnconfirmedError extends Error {
  readonly code = 'THREAD_WRITE_UNCONFIRMED'
  readonly cause?: unknown

  constructor(cause?: unknown) {
    super('帖子写入结果暂未确认')
    this.name = 'ThreadWriteUnconfirmedError'
    this.cause = cause
  }
}

export function isThreadWriteUnconfirmedError(value: unknown): value is ThreadWriteUnconfirmedError {
  return value instanceof ThreadWriteUnconfirmedError
}

export function rethrowThreadWriteFailure(error: unknown): never {
  // A lost response or a server/proxy error cannot establish whether the write committed.
  if (isAxiosError(error) && (!error.response || error.response.status >= 500)) {
    throw new ThreadWriteUnconfirmedError(error)
  }
  throw error
}

function responseId(value: unknown): string | undefined {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : undefined
  return typeof value === 'string' && /^[1-9]\d*$/.test(value) ? value : undefined
}

function requestedId(value: Id): string | undefined {
  if (typeof value === 'number') return responseId(value)
  const text = value.trim()
  return /^\d+$/.test(text) ? text.replace(/^0+/, '') || undefined : undefined
}

// Confirm the fields required by the write callers without interpreting optional
// DTO metadata or changing the avatar normalization used by read endpoints.
export function decodeThreadWriteResponse(value: unknown, context: WriteContext): Thread {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new ThreadWriteUnconfirmedError()
  const record = value as Record<string, unknown>
  if (!Object.prototype.hasOwnProperty.call(record, 'id') || !Object.prototype.hasOwnProperty.call(record, 'title')) {
    throw new ThreadWriteUnconfirmedError()
  }
  const id = responseId(record.id)
  if (!id || typeof record.title !== 'string') throw new ThreadWriteUnconfirmedError()
  if (context.kind === 'update' && id !== requestedId(context.threadId)) throw new ThreadWriteUnconfirmedError()
  return value as Thread
}

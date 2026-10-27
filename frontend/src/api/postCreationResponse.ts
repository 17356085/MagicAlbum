import { isAxiosError } from 'axios'
import type { Id, Post } from '@/types'

type PostCreationContext = Readonly<{ threadId: Id; replyToPostId?: Id | null }>

export class PostCreationUnconfirmedError extends Error {
  readonly code = 'POST_CREATE_UNCONFIRMED'
  readonly cause?: unknown

  constructor(cause?: unknown) {
    super('评论发送结果暂未确认')
    this.name = 'PostCreationUnconfirmedError'
    this.cause = cause
  }
}

export function isPostCreationUnconfirmedError(value: unknown): value is PostCreationUnconfirmedError {
  return value instanceof PostCreationUnconfirmedError
}

export function rethrowPostCreationFailure(error: unknown): never {
  if (isAxiosError(error) && (!error.response || error.response.status >= 500)) {
    throw new PostCreationUnconfirmedError(error)
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

export function decodePostCreationResponse(value: unknown, context: PostCreationContext): Post {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new PostCreationUnconfirmedError()
  const record = value as Record<string, unknown>
  const expectedThread = requestedId(context.threadId)
  const expectedParent = context.replyToPostId == null ? null : requestedId(context.replyToPostId)
  const actualParent = record.replyToPostId == null ? null : responseId(record.replyToPostId)
  if (!Object.prototype.hasOwnProperty.call(record, 'id') || !Object.prototype.hasOwnProperty.call(record, 'threadId')
    || !responseId(record.id) || !expectedThread || responseId(record.threadId) !== expectedThread
    || expectedParent === undefined || actualParent !== expectedParent
    || typeof (record.content ?? record.contentMd) !== 'string') {
    throw new PostCreationUnconfirmedError()
  }
  return value as Post
}

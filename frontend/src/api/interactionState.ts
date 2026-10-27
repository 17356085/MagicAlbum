import type { Id, PostLikeState, ThreadLikeState, UserFollowStatus } from '@/types'

const SAFE_ERROR_MESSAGE = '互动状态暂不可用，请稍后重试'
const DECIMAL_ID_PATTERN = /^[1-9]\d*$/

export type InteractionResponseReason =
  | 'invalid_response'
  | 'invalid_requested_id'
  | 'mismatched_target'
  | 'conflicting_relationship_fields'

export class InteractionResponseError extends Error {
  readonly endpoint: string
  readonly field?: string
  readonly reason: InteractionResponseReason

  constructor(
    endpoint: string,
    field: string | undefined,
    reason: InteractionResponseReason = 'invalid_response',
  ) {
    super(SAFE_ERROR_MESSAGE)
    this.name = 'InteractionResponseError'
    this.endpoint = endpoint
    this.field = field
    this.reason = reason
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

type ResponseRecord = Record<string, unknown>

function fail(
  endpoint: string,
  field: string | undefined,
  reason: InteractionResponseReason = 'invalid_response',
): never {
  throw new InteractionResponseError(endpoint, field, reason)
}

function isResponseRecord(value: unknown): value is ResponseRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function responseRecord(data: unknown, endpoint: string): ResponseRecord {
  if (!isResponseRecord(data)) fail(endpoint, undefined)
  return data
}

function hasOwn(record: ResponseRecord, field: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, field)
}

function requiredBoolean(record: ResponseRecord, field: string, endpoint: string): boolean {
  if (!hasOwn(record, field) || typeof record[field] !== 'boolean') {
    fail(endpoint, field)
  }
  return record[field] as boolean
}

function requiredCount(record: ResponseRecord, field: string, endpoint: string): number {
  const value = record[field]
  if (
    !hasOwn(record, field) ||
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    !Number.isSafeInteger(value) ||
    value < 0
  ) {
    fail(endpoint, field)
  }
  return value
}

function normalizeId(
  value: unknown,
  endpoint: string,
  field: string,
  reason: InteractionResponseReason = 'invalid_response',
): string {
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value <= 0) fail(endpoint, field, reason)
    return String(value)
  }
  if (typeof value === 'string' && DECIMAL_ID_PATTERN.test(value)) return value
  fail(endpoint, field, reason)
}

function assertMatchingId(
  record: ResponseRecord,
  requestedId: Id,
  endpoint: string,
  field: string,
): void {
  if (!hasOwn(record, field)) fail(endpoint, field)
  const actual = normalizeId(record[field], endpoint, field)
  const expected = normalizeId(requestedId, endpoint, 'requestedId', 'invalid_requested_id')
  if (actual !== expected) fail(endpoint, field, 'mismatched_target')
}

export function decodePostLikeResponse(
  data: unknown,
  requestedId: Id,
  endpoint: string,
): PostLikeState {
  const record = responseRecord(data, endpoint)
  assertMatchingId(record, requestedId, endpoint, 'postId')
  const liked = requiredBoolean(record, 'liked', endpoint)
  const likeCount = requiredCount(record, 'likeCount', endpoint)
  return {
    ...record,
    postId: record.postId as Id,
    liked,
    likeCount,
  } as PostLikeState
}

export function decodeThreadLikeResponse(
  data: unknown,
  requestedId: Id,
  endpoint: string,
): ThreadLikeState {
  const record = responseRecord(data, endpoint)
  assertMatchingId(record, requestedId, endpoint, 'threadId')
  const liked = requiredBoolean(record, 'liked', endpoint)
  const likeCount = requiredCount(record, 'likeCount', endpoint)
  return {
    ...record,
    threadId: record.threadId as Id,
    liked,
    likeCount,
  } as ThreadLikeState
}

export function decodeBookmarkResponse(
  data: unknown,
  endpoint: string,
): { bookmarked: boolean } {
  const record = responseRecord(data, endpoint)
  const bookmarked = requiredBoolean(record, 'bookmarked', endpoint)
  return { ...record, bookmarked }
}

export function decodeUserFollowResponse(
  data: unknown,
  requestedId: Id,
  endpoint: string,
): UserFollowStatus {
  const record = responseRecord(data, endpoint)
  assertMatchingId(record, requestedId, endpoint, 'userId')

  const hasFollowing = hasOwn(record, 'following')
  const hasFollowedByMe = hasOwn(record, 'followedByMe')
  if (!hasFollowing && !hasFollowedByMe) {
    fail(endpoint, 'following')
  }

  const following = hasFollowing ? requiredBoolean(record, 'following', endpoint) : undefined
  const followedByMe = hasFollowedByMe
    ? requiredBoolean(record, 'followedByMe', endpoint)
    : undefined
  if (following !== undefined && followedByMe !== undefined && following !== followedByMe) {
    fail(endpoint, 'followedByMe', 'conflicting_relationship_fields')
  }

  const relationship = following ?? followedByMe
  if (relationship === undefined) fail(endpoint, 'following')
  const followingMe = requiredBoolean(record, 'followingMe', endpoint)
  const followerCount = requiredCount(record, 'followerCount', endpoint)
  const followingCount = requiredCount(record, 'followingCount', endpoint)

  return {
    ...record,
    userId: record.userId as Id,
    following: relationship,
    followedByMe: relationship,
    followingMe,
    followerCount,
    followingCount,
  } as UserFollowStatus
}

import api from './client'
import { decodePageResponse, PageResponseError } from './pagination'
import { getHomeOverview, homeOverviewEnabled } from './homeOverview'
import type { PageResult, Thread } from '@/types'

export type FollowingFeedItem = Thread

export interface FollowingFeedCursorPage extends PageResult<FollowingFeedItem> {
  /** Present only when the server accepted the cursor pagination contract. */
  nextCursor: string | null
  previousCursor: string | null
}

export type FollowingFeedPage = PageResult<FollowingFeedItem> | FollowingFeedCursorPage

export interface ListFollowingFeedQuery {
  page?: number
  size?: number
  /** Opt in to the adjacent cursor contract without changing legacy callers. */
  pagination?: 'cursor'
  after?: string
  before?: string
}

export async function listFollowingFeed(
  { page = 1, size = 10, pagination, after, before }: ListFollowingFeedQuery = {},
  preferHomeOverview = false,
): Promise<FollowingFeedPage> {
  if (after !== undefined && before !== undefined) {
    throw new PageResponseError('Invalid following feed cursor request: after and before are mutually exclusive')
  }

  if (homeOverviewEnabled && preferHomeOverview && pagination === undefined && after === undefined && before === undefined && page === 1 && size === 4) {
    const overview = await getHomeOverview()
    if (overview?.following != null) {
      try {
        return normalizeFeed(overview.following, page, size)
      } catch (_) {
        // The source endpoint still handles a partial overview.
      }
    }
  }
  const params: Record<string, number | string> = { page, size }
  if (pagination === 'cursor') params.pagination = pagination
  if (after !== undefined) params.after = after
  if (before !== undefined) params.before = before

  const { data } = await api.get('/threads/following', { params })
  return normalizeFeed(data, page, size)
}

function normalizeFeed(data: unknown, page: number, size: number): FollowingFeedPage {
  const decoded = decodePageResponse<Thread & { authorAvatar?: string }>(data, { page, size, endpoint: '/threads/following' })
  const normalized = {
    ...decoded,
    items: decoded.items.map((item) => ({
      ...item,
      authorAvatarUrl: item.authorAvatarUrl || item.authorAvatar || '',
    })),
  }

  if (!isRecord(data)) return normalized

  const hasNextCursor = hasOwn(data, 'nextCursor')
  const hasPreviousCursor = hasOwn(data, 'previousCursor')
  if (!hasNextCursor && !hasPreviousCursor) return normalized
  if (!hasNextCursor || !hasPreviousCursor) {
    throw new PageResponseError('Invalid paginated response for /threads/following: cursor metadata must include nextCursor and previousCursor')
  }

  return {
    ...normalized,
    nextCursor: decodeCursor(data.nextCursor, 'nextCursor'),
    previousCursor: decodeCursor(data.previousCursor, 'previousCursor'),
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function hasOwn(record: Record<string, unknown>, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, key)
}

function decodeCursor(value: unknown, field: 'nextCursor' | 'previousCursor'): string | null {
  if (value === null || typeof value === 'string') return value
  throw new PageResponseError(`Invalid paginated response for /threads/following: ${field} must be a string or null`)
}

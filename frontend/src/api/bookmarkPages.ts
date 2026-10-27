import api from './client'
import { decodePageResponse, PageResponseError } from './pagination'
import type { Id, PageResult } from '@/types'

export interface BookmarkItem {
  threadId: Id
  title: string
  sectionName: string
  bookmarkedAt: string
}

export type BookmarkPage = PageResult<BookmarkItem> & {
  /** False means the page total is only this response's item count. */
  totalKnown?: boolean
}

interface ListBookmarksQuery {
  page?: number
  size?: number
}

const DECIMAL_ID_PATTERN = /^[1-9]\d*$/

function decodeBookmarkItem(value: unknown, index: number, endpoint: string): BookmarkItem {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new PageResponseError(`Invalid paginated response for ${endpoint}: bookmark item ${index} must be an object`)
  }

  const record = value as Record<string, unknown>
  const hasOwn = (field: string): boolean => Object.prototype.hasOwnProperty.call(record, field)
  const threadId = record.threadId
  const validNumericId = typeof threadId === 'number' && Number.isSafeInteger(threadId) && threadId > 0
  const validStringId = typeof threadId === 'string' && DECIMAL_ID_PATTERN.test(threadId)
  if (!hasOwn('threadId') || (!validNumericId && !validStringId)) {
    throw new PageResponseError(`Invalid paginated response for ${endpoint}: bookmark item ${index} has an invalid threadId`)
  }
  if (!hasOwn('title') || typeof record.title !== 'string') {
    throw new PageResponseError(`Invalid paginated response for ${endpoint}: bookmark item ${index} has an invalid title`)
  }

  const sectionName = hasOwn('sectionName') && record.sectionName !== null && record.sectionName !== undefined
    ? record.sectionName
    : ''
  const bookmarkedAt = hasOwn('bookmarkedAt') && record.bookmarkedAt !== null && record.bookmarkedAt !== undefined
    ? record.bookmarkedAt
    : ''
  if (typeof sectionName !== 'string' || typeof bookmarkedAt !== 'string') {
    throw new PageResponseError(`Invalid paginated response for ${endpoint}: bookmark item ${index} has invalid presentation fields`)
  }

  return {
    ...record,
    threadId: threadId as Id,
    title: record.title,
    sectionName,
    bookmarkedAt,
  }
}

export async function listBookmarks({ page = 1, size = 10 }: ListBookmarksQuery = {}): Promise<BookmarkPage> {
  const { data } = await api.get('/users/me/bookmarks', { params: { page, size } })
  const endpoint = '/users/me/bookmarks'
  const decoded = decodePageResponse<unknown>(data, { page, size, endpoint })
  const seenThreadIds = new Set<string>()
  const items = decoded.items.map((value, index) => {
    const item = decodeBookmarkItem(value, index, endpoint)
    const target = String(item.threadId)
    if (seenThreadIds.has(target)) {
      throw new PageResponseError(`Invalid paginated response for ${endpoint}: duplicate bookmark threadId`)
    }
    seenThreadIds.add(target)
    return item
  })
  const totalKnown = typeof data === 'object'
    && data !== null
    && !Array.isArray(data)
    && Object.prototype.hasOwnProperty.call(data, 'total')
  return { ...decoded, items, totalKnown }
}

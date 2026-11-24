import api from './client'
import { decodePageResponse } from './pagination'
import { getHomeOverview, homeOverviewEnabled } from './homeOverview'
import type { PageResult, Thread } from '@/types'

export type FollowingFeedItem = Thread

interface ListFollowingFeedQuery {
  page?: number
  size?: number
}

export async function listFollowingFeed(
  { page = 1, size = 10 }: ListFollowingFeedQuery = {},
  preferHomeOverview = false,
): Promise<PageResult<FollowingFeedItem>> {
  if (homeOverviewEnabled && preferHomeOverview && page === 1 && size === 4) {
    const overview = await getHomeOverview()
    if (overview?.following != null) {
      try {
        return normalizeFeed(overview.following, page, size)
      } catch (_) {
        // The source endpoint still handles a partial overview.
      }
    }
  }
  const { data } = await api.get('/threads/following', { params: { page, size } })
  return normalizeFeed(data, page, size)
}

function normalizeFeed(data: unknown, page: number, size: number): PageResult<FollowingFeedItem> {
  const decoded = decodePageResponse<Thread & { authorAvatar?: string }>(data, { page, size, endpoint: '/threads/following' })
  return {
    ...decoded,
    items: decoded.items.map((item) => ({
      ...item,
      authorAvatarUrl: item.authorAvatarUrl || item.authorAvatar || '',
    })),
  }
}

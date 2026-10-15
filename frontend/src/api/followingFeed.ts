import api from './client'
import { decodePageResponse } from './pagination'
import type { PageResult, Thread } from '@/types'

export type FollowingFeedItem = Thread

interface ListFollowingFeedQuery {
  page?: number
  size?: number
}

export async function listFollowingFeed(
  { page = 1, size = 10 }: ListFollowingFeedQuery = {},
): Promise<PageResult<FollowingFeedItem>> {
  const { data } = await api.get('/threads/following', { params: { page, size } })
  const decoded = decodePageResponse<Thread & { authorAvatar?: string }>(data, { page, size, endpoint: '/threads/following' })
  return {
    ...decoded,
    items: decoded.items.map((item) => ({
      ...item,
      authorAvatarUrl: item.authorAvatarUrl || item.authorAvatar || '',
    })),
  }
}

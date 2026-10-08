import api from './client'
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
  return {
    items: (data.items || []).map((item: Thread & { authorAvatar?: string }) => ({
      ...item,
      authorAvatarUrl: item.authorAvatarUrl || item.authorAvatar || '',
    })),
    page: Number(data.page ?? page),
    size: Number(data.size ?? size),
    total: Number(data.total ?? 0),
  }
}

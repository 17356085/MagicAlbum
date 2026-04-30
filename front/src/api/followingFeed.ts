import { listUserFollowing, listUserThreads } from '@/api/users'
import type { Id, PageResult, Thread, User } from '@/types'

export interface FollowingFeedItem extends Thread {
  followedAuthor: User
}

interface ListFollowingFeedQuery {
  page?: number
  size?: number
  followingSize?: number
  perUserSize?: number
}

function normalizeThreadPage(data: PageResult<Thread> | Thread[], page: number, size: number): PageResult<Thread> {
  if (Array.isArray(data)) {
    return { items: data, page, size, total: data.length }
  }
  return data || { items: [], page, size, total: 0 }
}

function timeOf(item: Thread): number {
  const raw = item.createdAt || item.updatedAt || ''
  const time = new Date(raw).getTime()
  return Number.isFinite(time) ? time : 0
}

export async function listFollowingFeed(
  userId: Id,
  { page = 1, size = 10, followingSize = 50, perUserSize = 8 }: ListFollowingFeedQuery = {},
): Promise<PageResult<FollowingFeedItem>> {
  const followingPage = await listUserFollowing(userId, { page: 1, size: followingSize })
  const followingUsers = followingPage.items || []
  if (followingUsers.length === 0) {
    return { items: [], page, size, total: 0 }
  }

  const batches = await Promise.all(
    followingUsers.map(async (followedUser) => {
      try {
        const data = await listUserThreads(followedUser.id, { page: 1, size: perUserSize })
        const normalized = normalizeThreadPage(data, 1, perUserSize)
        return (normalized.items || []).map((thread) => ({
          ...thread,
          authorId: thread.authorId || followedUser.id,
          authorUsername: thread.authorUsername || followedUser.username,
          authorNickname: thread.authorNickname || followedUser.nickname,
          authorAvatarUrl: thread.authorAvatarUrl || followedUser.avatarUrl,
          followedAuthor: followedUser,
        }))
      } catch (_) {
        return []
      }
    }),
  )

  const allItems = batches.flat().sort((a, b) => {
    const diff = timeOf(b) - timeOf(a)
    if (diff !== 0) return diff
    return Number(b.id || 0) - Number(a.id || 0)
  })
  const start = Math.max(0, (Math.max(page, 1) - 1) * size)
  return {
    items: allItems.slice(start, start + size),
    page,
    size,
    total: allItems.length,
  }
}

import api from './client'
import { decodePageResponse } from './pagination'
import { normalizeThread } from './threadNormalization'
import type { Id, PageResult, Post, Thread } from '@/types'

export { updateThread } from './threadWrites'

interface MyListQuery {
  q?: string
  sectionId?: Id
  page?: number
  size?: number
  sort?: string
}

interface MyPostsQuery extends MyListQuery {
  threadId?: Id
}

// 我的帖子
export async function listMyThreads({
  q,
  sectionId,
  page = 1,
  size = 20,
  sort,
}: MyListQuery = {}): Promise<PageResult<Thread>> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (sectionId) params.sectionId = sectionId
  if (sort) params.sort = sort
  params.page = page
  params.size = size
  const { data } = await api.get('/users/me/threads', { params })
  const decoded = decodePageResponse<Thread & { authorAvatar?: string }>(data, { page, size, endpoint: '/users/me/threads' })
  return { ...decoded, items: decoded.items.map((item) => normalizeThread(item)) }
}

// 我的评论
export async function listMyPosts({
  q,
  threadId,
  sectionId,
  page = 1,
  size = 20,
  sort,
}: MyPostsQuery = {}): Promise<PageResult<Post>> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (threadId) params.threadId = threadId
  if (sectionId) params.sectionId = sectionId
  if (sort) params.sort = sort
  params.page = page
  params.size = size
  const { data } = await api.get('/users/me/posts', { params })
  return decodePageResponse<Post>(data, { page, size, endpoint: '/users/me/posts' })
}

// 线程删除（本人资源）
export async function deleteThread(id: Id): Promise<unknown> {
  const { data } = await api.delete(`/threads/${id}`)
  return data
}

// 评论删除（本人资源）
export async function deletePost(id: Id): Promise<unknown> {
  const { data } = await api.delete(`/posts/${id}`)
  return data
}

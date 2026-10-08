import api from './client'
import type { Id, PageResult, Post, Thread, UpdateThreadPayload } from '@/types'

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
}: MyListQuery = {}): Promise<PageResult<Thread> | Thread[]> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (sectionId) params.sectionId = sectionId
  if (sort) params.sort = sort
  params.page = page
  params.size = size
  const { data } = await api.get('/users/me/threads', { params })
  return data
}

// 我的评论
export async function listMyPosts({
  q,
  threadId,
  sectionId,
  page = 1,
  size = 20,
  sort,
}: MyPostsQuery = {}): Promise<PageResult<Post> | Post[]> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (threadId) params.threadId = threadId
  if (sectionId) params.sectionId = sectionId
  if (sort) params.sort = sort
  params.page = page
  params.size = size
  const { data } = await api.get('/users/me/posts', { params })
  return data
}

// 线程编辑与删除（本人资源）
export async function updateThread(id: Id, payload: UpdateThreadPayload): Promise<Thread> {
  const { data } = await api.patch(`/threads/${id}`, payload)
  return data
}

export async function deleteThread(id: Id): Promise<unknown> {
  const { data } = await api.delete(`/threads/${id}`)
  return data
}

// 评论删除（本人资源）
export async function deletePost(id: Id): Promise<unknown> {
  const { data } = await api.delete(`/posts/${id}`)
  return data
}

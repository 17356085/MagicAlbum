import api from './client'
import { decodeThreadLikeResponse } from './interactionState'
import { decodePageResponse } from './pagination'
import { normalizeThread } from './threadNormalization'
import type { Id, PageResult, Thread, ThreadLikeState } from '@/types'

export { normalizeThread } from './threadNormalization'
export { createThread } from './threadWrites'

interface ListThreadsQuery {
  q?: string
  tag?: string
  sectionId?: Id
  page?: number
  size?: number
}

interface ListThreadRankingQuery {
  sectionId?: Id
  page?: number
  size?: number
}

function normalizeThreadResult(data: unknown, page: number, size: number, endpoint: string): PageResult<Thread> {
  const decoded = decodePageResponse<Thread & { authorAvatar?: string }>(data, { page, size, endpoint })
  return {
    ...decoded,
    items: decoded.items.map((item) => normalizeThread(item)),
  }
}

export async function listThreads({
  q,
  tag,
  sectionId,
  page = 1,
  size = 20,
}: ListThreadsQuery = {}): Promise<PageResult<Thread>> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (tag && tag.trim()) params.tag = tag.trim()
  if (sectionId) params.sectionId = sectionId
  params.page = page
  params.size = size
  const { data } = await api.get('/threads', { params })
  return normalizeThreadResult(data, page, size, '/threads')
}

export async function getThread(id: Id): Promise<Thread> {
  const { data } = await api.get(`/threads/${id}`)
  return normalizeThread(data)
}

export async function listThreadRanking({
  sectionId,
  page = 1,
  size = 20,
}: ListThreadRankingQuery = {}): Promise<PageResult<Thread>> {
  const params: Record<string, string | number> = { page, size }
  if (sectionId) params.sectionId = sectionId
  const { data } = await api.get('/threads/ranking', { params })
  return normalizeThreadResult(data, page, size, '/threads/ranking')
}

export async function getThreadLikeState(id: Id): Promise<ThreadLikeState> {
  const { data } = await api.get(`/threads/${id}/like`)
  return decodeThreadLikeResponse(data, id, `/threads/${id}/like`)
}

export async function likeThread(id: Id): Promise<ThreadLikeState> {
  const { data } = await api.post(`/threads/${id}/like`)
  return decodeThreadLikeResponse(data, id, `/threads/${id}/like`)
}

export async function unlikeThread(id: Id): Promise<ThreadLikeState> {
  const { data } = await api.delete(`/threads/${id}/like`)
  return decodeThreadLikeResponse(data, id, `/threads/${id}/like`)
}

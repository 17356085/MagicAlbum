import api from './client'
import type { CreateThreadPayload, Id, PageResult, Thread, ThreadLikeState } from '@/types'

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

function normalizeThread(raw: Thread & { authorAvatar?: string }): Thread {
  if (!raw) return raw
  return {
    ...raw,
    authorAvatarUrl: raw.authorAvatarUrl || raw.authorAvatar || '',
  }
}

function normalizeThreadResult(data: PageResult<Thread> | Thread[], page: number, size: number): PageResult<Thread> | Thread[] {
  if (Array.isArray(data)) {
    return data.map((item) => normalizeThread(item as Thread & { authorAvatar?: string }))
  }
  if (!data || !Array.isArray(data.items)) {
    return data
  }
  return {
    ...data,
    page: Number(data.page ?? page),
    size: Number(data.size ?? size),
    items: data.items.map((item) => normalizeThread(item as Thread & { authorAvatar?: string })),
  }
}

export async function createThread(payload: CreateThreadPayload): Promise<Thread> {
  const { data } = await api.post('/threads', payload)
  return normalizeThread(data)
}

export async function listThreads({
  q,
  tag,
  sectionId,
  page = 1,
  size = 20,
}: ListThreadsQuery = {}): Promise<PageResult<Thread> | Thread[]> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (tag && tag.trim()) params.tag = tag.trim()
  if (sectionId) params.sectionId = sectionId
  params.page = page
  params.size = size
  const { data } = await api.get('/threads', { params })
  return normalizeThreadResult(data, page, size)
}

export async function getThread(id: Id): Promise<Thread> {
  const { data } = await api.get(`/threads/${id}`)
  return normalizeThread(data)
}

export async function listThreadRanking({
  sectionId,
  page = 1,
  size = 20,
}: ListThreadRankingQuery = {}): Promise<PageResult<Thread> | Thread[]> {
  const params: Record<string, string | number> = { page, size }
  if (sectionId) params.sectionId = sectionId
  const { data } = await api.get('/threads/ranking', { params })
  return normalizeThreadResult(data, page, size)
}

export async function getThreadLikeState(id: Id): Promise<ThreadLikeState> {
  const { data } = await api.get(`/threads/${id}/like`)
  return data
}

export async function likeThread(id: Id): Promise<ThreadLikeState> {
  const { data } = await api.post(`/threads/${id}/like`)
  return data
}

export async function unlikeThread(id: Id): Promise<ThreadLikeState> {
  const { data } = await api.delete(`/threads/${id}/like`)
  return data
}

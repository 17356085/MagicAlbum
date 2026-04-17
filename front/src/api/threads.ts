import api from './client'
import type { CreateThreadPayload, Id, PageResult, Thread } from '@/types'

interface ListThreadsQuery {
  q?: string
  sectionId?: Id
  page?: number
  size?: number
}

export async function createThread(payload: CreateThreadPayload): Promise<Thread> {
  const { data } = await api.post('/threads', payload)
  return data
}

export async function listThreads({
  q,
  sectionId,
  page = 1,
  size = 20,
}: ListThreadsQuery = {}): Promise<PageResult<Thread> | Thread[]> {
  const params: Record<string, string | number> = {}
  if (q && q.trim()) params.q = q.trim()
  if (sectionId) params.sectionId = sectionId
  params.page = page
  params.size = size
  const { data } = await api.get('/threads', { params })
  return data
}

export async function getThread(id: Id): Promise<Thread> {
  const { data } = await api.get(`/threads/${id}`)
  return data
}

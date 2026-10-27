import api from './client'
import { normalizeThread } from './threadNormalization'
import { decodeThreadWriteResponse, rethrowThreadWriteFailure } from './threadWriteResponse'
import type { CreateThreadPayload, Id, Thread, UpdateThreadPayload } from '@/types'

export async function createThread(payload: CreateThreadPayload): Promise<Thread> {
  const { data } = await api.post<unknown>('/threads', payload).catch(rethrowThreadWriteFailure)
  return normalizeThread(decodeThreadWriteResponse(data, { kind: 'create' }))
}

export async function updateThread(id: Id, payload: UpdateThreadPayload): Promise<Thread> {
  const { data } = await api.patch<unknown>(`/threads/${id}`, payload).catch(rethrowThreadWriteFailure)
  return decodeThreadWriteResponse(data, { kind: 'update', threadId: id })
}

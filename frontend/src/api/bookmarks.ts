import api from './client'
import { decodeBookmarkResponse } from './interactionState'
import type { Id } from '@/types'

export { listBookmarks } from './bookmarkPages'
export type { BookmarkItem, BookmarkPage } from './bookmarkPages'

export interface ThreadBookmarkState {
  bookmarked: boolean
}

export async function getThreadBookmarkState(id: Id): Promise<ThreadBookmarkState> {
  const { data } = await api.get(`/threads/${id}/bookmark`)
  return decodeBookmarkResponse(data, `/threads/${id}/bookmark`)
}

export async function bookmarkThread(id: Id): Promise<ThreadBookmarkState> {
  const { data } = await api.post(`/threads/${id}/bookmark`)
  return decodeBookmarkResponse(data, `/threads/${id}/bookmark`)
}

export async function unbookmarkThread(id: Id): Promise<ThreadBookmarkState> {
  const { data } = await api.delete(`/threads/${id}/bookmark`)
  return decodeBookmarkResponse(data, `/threads/${id}/bookmark`)
}

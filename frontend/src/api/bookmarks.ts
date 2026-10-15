import api from './client'
import { decodePageResponse } from './pagination'
import type { Id, PageResult } from '@/types'

export interface ThreadBookmarkState {
  bookmarked: boolean
}

export interface BookmarkItem {
  threadId: Id
  title: string
  sectionName: string
  bookmarkedAt: string
}

export type BookmarkPage = PageResult<BookmarkItem>

interface ListBookmarksQuery {
  page?: number
  size?: number
}

export async function getThreadBookmarkState(id: Id): Promise<ThreadBookmarkState> {
  const { data } = await api.get(`/threads/${id}/bookmark`)
  return data
}

export async function bookmarkThread(id: Id): Promise<ThreadBookmarkState> {
  const { data } = await api.post(`/threads/${id}/bookmark`)
  return data
}

export async function unbookmarkThread(id: Id): Promise<ThreadBookmarkState> {
  const { data } = await api.delete(`/threads/${id}/bookmark`)
  return data
}

export async function listBookmarks({ page = 1, size = 10 }: ListBookmarksQuery = {}): Promise<BookmarkPage> {
  const { data } = await api.get('/users/me/bookmarks', { params: { page, size } })
  return decodePageResponse<BookmarkItem>(data, { page, size, endpoint: '/users/me/bookmarks' })
}

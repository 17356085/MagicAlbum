import api from './client'
import type { CreatePostPayload, Id, PageResult, Post, UpdatePostPayload } from '@/types'

interface ListPostsQuery {
  page?: number
  size?: number
}

export async function listPosts(
  threadId: Id,
  { page = 1, size = 20 }: ListPostsQuery = {},
): Promise<PageResult<Post> | Post[]> {
  const params = { page, size }
  const { data } = await api.get(`/threads/${threadId}/posts`, { params })
  return data
}

export async function createPost(threadId: Id, payload: CreatePostPayload): Promise<Post> {
  const { data } = await api.post(`/threads/${threadId}/posts`, payload)
  return data
}

export async function updatePost(id: Id, payload: UpdatePostPayload): Promise<Post> {
  const { data } = await api.patch(`/posts/${id}`, payload)
  return data
}

export async function deletePost(id: Id): Promise<void> {
  await api.delete(`/posts/${id}`)
}

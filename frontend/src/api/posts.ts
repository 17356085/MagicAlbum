import api from './client'
import { decodePostLikeResponse } from './interactionState'
import { decodePageResponse } from './pagination'
import { decodePostCreationResponse, rethrowPostCreationFailure } from './postCreationResponse'
import type { CreatePostPayload, Id, PageResult, Post, PostLikeState, UpdatePostPayload } from '@/types'

interface ListPostsQuery {
  page?: number
  size?: number
  sort?: 'time' | 'likeCount'
}

export async function listPosts(
  threadId: Id,
  { page = 1, size = 20, sort = 'time' }: ListPostsQuery = {},
): Promise<PageResult<Post>> {
  const params = { page, size, sort }
  const { data } = await api.get(`/threads/${threadId}/posts`, { params })
  return decodePageResponse<Post>(data, { page, size, endpoint: `/threads/${threadId}/posts` })
}

export async function createPost(threadId: Id, payload: CreatePostPayload): Promise<Post> {
  const context = { threadId, replyToPostId: payload.replyToPostId }
  const { data } = await api.post<unknown>(`/threads/${threadId}/posts`, payload).catch(rethrowPostCreationFailure)
  return decodePostCreationResponse(data, context)
}

export async function updatePost(id: Id, payload: UpdatePostPayload): Promise<Post> {
  const { data } = await api.patch(`/posts/${id}`, payload)
  return data
}

export async function deletePost(id: Id): Promise<void> {
  await api.delete(`/posts/${id}`)
}

export async function getPostLikeState(id: Id): Promise<PostLikeState> {
  const { data } = await api.get(`/posts/${id}/like`)
  return decodePostLikeResponse(data, id, `/posts/${id}/like`)
}

export async function likePost(id: Id): Promise<PostLikeState> {
  const { data } = await api.post(`/posts/${id}/like`)
  return decodePostLikeResponse(data, id, `/posts/${id}/like`)
}

export async function unlikePost(id: Id): Promise<PostLikeState> {
  const { data } = await api.delete(`/posts/${id}/like`)
  return decodePostLikeResponse(data, id, `/posts/${id}/like`)
}

import api from './client'

export async function listPosts(threadId, { page = 1, size = 20 } = {}) {
  const params = { page, size }
  const { data } = await api.get(`/threads/${threadId}/posts`, { params })
  return data
}

export async function createPost(threadId, payload) {
  const { data } = await api.post(`/threads/${threadId}/posts`, payload)
  return data
}

export async function updatePost(id, payload) {
  const { data } = await api.patch(`/posts/${id}`, payload)
  return data
}

export async function deletePost(id) {
  await api.delete(`/posts/${id}`)
}
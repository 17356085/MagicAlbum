import api from './client'

// 我的帖子
export async function listMyThreads({ q, sectionId, page = 1, size = 20, sort } = {}) {
  const params = {}
  if (q && q.trim()) params.q = q.trim()
  if (sectionId) params.sectionId = sectionId
  if (sort) params.sort = sort
  params.page = page
  params.size = size
  const { data } = await api.get('/users/me/threads', { params })
  return data
}

// 我的评论
export async function listMyPosts({ q, threadId, page = 1, size = 20 } = {}) {
  const params = {}
  if (q && q.trim()) params.q = q.trim()
  if (threadId) params.threadId = threadId
  params.page = page
  params.size = size
  const { data } = await api.get('/users/me/posts', { params })
  return data
}

// 线程编辑与删除（本人资源）
export async function updateThread(id, payload) {
  const { data } = await api.patch(`/threads/${id}`, payload)
  return data
}

export async function deleteThread(id) {
  const { data } = await api.delete(`/threads/${id}`)
  return data
}

// 评论删除（本人资源）
export async function deletePost(id) {
  const { data } = await api.delete(`/posts/${id}`)
  return data
}
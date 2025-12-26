import api from './client'

export async function createThread(payload) {
  const { data } = await api.post('/threads', payload)
  return data
}

export async function listThreads({ q, sectionId, page = 1, size = 20 } = {}) {
  const params = {}
  if (q && q.trim()) params.q = q.trim()
  if (sectionId) params.sectionId = sectionId
  params.page = page
  params.size = size
  const { data } = await api.get('/threads', { params })
  return data
}

export async function getThread(id) {
  const { data } = await api.get(`/threads/${id}`)
  return data
}
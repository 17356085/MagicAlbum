import api from './client'

export async function listNotifications({ type, unread, page = 1, size = 20 } = {}) {
  const params = {}
  if (type) params.type = type
  if (typeof unread === 'boolean') params.unread = unread
  params.page = page
  params.size = size
  const { data } = await api.get('/notifications', { params })
  return data
}

export async function markNotificationRead(id) {
  const { data } = await api.patch(`/notifications/${id}/read`)
  return data
}

export async function getNotificationSettings() {
  const { data } = await api.get('/notifications/settings')
  return data
}

export async function updateNotificationSettings(payload) {
  const { data } = await api.patch('/notifications/settings', payload)
  return data
}
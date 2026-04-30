import api from './client'
import type { Id, NotificationItem, PageResult } from '@/types'

interface ListNotificationsQuery {
  type?: string
  unread?: boolean
  page?: number
  size?: number
}

export async function listNotifications({
  type,
  unread,
  page = 1,
  size = 20,
}: ListNotificationsQuery = {}): Promise<PageResult<NotificationItem> | NotificationItem[]> {
  const params: Record<string, string | number | boolean> = {}
  if (type) params.type = type
  if (typeof unread === 'boolean') params.unread = unread
  params.page = page
  params.size = size
  const { data } = await api.get('/notifications', { params })
  return data
}

export async function markNotificationRead(id: Id): Promise<NotificationItem | { success?: boolean; id?: Id }> {
  const { data } = await api.patch(`/notifications/${id}/read`)
  return data
}

import api from './client'
import { decodePageResponse, PageResponseError } from './pagination'
import type { Id, NotificationItem, PageResult } from '@/types'

export interface NotificationPage extends PageResult<NotificationItem> {
  source?: string
}

export interface NotificationReadAcknowledgement {
  id: Id
  read: true
}

export class NotificationReadAcknowledgementError extends Error {
  readonly code = 'NOTIFICATION_READ_ACK_UNCONFIRMED'

  constructor() {
    super('已读结果暂未确认，请重试')
    this.name = 'NotificationReadAcknowledgementError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

interface ListNotificationsQuery {
  type?: string
  unread?: boolean
  page?: number
  size?: number
}

type NotificationRowRecord = Record<string, unknown>

const optionalNotificationTextFields = [
  'type',
  'title',
  'content',
  'createdAt',
  'actorName',
  'link',
  'targetType',
] as const

const optionalNotificationIdFields = ['actorId', 'threadId', 'targetId'] as const

function hasOwnNotificationField(record: NotificationRowRecord, field: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, field)
}

function invalidNotificationRow(index: number, field: string, requirement: string): PageResponseError {
  return new PageResponseError(`Invalid notification response for /notifications: items[${index}].${field} ${requirement}`)
}

function decodeNotificationRow(data: unknown, index: number): NotificationItem {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw invalidNotificationRow(index, 'row', 'must be an object')
  }

  const row = data as NotificationRowRecord
  if (!hasOwnNotificationField(row, 'id')) {
    throw invalidNotificationRow(index, 'id', 'must be an own positive safe integer or decimal string')
  }
  if (canonicalPositiveId(row.id) === undefined) {
    throw invalidNotificationRow(index, 'id', 'must be an own positive safe integer or decimal string')
  }
  if (!hasOwnNotificationField(row, 'read') || typeof row.read !== 'boolean') {
    throw invalidNotificationRow(index, 'read', 'must be an own boolean')
  }

  for (const field of optionalNotificationTextFields) {
    if (hasOwnNotificationField(row, field) && row[field] !== null && typeof row[field] !== 'string') {
      throw invalidNotificationRow(index, field, 'must be a string, null, or omitted')
    }
  }
  for (const field of optionalNotificationIdFields) {
    if (hasOwnNotificationField(row, field) && row[field] !== null && canonicalPositiveId(row[field]) === undefined) {
      throw invalidNotificationRow(index, field, 'must be a positive safe integer, decimal string, null, or omitted')
    }
  }

  return { ...row, id: row.id as Id, read: row.read }
}

export async function listNotifications({
  type,
  unread,
  page = 1,
  size = 20,
}: ListNotificationsQuery = {}): Promise<NotificationPage> {
  const params: Record<string, string | number | boolean> = {}
  if (type) params.type = type
  if (typeof unread === 'boolean') params.unread = unread
  params.page = page
  params.size = size
  const { data } = await api.get('/notifications', { params })
  const decoded = decodePageResponse<unknown, { source?: string }>(data, { page, size, endpoint: '/notifications' })
  const seenIds = new Set<string>()
  const items = decoded.items.map((item, index) => {
    const row = decodeNotificationRow(item, index)
    const canonicalId = canonicalPositiveId(row.id) as string
    if (seenIds.has(canonicalId)) {
      throw invalidNotificationRow(index, 'id', 'must be unique within the page')
    }
    seenIds.add(canonicalId)
    return row
  })
  return { ...decoded, items } as NotificationPage
}

function canonicalPositiveId(value: unknown): string | undefined {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value > 0 ? String(value) : undefined
  }
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) return value
  return undefined
}

function isConfirmedReadAcknowledgement(data: unknown, requestedId: Id): data is NotificationReadAcknowledgement {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) return false
  if (!Object.prototype.hasOwnProperty.call(data, 'id') || !Object.prototype.hasOwnProperty.call(data, 'read')) return false

  const response = data as { id?: unknown; read?: unknown }
  const requested = canonicalPositiveId(requestedId)
  const responseId = canonicalPositiveId(response.id)
  return requested !== undefined && responseId !== undefined && requested === responseId && response.read === true
}

export async function markNotificationRead(id: Id): Promise<NotificationReadAcknowledgement> {
  const { data } = await api.patch(`/notifications/${id}/read`)
  if (!isConfirmedReadAcknowledgement(data, id)) throw new NotificationReadAcknowledgementError()
  return { id: data.id, read: true }
}

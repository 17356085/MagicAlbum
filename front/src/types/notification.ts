import type { Id } from './common'

export interface NotificationItem {
  id: Id
  type?: string
  title?: string
  content?: string
  read?: boolean
  createdAt?: string
  actorName?: string
  actorId?: Id
  threadId?: Id
  targetId?: Id
  targetType?: string
  link?: string
}

export interface NotificationInAppSettings {
  reply: boolean
  mention: boolean
  like: boolean
  system: boolean
}

export interface NotificationEmailSettings {
  enabled: boolean
  frequency: string
}

export interface NotificationSettings {
  inApp: NotificationInAppSettings
  email: NotificationEmailSettings
}

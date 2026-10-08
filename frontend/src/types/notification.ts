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
  follow: boolean
  /** System notifications are managed by the platform and cannot be disabled here. */
  system: true
}

export interface NotificationEmailSettings {
  /** Email delivery is not implemented and is always disabled. */
  enabled: false
  frequency: string
}

export interface NotificationSettings {
  inApp: NotificationInAppSettings
  email: NotificationEmailSettings
}

import type { Id } from './common'

export interface NotificationItem {
  id: Id
  type?: string | null
  title?: string | null
  content?: string | null
  read?: boolean
  createdAt?: string | null
  actorName?: string | null
  actorId?: Id | null
  threadId?: Id | null
  targetId?: Id | null
  targetType?: string | null
  link?: string | null
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

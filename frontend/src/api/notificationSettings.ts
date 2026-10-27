import api from './client'
import type { NotificationSettings } from '@/types'

export class NotificationSettingsResponseError extends Error {
  constructor() {
    super('通知偏好结果暂未确认，请重试。')
    this.name = 'NotificationSettingsResponseError'
  }
}

type SettingsRecord = Record<string, unknown>

function settingsRecord(value: unknown): SettingsRecord {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new NotificationSettingsResponseError()
  }
  return value as SettingsRecord
}

function settingsField(record: SettingsRecord, key: string): unknown {
  if (!Object.prototype.hasOwnProperty.call(record, key)) throw new NotificationSettingsResponseError()
  return record[key]
}

function booleanSetting(record: SettingsRecord, key: string): boolean {
  const value = settingsField(record, key)
  if (typeof value !== 'boolean') throw new NotificationSettingsResponseError()
  return value
}

function decodeNotificationSettings(value: unknown): NotificationSettings {
  const record = settingsRecord(value)
  const inApp = settingsRecord(settingsField(record, 'inApp'))
  const email = settingsRecord(settingsField(record, 'email'))
  const frequency = settingsField(email, 'frequency')
  if (settingsField(inApp, 'system') !== true || settingsField(email, 'enabled') !== false
    || typeof frequency !== 'string' || !frequency.trim()) {
    throw new NotificationSettingsResponseError()
  }
  return {
    inApp: {
      reply: booleanSetting(inApp, 'reply'),
      mention: booleanSetting(inApp, 'mention'),
      like: booleanSetting(inApp, 'like'),
      follow: booleanSetting(inApp, 'follow'),
      system: true,
    },
    email: { enabled: false, frequency },
  }
}

export async function getMyNotificationSettings(): Promise<NotificationSettings> {
  const { data } = await api.get('/users/me/settings')
  return decodeNotificationSettings(data)
}

export async function updateMyNotificationSettings(payload: NotificationSettings): Promise<NotificationSettings> {
  const { data } = await api.patch('/users/me/settings', payload)
  return decodeNotificationSettings(data)
}

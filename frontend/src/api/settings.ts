import api from './client'
import { isAxiosError } from 'axios'
import type {
  BasicInfoPayload,
  BasicInfoResponse,
  PasswordUpdatePayload,
  User,
  UserProfile,
  UserSettings,
} from '@/types'

export {
  NotificationSettingsResponseError,
  getMyNotificationSettings,
  updateMyNotificationSettings,
} from './notificationSettings'

export async function getMyProfile(): Promise<UserProfile & Partial<User>> {
  const { data } = await api.get('/users/me')
  return data
}

export async function updateMyProfile(payload: UserProfile): Promise<UserProfile & Partial<User>> {
  const { data } = await api.patch('/users/me', payload)
  return data
}

export async function getMySettings(): Promise<UserSettings | null> {
  const { data } = await api.get('/users/me/settings')
  return data
}

export async function updateMySettings(payload: UserSettings): Promise<UserSettings> {
  const { data } = await api.patch('/users/me/settings', payload)
  return data
}

export class PasswordUpdateUnconfirmedError extends Error {
  constructor() {
    super('密码修改结果未确认，请先确认新密码能否登录，再决定是否重试。')
    this.name = 'PasswordUpdateUnconfirmedError'
  }
}

// Only the documented 204 confirms that the server finished the change.
export async function updateMyPassword(payload: PasswordUpdatePayload): Promise<unknown> {
  try {
    const response = await api.post('/users/me/password', payload)
    if (response.status !== 204) throw new PasswordUpdateUnconfirmedError()
    return response.data
  } catch (error: unknown) {
    if (isAxiosError(error)) {
      const status = error.response?.status
      if (!error.response || status === 408 || (typeof status === 'number' && status >= 500)) {
        throw new PasswordUpdateUnconfirmedError()
      }
    }
    throw error
  }
}

// 获取与更新我的基础账户信息（用户名、邮箱、手机号）
export async function getMyBasicInfo(): Promise<BasicInfoResponse> {
  const { data } = await api.get('/users/me/basic')
  return data
}

export async function updateMyBasicInfo(payload: BasicInfoPayload): Promise<BasicInfoResponse> {
  const { data } = await api.patch('/users/me/basic', payload)
  return data
}

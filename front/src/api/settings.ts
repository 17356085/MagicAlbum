import api from './client'
import type {
  BasicInfoPayload,
  PasswordUpdatePayload,
  User,
  UserProfile,
  UserSettings,
} from '@/types'

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

// 更新我的密码：需要提供 currentPassword 与 newPassword
export async function updateMyPassword(payload: PasswordUpdatePayload): Promise<unknown> {
  const { data } = await api.post('/users/me/password', payload)
  return data
}

// 获取与更新我的基础账户信息（用户名、邮箱、手机号）
export async function getMyBasicInfo(): Promise<BasicInfoPayload> {
  const { data } = await api.get('/users/me/basic')
  return data
}

export async function updateMyBasicInfo(payload: BasicInfoPayload): Promise<BasicInfoPayload> {
  const { data } = await api.patch('/users/me/basic', payload)
  return data
}

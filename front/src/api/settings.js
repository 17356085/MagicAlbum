import api from './client'

export async function getMyProfile() {
  const { data } = await api.get('/users/me')
  return data
}

export async function updateMyProfile(payload) {
  const { data } = await api.patch('/users/me', payload)
  return data
}

export async function getMySettings() {
  const { data } = await api.get('/users/me/settings')
  return data
}

export async function updateMySettings(payload) {
  const { data } = await api.patch('/users/me/settings', payload)
  return data
}

// 更新我的密码：需要提供 currentPassword 与 newPassword
export async function updateMyPassword(payload) {
  const { data } = await api.post('/users/me/password', payload)
  return data
}

// 获取与更新我的基础账户信息（用户名、邮箱、手机号）
export async function getMyBasicInfo() {
  const { data } = await api.get('/users/me/basic')
  return data
}

export async function updateMyBasicInfo(payload) {
  const { data } = await api.patch('/users/me/basic', payload)
  return data
}
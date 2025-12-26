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
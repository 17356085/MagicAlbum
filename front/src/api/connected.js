import api from './client'

export async function listConnectedAccounts() {
  const { data } = await api.get('/users/me/connected-accounts')
  return data
}

export async function connectAccount(provider) {
  const { data } = await api.post(`/users/me/connected-accounts/${provider}/connect`)
  return data
}

export async function disconnectAccount(provider) {
  const { data } = await api.delete(`/users/me/connected-accounts/${provider}`)
  return data
}
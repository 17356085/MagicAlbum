import api from './client'
import type { ConnectedAccount, Id } from '@/types'

interface ConnectedAccountsResponse {
  items: ConnectedAccount[]
}

export async function listConnectedAccounts(): Promise<ConnectedAccountsResponse> {
  const { data } = await api.get('/users/me/connected-accounts')
  return data
}

export async function connectAccount(provider: string): Promise<ConnectedAccount | { provider: string }> {
  const { data } = await api.post(`/users/me/connected-accounts/${provider}/connect`)
  return data
}

export async function disconnectAccount(provider: string): Promise<{ success?: boolean; provider?: string; id?: Id }> {
  const { data } = await api.delete(`/users/me/connected-accounts/${provider}`)
  return data
}

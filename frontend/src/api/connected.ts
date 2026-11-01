import api from './client'
import type { ConnectedAccount } from '@/types'

interface ConnectedAccountsResponse {
  items: ConnectedAccount[]
}

export class ConnectedAccountsResponseError extends Error {
  constructor() {
    super('关联账号状态响应格式无效')
    this.name = 'ConnectedAccountsResponseError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

const hasOwn = (value: object, key: PropertyKey): boolean => Object.prototype.hasOwnProperty.call(value, key)

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isValidOptionalTextField(value: Record<string, unknown>, key: 'displayName' | 'linkedAt'): boolean {
  if (!hasOwn(value, key)) return true
  return value[key] === null || typeof value[key] === 'string'
}

function isValidOptionalBindingTag(value: Record<string, unknown>): boolean {
  if (!hasOwn(value, 'bindingTag') || value.bindingTag === null) return true
  return typeof value.bindingTag === 'string' && /^[0-9a-f]{64}$/.test(value.bindingTag)
}

function decodeConnectedAccount(value: unknown, providers: Set<string>): ConnectedAccount {
  if (!isRecord(value)
    || !hasOwn(value, 'provider')
    || typeof value.provider !== 'string'
    || !value.provider.trim()
    || !hasOwn(value, 'connected')
    || typeof value.connected !== 'boolean'
    || !isValidOptionalTextField(value, 'displayName')
    || !isValidOptionalTextField(value, 'linkedAt')
    || !isValidOptionalBindingTag(value)) {
    throw new ConnectedAccountsResponseError()
  }

  const canonicalProvider = value.provider.trim().toLowerCase()
  if (providers.has(canonicalProvider)) throw new ConnectedAccountsResponseError()
  providers.add(canonicalProvider)

  return { ...value, provider: value.provider, connected: value.connected }
}

function decodeConnectedAccountsResponse(value: unknown): ConnectedAccountsResponse {
  if (!isRecord(value) || !hasOwn(value, 'items') || !Array.isArray(value.items)) {
    throw new ConnectedAccountsResponseError()
  }

  const providers = new Set<string>()
  return {
    items: value.items.map((item) => decodeConnectedAccount(item, providers)),
  }
}

export async function listConnectedAccounts(): Promise<ConnectedAccountsResponse> {
  const { data } = await api.get<unknown>('/users/me/connected-accounts')
  return decodeConnectedAccountsResponse(data)
}

export async function connectAccount(provider: string): Promise<ConnectedAccount | { provider: string }> {
  const { data } = await api.post(`/users/me/connected-accounts/${provider}/connect`)
  return data
}

export interface DisconnectAccountProof {
  identifier: string
  currentPassword: string
  bindingTag: string
}

export async function disconnectAccount(provider: string, proof: DisconnectAccountProof, accessToken: string): Promise<void> {
  const response = await api.post(`/users/me/connected-accounts/${encodeURIComponent(provider)}/disconnect`, proof, {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  if (response.status !== 204) throw new Error('无法确认解绑结果')
}

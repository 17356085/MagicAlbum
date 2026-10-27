import { afterEach, describe, expect, it, vi } from 'vitest'

const authState = vi.hoisted(() => ({
  currentToken: 'token-a',
  handleUnauthorized: vi.fn(),
}))

vi.mock('@/stores/authSession', () => ({
  useAuthSessionStore: () => ({ handleUnauthorized: authState.handleUnauthorized }),
}))
vi.mock('@/utils/authStorage', () => ({
  getStoredAccessToken: () => authState.currentToken,
  hasRealToken: (token?: string | null) => !!token && !String(token).startsWith('mock-token-'),
}))

import api from '@/api/client'

const originalAdapter = api.defaults.adapter

afterEach(() => {
  api.defaults.adapter = originalAdapter
  authState.currentToken = 'token-a'
  authState.handleUnauthorized.mockClear()
})

describe('API explicit authorization', () => {
  it('preserves a response-token override over the stored session token', async () => {
    let requestAuthorization: unknown
    api.defaults.adapter = async (config) => {
      requestAuthorization = config.headers?.Authorization
      return {
        config,
        data: {},
        headers: {},
        status: 200,
        statusText: 'OK',
      } as never
    }

    await api.get('/users/me', { headers: { Authorization: 'Bearer token-b' } })

    expect(requestAuthorization).toBe('Bearer token-b')
  })
})

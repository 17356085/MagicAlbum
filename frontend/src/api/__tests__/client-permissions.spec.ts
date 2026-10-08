import { afterEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'

const { handleUnauthorized } = vi.hoisted(() => ({ handleUnauthorized: vi.fn() }))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ handleUnauthorized }),
}))
vi.mock('@/utils/authStorage', () => ({
  getStoredAccessToken: () => 'valid-user-token',
  hasRealToken: () => true,
}))

const originalAdapter = api.defaults.adapter

afterEach(() => {
  api.defaults.adapter = originalAdapter
  handleUnauthorized.mockClear()
  vi.unstubAllGlobals()
})

describe('API permission response', () => {
  it('keeps the session when an authenticated user receives 403', async () => {
    const alertMock = vi.fn()
    vi.stubGlobal('alert', alertMock)
    api.defaults.adapter = async (config) => {
      throw { config, response: { status: 403 } }
    }

    await expect(api.get('/admin/me')).rejects.toMatchObject({ response: { status: 403 } })

    expect(handleUnauthorized).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })
})

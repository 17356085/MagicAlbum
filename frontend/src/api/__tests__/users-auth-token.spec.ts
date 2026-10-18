import { beforeEach, describe, expect, it, vi } from 'vitest'

const apiMock = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('@/api/client', () => ({ default: apiMock }))

import { getCurrentUser } from '@/api/users'

describe('getCurrentUser auth token binding', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('binds profile fallback requests to the login response token', async () => {
    apiMock.get.mockResolvedValueOnce({
      data: { id: 7, username: 'account-b' },
    })

    await expect(getCurrentUser('token-b')).resolves.toMatchObject({ id: 7, username: 'account-b' })
    expect(apiMock.get).toHaveBeenCalledWith('/users/me', {
      headers: { Authorization: 'Bearer token-b' },
    })
  })

  it('keeps the storage-token request shape when no token override is supplied', async () => {
    apiMock.get.mockResolvedValueOnce({
      data: { id: 1, username: 'account-a' },
    })

    await expect(getCurrentUser()).resolves.toMatchObject({ id: 1, username: 'account-a' })
    expect(apiMock.get).toHaveBeenCalledWith('/users/me')
  })
})

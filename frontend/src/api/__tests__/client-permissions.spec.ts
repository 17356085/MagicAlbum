import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import api from '@/api/client'

const authState = vi.hoisted(() => ({
  currentToken: 'valid-user-token',
  handleUnauthorized: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ handleUnauthorized: authState.handleUnauthorized }),
}))
vi.mock('@/utils/authStorage', () => ({
  getStoredAccessToken: () => authState.currentToken,
  hasRealToken: (token?: string | null) => !!token && !String(token).startsWith('mock-token-'),
}))

const originalAdapter = api.defaults.adapter

afterEach(() => {
  api.defaults.adapter = originalAdapter
  authState.currentToken = 'valid-user-token'
  authState.handleUnauthorized.mockClear()
  window.__authExpiredAlertShown = false
  vi.clearAllTimers()
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

    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('clears the current session when its authenticated request receives 401', async () => {
    window.__authExpiredAlertShown = true
    api.defaults.adapter = async (config) => {
      throw { config, response: { status: 401 } }
    }

    await expect(api.get('/threads')).rejects.toMatchObject({ response: { status: 401 } })

    expect(authState.handleUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not clear a newer session when an old authenticated request returns 401', async () => {
    const alertMock = vi.fn()
    vi.stubGlobal('alert', alertMock)
    let capturedConfig: InternalAxiosRequestConfig | undefined
    let rejectResponse: ((reason?: unknown) => void) | undefined
    api.defaults.adapter = (config) => new Promise((_, reject) => {
      capturedConfig = config
      rejectResponse = reject
    })

    const request = api.get('/threads')
    await vi.waitFor(() => expect(rejectResponse).toBeTypeOf('function'))
    expect(capturedConfig?.headers?.Authorization).toBe('Bearer valid-user-token')

    authState.currentToken = 'new-user-token'
    rejectResponse?.({ config: capturedConfig, response: { status: 401 } })

    await expect(request).rejects.toMatchObject({ response: { status: 401 } })
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('does not clear a new session when an anonymous request returns 401', async () => {
    const alertMock = vi.fn()
    vi.stubGlobal('alert', alertMock)
    authState.currentToken = ''
    let capturedConfig: InternalAxiosRequestConfig | undefined
    let rejectResponse: ((reason?: unknown) => void) | undefined
    api.defaults.adapter = (config) => new Promise((_, reject) => {
      capturedConfig = config
      rejectResponse = reject
    })

    const request = api.get('/public/threads')
    await vi.waitFor(() => expect(rejectResponse).toBeTypeOf('function'))
    expect(capturedConfig?.headers?.Authorization).toBeUndefined()

    authState.currentToken = 'new-user-token'
    rejectResponse?.({ config: capturedConfig, response: { status: 401 } })

    await expect(request).rejects.toMatchObject({ response: { status: 401 } })
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })
})

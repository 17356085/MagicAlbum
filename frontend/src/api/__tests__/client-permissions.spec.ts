import { afterEach, describe, expect, it, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import api, { captureAuthGeneration, handleCurrentTokenUnauthorized } from '@/api/client'

const authState = vi.hoisted(() => ({
  currentToken: 'valid-user-token',
  generation: 0,
  handleUnauthorized: vi.fn(),
}))

vi.mock('@/stores/authSession', () => ({
  useAuthSessionStore: () => ({ handleUnauthorized: authState.handleUnauthorized, getAuthGeneration: () => authState.generation }),
}))
vi.mock('@/utils/authStorage', () => ({
  getStoredAccessToken: () => authState.currentToken,
  hasRealToken: (token?: string | null) => !!token && !String(token).startsWith('mock-token-'),
}))

const originalAdapter = api.defaults.adapter

afterEach(() => {
  api.defaults.adapter = originalAdapter
  authState.currentToken = 'valid-user-token'
  authState.generation = 0
  authState.handleUnauthorized.mockClear()
  window.__authExpiredAlertShown = false
  vi.clearAllTimers()
  vi.unstubAllGlobals()
})

describe('API permission response', () => {
  it.each(['/auth/code/finish', '/auth/oauth/exchange', '/auth/qr/exchange',
    '/api/v1/auth/code/finish?next=/threads', '/users/register'])(
  'preserves the current session when the independent auth challenge %s fails', async (path) => {
    api.defaults.adapter = async (config) => { throw { config, response: { status: 401 } } }
    await expect(api.post(path)).rejects.toMatchObject({ response: { status: 401 } })
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
  })

  it.each(['/auth/qr/scan', '/auth/qr/confirm', '/threads?next=/auth/login', '/auth/login/extra'])(
  'still expires an invalid bearer on protected path %s', async (path) => {
    window.__authExpiredAlertShown = true
    api.defaults.adapter = async (config) => { throw { config, response: { status: 401 } } }
    await expect(api.post(path)).rejects.toMatchObject({ response: { status: 401 } })
    expect(authState.handleUnauthorized).toHaveBeenCalledOnce()
  })
  it('preserves an existing session when a replacement login rejects its credentials', async () => {
    api.defaults.adapter = async (config) => { throw { config, response: { status: 401 } } }
    await expect(api.post('/auth/login', { email: 'other@example.test', password: 'invalid' }))
      .rejects.toMatchObject({ response: { status: 401 } })
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
  })
  it('does not interrupt a newer login when an old request returns 401 before the token changes', async () => {
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
    authState.generation += 1
    rejectResponse?.({ config: capturedConfig, response: { status: 401 } })
    await expect(request).rejects.toMatchObject({ response: { status: 401 } })
    expect(authState.currentToken).toBe('valid-user-token')
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('protects the captured native-stream login generation while allowing current expiry', () => {
    const generation = captureAuthGeneration()
    authState.generation += 1
    expect(handleCurrentTokenUnauthorized('valid-user-token', generation)).toBe(false)
    expect(authState.handleUnauthorized).not.toHaveBeenCalled()
    handleCurrentTokenUnauthorized('valid-user-token', captureAuthGeneration())
    expect(authState.handleUnauthorized).toHaveBeenCalledOnce()
  })
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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useAuthSessionStore } from '@/stores/authSession'

const originalAdapter = api.defaults.adapter

let auth: ReturnType<typeof useAuthStore>
let alertMock: ReturnType<typeof vi.fn>

function okResponse(config: InternalAxiosRequestConfig): AxiosResponse<{ ok: boolean }> {
  return {
    config,
    data: { ok: true },
    headers: {},
    status: 200,
    statusText: 'OK',
  }
}

function readAuthorization(config: InternalAxiosRequestConfig | undefined): unknown {
  if (!config?.headers) return undefined
  return typeof config.headers.get === 'function'
    ? config.headers.get('Authorization')
    : config.headers.Authorization ?? config.headers.authorization
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  auth = useAuthStore()
  auth.applyAuthState({ accessToken: 'token-a' })
  alertMock = vi.fn()
  vi.stubGlobal('alert', alertMock)
  window.__authExpiredAlertShown = false
})

afterEach(() => {
  api.defaults.adapter = originalAdapter
  localStorage.clear()
  window.__authExpiredAlertShown = false
  vi.clearAllTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('API automatic session ownership', () => {
  it.each(['get', 'post'] as const)('rejects an owner-specific auto-auth %s before the adapter when memory and storage diverge', async (method) => {
    auth.token = 'token-a'
    localStorage.setItem('accessToken', 'token-b')
    let adapterCalls = 0
    api.defaults.adapter = async (config) => {
      adapterCalls += 1
      return okResponse(config)
    }
    const handleUnauthorized = vi.spyOn(useAuthSessionStore(), 'handleUnauthorized')
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')
    const dispatchEvent = vi.spyOn(window, 'dispatchEvent')

    await expect(api.request({ method, url: method === 'post' ? '/threads' : '/users/me', data: method === 'post' ? { title: 'owner-a draft' } : undefined })).rejects.toMatchObject({
      code: 'AUTH_SESSION_CHANGED',
      message: '账号已在其他标签页变更，请刷新后重试',
    })

    expect(adapterCalls).toBe(0)
    expect(auth.token).toBe('token-a')
    expect(localStorage.getItem('accessToken')).toBe('token-b')
    expect(handleUnauthorized).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
    expect(removeItem).not.toHaveBeenCalled()
    expect(dispatchEvent).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('rejects an auto-auth request before the adapter when storage has been removed', async () => {
    auth.token = 'token-a'
    localStorage.removeItem('accessToken')
    let adapterCalls = 0
    api.defaults.adapter = async (config) => {
      adapterCalls += 1
      return okResponse(config)
    }
    const handleUnauthorized = vi.spyOn(useAuthSessionStore(), 'handleUnauthorized')
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')
    const dispatchEvent = vi.spyOn(window, 'dispatchEvent')

    await expect(api.get('/users/me')).rejects.toMatchObject({ code: 'AUTH_SESSION_CHANGED' })

    expect(adapterCalls).toBe(0)
    expect(auth.token).toBe('token-a')
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(handleUnauthorized).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
    expect(removeItem).not.toHaveBeenCalled()
    expect(dispatchEvent).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })

  it.each(['get', 'post'] as const)('binds a coherent %s and its generation before an immediate same-tab switch', async (method) => {
    let capturedConfig: InternalAxiosRequestConfig | undefined
    let rejectResponse: ((reason?: unknown) => void) | undefined
    api.defaults.adapter = (config) => new Promise((_, reject) => {
      capturedConfig = config
      rejectResponse = reject
    })
    const handleUnauthorized = vi.spyOn(useAuthSessionStore(), 'handleUnauthorized')

    const request = api.request({ method, url: method === 'post' ? '/threads' : '/users/me', data: method === 'post' ? { title: 'owner-a draft' } : undefined })
    expect(readAuthorization(capturedConfig)).toBe('Bearer token-a')

    auth.applyAuthState({ accessToken: 'token-b' })
    expect(readAuthorization(capturedConfig)).toBe('Bearer token-a')

    rejectResponse?.({ config: capturedConfig, response: { status: 401 } })
    await expect(request).rejects.toMatchObject({ response: { status: 401 } })
    expect(handleUnauthorized).not.toHaveBeenCalled()
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('preserves an explicit authorization override despite a storage mismatch', async () => {
    auth.token = 'token-a'
    localStorage.setItem('accessToken', 'token-b')
    let capturedConfig: InternalAxiosRequestConfig | undefined
    api.defaults.adapter = async (config) => {
      capturedConfig = config
      return okResponse(config)
    }

    await expect(api.get('/users/me', { headers: { Authorization: 'Bearer token-c' } })).resolves.toMatchObject({
      data: { ok: true },
    })

    expect(readAuthorization(capturedConfig)).toBe('Bearer token-c')
  })

  it('allows public replacement login to proceed despite a storage mismatch', async () => {
    auth.token = 'token-a'
    localStorage.setItem('accessToken', 'token-b')
    let adapterCalls = 0
    api.defaults.adapter = async (config) => {
      adapterCalls += 1
      return okResponse(config)
    }

    await expect(api.post('/auth/login', { email: 'account-b@example.test', password: 'secret' }))
      .resolves.toMatchObject({ data: { ok: true } })

    expect(adapterCalls).toBe(1)
    expect(auth.token).toBe('token-a')
    expect(localStorage.getItem('accessToken')).toBe('token-b')
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('keeps current-session 401 expiry behavior when token and generation still match', async () => {
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    window.__authExpiredAlertShown = true
    api.defaults.adapter = async (config) => {
      throw { config, response: { status: 401 } }
    }
    const handleUnauthorized = vi.spyOn(useAuthSessionStore(), 'handleUnauthorized')

    await expect(api.get('/users/me')).rejects.toMatchObject({ response: { status: 401 } })

    expect(handleUnauthorized).toHaveBeenCalledOnce()
    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('accessToken')).toBeNull()
  })
})

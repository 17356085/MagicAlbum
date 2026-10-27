import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createPinia, getActivePinia, setActivePinia } from 'pinia'
import api, { captureAuthGeneration, handleCurrentTokenUnauthorized } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useAuthSessionStore } from '@/stores/authSession'
import {
  getStoredAccessToken,
  getStoredCurrentUser,
  persistAuthState,
} from '@/utils/authStorage'
import type { User } from '@/types'

const TOKEN_A = 'account-a-token'
const TOKEN_B = 'account-b-token'
const USER_A: User = { id: 1, username: 'alice' }
const USER_B: User = { id: 2, username: 'bob' }

const originalAdapter = api.defaults.adapter

type StorageSnapshot = Array<[string, string]>

let previousActivePinia: ReturnType<typeof getActivePinia>
let previousLocalStorage: StorageSnapshot
let previousSessionStorage: StorageSnapshot
let previousAuthExpiredAlertShown: boolean | undefined
let hadAuthExpiredAlertShown = false
let alertMock: ReturnType<typeof vi.fn>

function snapshotStorage(storage: Storage): StorageSnapshot {
  return Array.from({ length: storage.length }, (_, index) => storage.key(index))
    .filter((key): key is string => key !== null)
    .map(key => [key, storage.getItem(key) || ''])
}

function restoreStorage(storage: Storage, snapshot: StorageSnapshot): void {
  storage.clear()
  for (const [key, value] of snapshot) storage.setItem(key, value)
}

function readAuthorization(config: InternalAxiosRequestConfig | undefined): string | undefined {
  if (!config?.headers) return undefined
  const value = typeof config.headers.get === 'function'
    ? config.headers.get('Authorization')
    : config.headers.Authorization ?? config.headers.authorization
  return typeof value === 'string' ? value : undefined
}

function unauthorizedError(config: InternalAxiosRequestConfig): AxiosError {
  const response: AxiosResponse = {
    config,
    data: { message: 'unauthorized' },
    headers: {},
    status: 401,
    statusText: 'Unauthorized',
  }
  return new AxiosError(
    'Request failed with status code 401',
    AxiosError.ERR_BAD_REQUEST,
    config,
    undefined,
    response,
  )
}

beforeEach(() => {
  previousActivePinia = getActivePinia()
  previousLocalStorage = snapshotStorage(localStorage)
  previousSessionStorage = snapshotStorage(sessionStorage)
  hadAuthExpiredAlertShown = Object.prototype.hasOwnProperty.call(window, '__authExpiredAlertShown')
  previousAuthExpiredAlertShown = window.__authExpiredAlertShown

  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(undefined)
  window.__authExpiredAlertShown = false
  alertMock = vi.fn()
  vi.stubGlobal('alert', alertMock)
})

afterEach(() => {
  api.defaults.adapter = originalAdapter
  vi.clearAllTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()

  restoreStorage(localStorage, previousLocalStorage)
  restoreStorage(sessionStorage, previousSessionStorage)
  if (hadAuthExpiredAlertShown) window.__authExpiredAlertShown = previousAuthExpiredAlertShown
  else delete window.__authExpiredAlertShown
  setActivePinia(previousActivePinia)
})

describe('API auth context contracts', () => {
  it('uses the current storage bearer for an automatic GET without Pinia', async () => {
    localStorage.setItem('accessToken', TOKEN_A)
    let adapterCalls = 0
    let requestConfig: InternalAxiosRequestConfig | undefined
    api.defaults.adapter = async config => {
      adapterCalls += 1
      requestConfig = config
      const response: AxiosResponse<{ ok: true }> = {
        config,
        data: { ok: true },
        headers: {},
        status: 200,
        statusText: 'OK',
      }
      return response
    }

    expect(captureAuthGeneration()).toBeUndefined()
    await expect(api.get('/threads')).resolves.toMatchObject({ data: { ok: true } })

    expect(adapterCalls).toBe(1)
    expect(readAuthorization(requestConfig)).toBe(`Bearer ${TOKEN_A}`)
  })

  it('keeps storage and suppresses expiry UI for a real 401 without Pinia', async () => {
    persistAuthState({ accessToken: TOKEN_A, user: USER_A })
    expect(handleCurrentTokenUnauthorized(TOKEN_A)).toBe(false)

    let adapterCalls = 0
    api.defaults.adapter = async config => {
      adapterCalls += 1
      throw unauthorizedError(config)
    }

    await expect(api.get('/threads')).rejects.toSatisfy((error: unknown) => {
      return error instanceof AxiosError
        && error.response?.status === 401
        && error.config !== undefined
    })

    expect(adapterCalls).toBe(1)
    expect(getStoredAccessToken()).toBe(TOKEN_A)
    expect(getStoredCurrentUser()).toEqual(USER_A)
    expect(handleCurrentTokenUnauthorized(TOKEN_A)).toBe(false)
    expect(alertMock).not.toHaveBeenCalled()
  })

  it('does not let an A request clear B after active Pinia and storage switch', async () => {
    setActivePinia(createPinia())
    const authA = useAuthStore()
    authA.applyAuthState({ accessToken: TOKEN_A, user: USER_A })

    let requestConfig: InternalAxiosRequestConfig | undefined
    let rejectRequest: ((reason?: unknown) => void) | undefined
    api.defaults.adapter = config => {
      requestConfig = config
      return new Promise((_, reject) => {
        rejectRequest = reject
      })
    }

    const request = api.get('/threads')
    await vi.waitFor(() => expect(rejectRequest).toBeTypeOf('function'))
    expect(readAuthorization(requestConfig)).toBe(`Bearer ${TOKEN_A}`)

    setActivePinia(createPinia())
    const authB = useAuthStore()
    authB.applyAuthState({ accessToken: TOKEN_B, user: USER_B })
    const handleUnauthorizedB = vi.spyOn(useAuthSessionStore(), 'handleUnauthorized')

    expect(getStoredAccessToken()).toBe(TOKEN_B)
    expect(getStoredCurrentUser()).toEqual(USER_B)

    rejectRequest?.(unauthorizedError(requestConfig!))
    await expect(request).rejects.toSatisfy((error: unknown) => {
      return error instanceof AxiosError
        && error.response?.status === 401
        && error.config === requestConfig
    })

    expect(handleUnauthorizedB).not.toHaveBeenCalled()
    expect(authB.token).toBe(TOKEN_B)
    expect(authB.user).toEqual(USER_B)
    expect(getStoredAccessToken()).toBe(TOKEN_B)
    expect(getStoredCurrentUser()).toEqual(USER_B)
    expect(alertMock).not.toHaveBeenCalled()
  })
})

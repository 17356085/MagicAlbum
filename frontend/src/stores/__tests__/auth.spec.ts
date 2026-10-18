import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoginResponse, User } from '@/types'

const apiMocks = vi.hoisted(() => ({
  loginWithPhonePassword: vi.fn(),
  loginWithEmailPassword: vi.fn(),
  finishEmailCodeLogin: vi.fn(),
  finishPhoneCodeLogin: vi.fn(),
}))

const userApiMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
}))

vi.mock('@/api/auth', () => apiMocks)
vi.mock('@/api/users', () => userApiMocks)

import { AuthOperationSupersededError, useAuthStore } from '../auth'

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

const loginResponse = (accessToken: string, nextUser: User | null): LoginResponse => ({ accessToken, user: nextUser })

describe('auth draft ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    userApiMocks.getCurrentUser.mockResolvedValue(null)
    setActivePinia(createPinia())
  })

  it('keeps legacy and account-scoped drafts when clearAuthState is called', () => {
    const legacy = JSON.stringify({ title: '旧无归属草稿' })
    const accountDraft = JSON.stringify({ title: '账号草稿' })
    localStorage.setItem('thread-draft-v1', legacy)
    localStorage.setItem('thread-draft-v1:7', accountDraft)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-7', user: { id: 7, username: 'seven' } })

    auth.clearAuthState({ clearDraft: true })

    expect(localStorage.getItem('thread-draft-v1')).toBe(legacy)
    expect(localStorage.getItem('thread-draft-v1:7')).toBe(accountDraft)
    expect(auth.isLoggedIn).toBe(false)
  })

  it('keeps account-scoped drafts across logout for local recovery', () => {
    const accountDraft = JSON.stringify({ title: '退出后仍可恢复' })
    localStorage.setItem('thread-draft-v1:7', accountDraft)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-7', user: { id: 7, username: 'seven' } })

    auth.logout()

    expect(localStorage.getItem('thread-draft-v1:7')).toBe(accountDraft)
    expect(localStorage.getItem('currentUser')).toBeNull()
    expect(localStorage.getItem('accessToken')).toBeNull()
  })

  it('keeps the newer login when an older response arrives later', async () => {
    const olderLogin = deferred<LoginResponse>()
    const newerLogin = deferred<LoginResponse>()
    apiMocks.loginWithEmailPassword
      .mockReturnValueOnce(olderLogin.promise)
      .mockReturnValueOnce(newerLogin.promise)

    const auth = useAuthStore()
    const olderPending = auth.loginWithEmailPassword({ email: 'older@example.com', password: 'password-1' })
    const newerPending = auth.loginWithEmailPassword({ email: 'newer@example.com', password: 'password-2' })

    newerLogin.resolve(loginResponse('token-newer', { id: 2, username: 'newer' }))
    await newerPending
    olderLogin.resolve(loginResponse('token-older', { id: 1, username: 'older' }))

    await expect(olderPending).rejects.toBeInstanceOf(AuthOperationSupersededError)
    expect(auth.token).toBe('token-newer')
    expect(auth.user?.id).toBe(2)
    expect(localStorage.getItem('accessToken')).toBe('token-newer')
  })

  it('does not re-authenticate after logout when a pending login resolves', async () => {
    const pendingLogin = deferred<LoginResponse>()
    apiMocks.loginWithEmailPassword.mockReturnValueOnce(pendingLogin.promise)

    const auth = useAuthStore()
    const pending = auth.loginWithEmailPassword({ email: 'late@example.com', password: 'password-1' })
    auth.logout()

    pendingLogin.resolve(loginResponse('token-late', { id: 3, username: 'late' }))

    await expect(pending).rejects.toBeInstanceOf(AuthOperationSupersededError)
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.token).toBe('')
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('currentUser')).toBeNull()
  })

  it('invalidates a login while its profile fallback is awaiting', async () => {
    const profile = deferred<User | null>()
    apiMocks.loginWithEmailPassword.mockResolvedValueOnce(loginResponse('token-fallback', null))
    userApiMocks.getCurrentUser.mockReturnValueOnce(profile.promise)

    const auth = useAuthStore()
    const pending = auth.loginWithEmailPassword({ email: 'fallback@example.com', password: 'password-1' })
    await Promise.resolve()
    auth.logout()
    profile.resolve({ id: 4, username: 'fallback' })

    await expect(pending).rejects.toBeInstanceOf(AuthOperationSupersededError)
    expect(auth.isLoggedIn).toBe(false)
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('currentUser')).toBeNull()
  })

  it('uses the new response token for profile fallback when switching accounts', async () => {
    const accountB = { id: 7, username: 'account-b' }
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 6, username: 'account-a' } })
    apiMocks.loginWithEmailPassword.mockResolvedValueOnce(loginResponse('token-b', null))
    userApiMocks.getCurrentUser.mockResolvedValueOnce(accountB)

    await auth.loginWithEmailPassword({ email: 'b@example.com', password: 'password-2' })

    expect(userApiMocks.getCurrentUser).toHaveBeenCalledWith('token-b')
    expect(auth.token).toBe('token-b')
    expect(auth.user?.id).toBe(7)
  })

  it('invalidates pending login when an explicit auth response is applied', async () => {
    const pendingLogin = deferred<LoginResponse>()
    apiMocks.loginWithEmailPassword.mockReturnValueOnce(pendingLogin.promise)

    const auth = useAuthStore()
    const pending = auth.loginWithEmailPassword({ email: 'pending@example.com', password: 'password-1' })
    auth.loginWithAuthResponse(loginResponse('token-explicit', { id: 5, username: 'explicit' }))
    pendingLogin.resolve(loginResponse('token-pending', { id: 6, username: 'pending' }))

    await expect(pending).rejects.toBeInstanceOf(AuthOperationSupersededError)
    expect(auth.token).toBe('token-explicit')
    expect(auth.user?.id).toBe(5)
    expect(localStorage.getItem('accessToken')).toBe('token-explicit')
  })
})

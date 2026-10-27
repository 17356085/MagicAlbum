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
import { storeOAuthBrowserProof } from '@/utils/oauthBrowserProof'
import { getStoredCurrentUser, persistAuthState } from '@/utils/authStorage'

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
    sessionStorage.clear()
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

  it('clears an OAuth browser proof when a new auth operation starts', () => {
    storeOAuthBrowserProof({ provider: 'google', verifier: 'v'.repeat(43), expiresAt: Date.now() + 300_000 })
    const auth = useAuthStore()

    auth.invalidateAuthOperations()

    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
  })

  it('shares one callback generation for the same proof and expires it on a new auth intent', () => {
    const proof = { provider: 'google' as const, verifier: 'v'.repeat(43), expiresAt: Date.now() + 300_000 }
    storeOAuthBrowserProof(proof)
    const auth = useAuthStore()

    const firstGeneration = auth.beginOAuthCallbackOperation(proof)
    const secondGeneration = auth.beginOAuthCallbackOperation(proof)

    expect(firstGeneration).not.toBeNull()
    expect(secondGeneration).toBe(firstGeneration)
    auth.invalidateAuthOperations()

    if (firstGeneration === null) throw new Error('expected callback generation')
    expect(auth.isAuthGenerationCurrent(firstGeneration)).toBe(false)
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
  })

  it('clears the callback claim and proof on logout', () => {
    const proof = { provider: 'google' as const, verifier: 'v'.repeat(43), expiresAt: Date.now() + 300_000 }
    storeOAuthBrowserProof(proof)
    const auth = useAuthStore()
    const oldGeneration = auth.beginOAuthCallbackOperation(proof)

    auth.logout()
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()

    storeOAuthBrowserProof(proof)
    const nextGeneration = auth.beginOAuthCallbackOperation(proof)
    expect(nextGeneration).not.toBe(oldGeneration)
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

  it.each(['phone-password', 'email-password', 'phone-code', 'email-code'] as const)(
    'preserves a storage-first account switch while %s is pending', async (method) => {
      const reply = deferred<LoginResponse>()
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-original', user: { id: 1, username: 'original' } })
      let pending: Promise<void>
      if (method === 'phone-password') {
        apiMocks.loginWithPhonePassword.mockReturnValueOnce(reply.promise)
        pending = auth.loginWithPhonePassword({ phone: '13800000000', password: 'LegacyPassword1' })
      } else if (method === 'email-password') {
        apiMocks.loginWithEmailPassword.mockReturnValueOnce(reply.promise)
        pending = auth.loginWithEmailPassword({ email: 'original@example.com', password: 'LegacyPassword1' })
      } else if (method === 'phone-code') {
        apiMocks.finishPhoneCodeLogin.mockReturnValueOnce(reply.promise)
        pending = auth.loginWithPhoneCode({ channel: 'phone', address: '13800000000', code: '123456', session: 'otp' })
      } else {
        apiMocks.finishEmailCodeLogin.mockReturnValueOnce(reply.promise)
        pending = auth.loginWithEmailCode({ channel: 'email', address: 'original@example.com', code: '123456', session: 'otp' })
      }
      const rejection = expect(pending).rejects.toBeInstanceOf(AuthOperationSupersededError)
      persistAuthState({ accessToken: 'token-new-tab', user: { id: 9, username: 'new-tab' } })
      const storedIdentity = localStorage.getItem('currentUserSession.v1')
      reply.resolve(loginResponse('token-stale-login', null))
      await rejection
      expect(userApiMocks.getCurrentUser).not.toHaveBeenCalled()
      expect(auth.token).toBe('token-original')
      expect(auth.user?.id).toBe(1)
      expect(localStorage.getItem('accessToken')).toBe('token-new-tab')
      expect(localStorage.getItem('currentUserSession.v1')).toBe(storedIdentity)
      expect(getStoredCurrentUser('token-new-tab')?.id).toBe(9)
    },
  )

  it('preserves storage logout during a pending identity fallback', async () => {
    const profile = deferred<User | null>()
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-original', user: { id: 1, username: 'original' } })
    apiMocks.loginWithEmailPassword.mockResolvedValueOnce(loginResponse('token-login', null))
    userApiMocks.getCurrentUser.mockReturnValueOnce(profile.promise)
    const pending = auth.loginWithEmailPassword({ email: 'other@example.com', password: 'LegacyPassword1' })
    const rejection = expect(pending).rejects.toBeInstanceOf(AuthOperationSupersededError)
    await Promise.resolve()
    expect(userApiMocks.getCurrentUser).toHaveBeenCalledWith('token-login')
    localStorage.removeItem('accessToken')
    profile.resolve({ id: 2, username: 'other' })
    await rejection
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(auth.token).toBe('token-original')
    expect(auth.user?.id).toBe(1)
  })

  it('rejects a pending login after memory credentials change without an auth event', async () => {
    const reply = deferred<LoginResponse>()
    const auth = useAuthStore()
    apiMocks.loginWithEmailPassword.mockReturnValueOnce(reply.promise)
    const pending = auth.loginWithEmailPassword({ email: 'other@example.com', password: 'LegacyPassword1' })
    const rejection = expect(pending).rejects.toBeInstanceOf(AuthOperationSupersededError)
    auth.token = 'other-memory-token'
    reply.resolve(loginResponse('token-stale', { id: 2, username: 'other' }))
    await rejection
    expect(auth.token).toBe('other-memory-token')
    expect(localStorage.getItem('accessToken')).toBeNull()
  })

  it('captures an auth operation without changing credentials or browser proof', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'original', user: { id: 1, username: 'original' } })
    storeOAuthBrowserProof({ provider: 'google', verifier: 'v'.repeat(43), expiresAt: Date.now() + 300_000 })
    const proof = sessionStorage.getItem('oauthBrowserProof')
    const generation = auth.getAuthGeneration()
    const context = auth.captureAuthOperationContext()
    expect(context).toEqual({ generation, memoryToken: 'original', persistedToken: 'original' })
    expect(Object.isFrozen(context)).toBe(true)
    expect(auth.isAuthOperationContextCurrent(context)).toBe(true)
    expect(auth.getAuthGeneration()).toBe(generation)
    expect(sessionStorage.getItem('oauthBrowserProof')).toBe(proof)
  })

  it.each(['storage', 'memory', 'generation'] as const)('rejects a guarded auth response after a %s change', reason => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'original', user: { id: 1, username: 'original' } })
    const context = auth.captureAuthOperationContext()
    if (reason === 'storage') persistAuthState({ accessToken: 'new-tab', user: { id: 2, username: 'new-tab' } })
    if (reason === 'memory') auth.token = 'new-memory'
    if (reason === 'generation') auth.invalidateAuthOperations()
    const storedToken = localStorage.getItem('accessToken')
    const storedIdentity = localStorage.getItem('currentUserSession.v1')
    const generation = auth.getAuthGeneration()
    const events = vi.spyOn(window, 'dispatchEvent')
    try {
      expect(auth.isAuthOperationContextCurrent(context)).toBe(false)
      expect(auth.tryLoginWithAuthResponse({ accessToken: 'stale', user: { id: 9, username: 'stale' } }, context)).toBe(false)
      expect(auth.token).toBe(reason === 'memory' ? 'new-memory' : 'original')
      expect(auth.user?.id).toBe(1)
      expect(auth.getAuthGeneration()).toBe(generation)
      expect(localStorage.getItem('accessToken')).toBe(storedToken)
      expect(localStorage.getItem('currentUserSession.v1')).toBe(storedIdentity)
      expect(events).not.toHaveBeenCalled()
    } finally { events.mockRestore() }
  })

  it('commits a current guarded response once and rejects reusing its old context', () => {
    const auth = useAuthStore()
    const context = auth.captureAuthOperationContext()
    expect(auth.tryLoginWithAuthResponse({ accessToken: 'accepted', user: { id: 3, username: 'accepted' } }, context)).toBe(true)
    expect(auth.token).toBe('accepted')
    expect(getStoredCurrentUser('accepted')?.id).toBe(3)
    expect(auth.isAuthOperationContextCurrent(context)).toBe(false)
    expect(auth.tryLoginWithAuthResponse({ accessToken: 'replay', user: { id: 4, username: 'replay' } }, context)).toBe(false)
    expect(localStorage.getItem('accessToken')).toBe('accepted')
  })

  it('reports a guarded commit as superseded when an auth listener replaces it synchronously', () => {
    const auth = useAuthStore()
    const context = auth.captureAuthOperationContext()
    const replaceSession = () => {
      window.removeEventListener('auth-state-changed', replaceSession)
      auth.applyAuthState({ accessToken: 'newer', user: { id: 8, username: 'newer' } })
    }
    window.addEventListener('auth-state-changed', replaceSession)
    expect(auth.tryLoginWithAuthResponse({ accessToken: 'earlier', user: { id: 7, username: 'earlier' } }, context)).toBe(false)
    expect(auth.token).toBe('newer')
    expect(getStoredCurrentUser('newer')?.id).toBe(8)
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

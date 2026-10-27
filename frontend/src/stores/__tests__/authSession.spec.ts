import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@/types'

const apiFactoryCalls = vi.hoisted(() => ({ auth: 0, users: 0 }))

// The session core must stay independent of both the API client and the old
// auth facade. These factories throw if an accidental import initializes them.
vi.mock('@/api/auth', () => {
  apiFactoryCalls.auth += 1
  throw new Error('auth API must not initialize while importing authSession')
})
vi.mock('@/api/users', () => {
  apiFactoryCalls.users += 1
  throw new Error('users API must not initialize while importing authSession')
})

import { useAuthSessionStore } from '../authSession'
import { getStoredCurrentUser } from '@/utils/authStorage'
import { storeOAuthBrowserProof } from '@/utils/oauthBrowserProof'

const user = (id: number, username: string): User => ({ id, username })

describe('auth session core', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('imports without initializing the API client or auth API modules', () => {
    expect(apiFactoryCalls).toEqual({ auth: 0, users: 0 })
    const auth = useAuthSessionStore(createPinia())
    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
  })

  it('keeps token, user, and generation isolated across real Pinia instances', () => {
    const piniaA = createPinia()
    const piniaB = createPinia()
    const authA = useAuthSessionStore(piniaA)
    const authB = useAuthSessionStore(piniaB)

    authA.applyAuthState({ accessToken: 'token-a', user: user(1, 'alice') })
    authB.applyAuthState({ accessToken: 'token-b', user: user(2, 'bob') })

    expect(authA.token).toBe('token-a')
    expect(authA.user).toEqual(user(1, 'alice'))
    expect(authA.isLoggedIn).toBe(true)
    expect(authB.token).toBe('token-b')
    expect(authB.user).toEqual(user(2, 'bob'))
    expect(authB.isLoggedIn).toBe(true)
    expect(authA.getAuthGeneration()).toBe(1)
    expect(authB.getAuthGeneration()).toBe(1)

    authA.invalidateAuthOperations()
    expect(authA.getAuthGeneration()).toBe(2)
    expect(authB.getAuthGeneration()).toBe(1)
  })

  it('commits synchronously, emits auth state changes, and guards an auth context', () => {
    const auth = useAuthSessionStore(createPinia())
    const events: boolean[] = []
    const listener = (event: Event) => {
      events.push((event as CustomEvent<{ loggedIn: boolean }>).detail.loggedIn)
    }
    window.addEventListener('auth-state-changed', listener)

    try {
      auth.commitAuthState({ accessToken: 'token-a', user: user(1, 'alice') })
      expect(auth.getAuthGeneration()).toBe(0)
      expect(auth.token).toBe('token-a')
      expect(auth.user).toEqual(user(1, 'alice'))
      expect(localStorage.getItem('accessToken')).toBe('token-a')
      expect(getStoredCurrentUser('token-a')).toEqual(user(1, 'alice'))
      expect(events).toEqual([true])

      const context = auth.captureAuthOperationContext()
      expect(context).toEqual({ generation: 0, memoryToken: 'token-a', persistedToken: 'token-a' })
      expect(Object.isFrozen(context)).toBe(true)
      expect(auth.isAuthOperationContextCurrent(context)).toBe(true)

      expect(auth.tryLoginWithAuthResponse({ accessToken: 'token-b', user: user(2, 'bob') }, context)).toBe(true)
      expect(auth.token).toBe('token-b')
      expect(auth.user).toEqual(user(2, 'bob'))
      expect(auth.getAuthGeneration()).toBe(1)
      expect(auth.isAuthOperationContextCurrent(context)).toBe(false)
      expect(events).toEqual([true, true])
    } finally {
      window.removeEventListener('auth-state-changed', listener)
    }
  })

  it('synchronizes an external token change without restoring stale user metadata', () => {
    const auth = useAuthSessionStore(createPinia())
    const events: boolean[] = []
    const listener = (event: Event) => {
      events.push((event as CustomEvent<{ loggedIn: boolean }>).detail.loggedIn)
    }
    window.addEventListener('auth-state-changed', listener)

    try {
      auth.commitAuthState({ accessToken: 'token-a', user: user(1, 'alice') })
      const generation = auth.getAuthGeneration()
      localStorage.setItem('accessToken', 'token-b')

      expect(auth.syncAuthFromStorage()).toBe(true)
      expect(auth.token).toBe('token-b')
      expect(auth.user).toBeNull()
      expect(auth.getAuthGeneration()).toBe(generation + 1)
      expect(events).toEqual([true, true])
      expect(auth.syncAuthFromStorage()).toBe(false)
      expect(auth.getAuthGeneration()).toBe(generation + 1)
    } finally {
      window.removeEventListener('auth-state-changed', listener)
    }
  })

  it('increments metadata revision without advancing auth generation', () => {
    const auth = useAuthSessionStore(createPinia())
    auth.applyAuthState({ accessToken: 'token-a', user: user(1, 'alice') })
    const generation = auth.getAuthGeneration()
    const revision = auth.getMetadataRevision()

    auth.updateCurrentUser({ nickname: 'Alice' })

    expect(auth.getAuthGeneration()).toBe(generation)
    expect(auth.getMetadataRevision()).toBe(revision + 1)
    expect(auth.user).toMatchObject({ id: 1, username: 'alice', nickname: 'Alice' })
    expect(getStoredCurrentUser('token-a')).toMatchObject({ id: 1, nickname: 'Alice' })

    auth.updateCurrentUser({ nickname: 'Alice' })
    expect(auth.getMetadataRevision()).toBe(revision + 1)
  })

  it('reuses an OAuth callback claim for the same proof and clears it on invalidation', () => {
    const proof = {
      provider: 'google' as const,
      verifier: 'v'.repeat(43),
      expiresAt: Date.now() + 300_000,
    }
    storeOAuthBrowserProof(proof)
    const auth = useAuthSessionStore(createPinia())

    const first = auth.beginOAuthCallbackOperation(proof)
    const second = auth.beginOAuthCallbackOperation(proof)

    expect(first).not.toBeNull()
    expect(second).toBe(first)
    expect(auth.getAuthGeneration()).toBe(first)

    auth.invalidateAuthOperations()
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
    expect(auth.isAuthGenerationCurrent(first!)).toBe(false)
  })

  it('cleans a persisted mock token during normal-mode initialization', () => {
    localStorage.setItem('accessToken', 'mock-token-test')
    localStorage.setItem('currentUser', JSON.stringify(user(9, 'mock-user')))

    const auth = useAuthSessionStore(createPinia())

    expect(auth.token).toBe('')
    expect(auth.user).toBeNull()
    expect(auth.isLoggedIn).toBe(false)
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('currentUser')).toBeNull()
  })
})

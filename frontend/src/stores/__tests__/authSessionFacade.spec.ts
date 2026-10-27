import { nextTick, watch } from 'vue'
import {
  createPinia,
  disposePinia,
  setActivePinia,
  storeToRefs,
  type Pinia,
} from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoginResponse, User } from '@/types'
import {
  getStoredAccessToken,
  getStoredCurrentUser,
} from '@/utils/authStorage'
import { useAuthSessionStore } from '../authSession'
import { useAuthStore } from '../auth'

const authApiMocks = vi.hoisted(() => ({
  finishPhoneCodeLogin: vi.fn(),
  finishEmailCodeLogin: vi.fn(),
  loginWithPhonePassword: vi.fn(),
  loginWithEmailPassword: vi.fn(),
}))

const userApiMocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
}))

vi.mock('@/api/auth', () => authApiMocks)
vi.mock('@/api/users', () => userApiMocks)

const TOKEN_A = 'facade-token-a'
const TOKEN_B = 'facade-token-b'
const USER_A: User = { id: 1, username: 'alice' }
const USER_B: User = { id: 2, username: 'bob' }

let piniaInstances: Pinia[] = []
let cleanups: Array<() => void> = []

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.clearAllMocks()
  piniaInstances = []
  cleanups = []
  const pinia = createPinia()
  piniaInstances.push(pinia)
  setActivePinia(pinia)
})

afterEach(() => {
  for (const cleanup of cleanups.splice(0)) cleanup()
  for (const pinia of piniaInstances.splice(0)) disposePinia(pinia)
  setActivePinia(undefined)
  localStorage.clear()
  sessionStorage.clear()
})

describe('auth session facade boundary', () => {
  it('keeps core and facade state shared in both directions, including storeToRefs reactivity', async () => {
    const core = useAuthSessionStore()
    const facade = useAuthStore()
    const coreRefs = storeToRefs(core)
    const facadeRefs = storeToRefs(facade)
    const observedFacadeTokens: string[] = []
    const stopWatch = watch(facadeRefs.token, value => observedFacadeTokens.push(value))
    cleanups.push(stopWatch)

    core.$patch({ token: TOKEN_A, user: USER_A })
    await nextTick()

    expect(facadeRefs.token.value).toBe(TOKEN_A)
    expect(facadeRefs.user.value).toEqual(USER_A)
    expect(facade.isLoggedIn).toBe(true)
    expect(observedFacadeTokens).toEqual([TOKEN_A])

    facade.$patch({ token: TOKEN_B, user: USER_B })
    await nextTick()

    expect(coreRefs.token.value).toBe(TOKEN_B)
    expect(coreRefs.user.value).toEqual(USER_B)
    expect(core.isLoggedIn).toBe(true)
    expect(facadeRefs.token.value).toBe(TOKEN_B)
    expect(facadeRefs.user.value).toEqual(USER_B)
    expect(observedFacadeTokens).toEqual([TOKEN_A, TOKEN_B])
  })

  it('reflects core unauthorized cleanup through the facade and emits the legacy auth event', () => {
    const core = useAuthSessionStore()
    const facade = useAuthStore()
    facade.applyAuthState({ accessToken: TOKEN_A, user: USER_A })

    const events: CustomEvent<{ loggedIn: boolean }>[] = []
    const listener = (event: Event) => events.push(event as CustomEvent<{ loggedIn: boolean }>)
    window.addEventListener('auth-state-changed', listener)
    cleanups.push(() => window.removeEventListener('auth-state-changed', listener))

    expect(core.handleUnauthorized(TOKEN_A)).toBe(true)
    expect(core.token).toBe('')
    expect(core.user).toBeNull()
    expect(facade.token).toBe('')
    expect(facade.user).toBeNull()
    expect(facade.isLoggedIn).toBe(false)
    expect(getStoredAccessToken()).toBe('')
    expect(getStoredCurrentUser()).toBeNull()
    expect(events).toHaveLength(1)
    expect(events[0]?.detail).toEqual({ loggedIn: false })
  })

  it('reports direct facade invalidation as the legacy action name and runs after hooks', () => {
    const core = useAuthSessionStore()
    const facade = useAuthStore()
    const after = vi.fn()
    const stop = facade.$onAction(({ name, after: registerAfter }) => {
      if (name === 'invalidateAuthOperations') registerAfter(after)
    })
    cleanups.push(stop)

    const previousGeneration = core.getAuthGeneration()
    expect(facade.invalidateAuthOperations()).toBe(previousGeneration + 1)
    expect(core.getAuthGeneration()).toBe(previousGeneration + 1)
    expect(after).toHaveBeenCalledOnce()
  })

  it('keeps core and facade stores isolated across two Pinia instances', () => {
    const piniaA = piniaInstances[0]
    if (!piniaA) throw new Error('missing first Pinia instance')
    const coreA = useAuthSessionStore(piniaA)
    const facadeA = useAuthStore(piniaA)
    coreA.$patch({ token: TOKEN_A, user: USER_A })

    const piniaB = createPinia()
    piniaInstances.push(piniaB)
    const coreB = useAuthSessionStore(piniaB)
    const facadeB = useAuthStore(piniaB)
    facadeB.$patch({ token: TOKEN_B, user: USER_B })

    expect(coreA.token).toBe(TOKEN_A)
    expect(coreA.user).toEqual(USER_A)
    expect(facadeA.token).toBe(TOKEN_A)
    expect(facadeA.user).toEqual(USER_A)
    expect(coreB.token).toBe(TOKEN_B)
    expect(coreB.user).toEqual(USER_B)
    expect(facadeB.token).toBe(TOKEN_B)
    expect(facadeB.user).toEqual(USER_B)
  })

  it('preserves facade login request fields, generation, persistence, and metadata revision behavior', async () => {
    const loginResponse: LoginResponse = { accessToken: 'facade-login-token', user: null }
    authApiMocks.loginWithEmailPassword.mockResolvedValueOnce(loginResponse)
    userApiMocks.getCurrentUser.mockResolvedValueOnce({ ...USER_A, email: 'alice@example.com' })

    const facade = useAuthStore()
    const generationBeforeLogin = facade.getAuthGeneration()
    await facade.loginWithEmailPassword({
      email: 'alice@example.com',
      password: 'password-1',
      verifyToken: 'captcha-token',
      verifyProvider: 'turnstile',
      verifyScene: 'login',
    })

    expect(authApiMocks.loginWithEmailPassword).toHaveBeenCalledOnce()
    expect(authApiMocks.loginWithEmailPassword).toHaveBeenCalledWith({
      email: 'alice@example.com',
      password: 'password-1',
      verifyToken: 'captcha-token',
      verifyProvider: 'turnstile',
      verifyScene: 'login',
    })
    expect(userApiMocks.getCurrentUser).toHaveBeenCalledWith('facade-login-token')
    expect(facade.token).toBe('facade-login-token')
    expect(facade.user).toMatchObject({ id: USER_A.id, username: USER_A.username, email: 'alice@example.com' })
    expect(getStoredAccessToken()).toBe('facade-login-token')
    expect(getStoredCurrentUser()).toMatchObject({ id: USER_A.id, username: USER_A.username, email: 'alice@example.com' })

    const generationAfterLogin = facade.getAuthGeneration()
    expect(generationAfterLogin).toBe(generationBeforeLogin + 1)

    facade.updateCurrentUser({ nickname: 'Alice' })

    expect(facade.user).toMatchObject({ id: USER_A.id, username: USER_A.username, nickname: 'Alice' })
    expect(getStoredCurrentUser()).toMatchObject({ id: USER_A.id, nickname: 'Alice' })
    expect(getStoredAccessToken()).toBe('facade-login-token')
    expect(facade.getAuthGeneration()).toBe(generationAfterLogin)
  })
})

import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  finishPhoneCodeLogin as apiFinishPhoneCodeLogin,
  finishEmailCodeLogin as apiFinishEmailCodeLogin,
  loginWithPhonePassword as apiLoginWithPhonePassword,
  loginWithEmailPassword as apiLoginWithEmailPassword,
} from '@/api/auth'
import { getCurrentUser } from '@/api/users'
import {
  clearPersistedAuthState,
  getStoredAccessToken,
  getStoredCurrentUser,
  hasRealToken,
  isMockToken,
  persistAuthState,
} from '@/utils/authStorage'
import type {
  FinishPhoneCodeLoginRequest,
  FinishEmailCodeLoginRequest,
  LoginResponse,
  LoginWithEmailPasswordRequest,
  LoginWithPhonePasswordRequest,
  PersistedAuthState,
  User,
} from '@/types'

export class AuthOperationSupersededError extends Error {
  readonly code = 'AUTH_OPERATION_SUPERSEDED'

  constructor() {
    super('认证操作已失效')
    this.name = 'AuthOperationSupersededError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

export function isAuthOperationSuperseded(error: unknown): error is AuthOperationSupersededError {
  if (error instanceof AuthOperationSupersededError) return true
  if (typeof error !== 'object' || error === null) return false
  return (error as { code?: unknown }).code === 'AUTH_OPERATION_SUPERSEDED'
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(getStoredCurrentUser())
  const token = ref<string>(getStoredAccessToken())
  const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'
  let authGeneration = 0

  // Clean mock token if needed
  if (!useMock && isMockToken(token.value)) {
    token.value = ''
    clearPersistedAuthState()
  }

  // Getters
  const isLoggedIn = computed(() => hasRealToken(token.value))

  function emitAuthChanged(loggedIn: boolean): void {
    try {
      window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn } }))
    } catch (_) {}
  }

  function writeAuthState({ accessToken = '', user: nextUser = null }: Partial<PersistedAuthState> = {}): void {
    token.value = hasRealToken(accessToken) ? accessToken : ''
    user.value = nextUser || null
    persistAuthState({ accessToken: token.value, user: user.value })
    emitAuthChanged(!!token.value)
  }

  function invalidateAuthOperations(): number {
    authGeneration += 1
    return authGeneration
  }

  function getAuthGeneration(): number {
    return authGeneration
  }

  function isAuthGenerationCurrent(generation: number): boolean {
    return generation === authGeneration
  }

  function applyAuthState({ accessToken = '', user: nextUser = null }: Partial<PersistedAuthState> = {}): void {
    invalidateAuthOperations()
    writeAuthState({ accessToken, user: nextUser })
  }

  // Keep the option for caller compatibility; browser drafts are independent
  // of auth cleanup and must survive logout for account-scoped recovery.
  function clearAuthState(_options: { clearDraft?: boolean } = {}): void {
    invalidateAuthOperations()
    writeAuthState()
  }

  // Actions
  async function applyLoginResponse(
    generation: number,
    request: () => Promise<LoginResponse>,
  ): Promise<void> {
    let response: LoginResponse
    try {
      response = await request()
    } catch (error: unknown) {
      if (!isAuthGenerationCurrent(generation)) throw new AuthOperationSupersededError()
      throw error
    }

    if (!isAuthGenerationCurrent(generation)) throw new AuthOperationSupersededError()

    let nextUser: User | null = response.user || null
    if (!nextUser) {
      try {
        const fetched = await getCurrentUser(response.accessToken)
        if (fetched) nextUser = fetched
      } catch {}
      if (!isAuthGenerationCurrent(generation)) throw new AuthOperationSupersededError()
    }

    if (!isAuthGenerationCurrent(generation)) throw new AuthOperationSupersededError()
    writeAuthState({ accessToken: response.accessToken, user: nextUser })
  }

  async function loginWithPhonePassword({ phone, password, verifyToken, verifyProvider, verifyScene }: LoginWithPhonePasswordRequest): Promise<void> {
    const generation = invalidateAuthOperations()
    await applyLoginResponse(generation, () => apiLoginWithPhonePassword({ phone, password, verifyToken, verifyProvider, verifyScene }))
  }

  async function loginWithEmailPassword({ email, password, verifyToken, verifyProvider, verifyScene }: LoginWithEmailPasswordRequest): Promise<void> {
    const generation = invalidateAuthOperations()
    await applyLoginResponse(generation, () => apiLoginWithEmailPassword({ email, password, verifyToken, verifyProvider, verifyScene }))
  }

  async function loginWithEmailCode({ channel, address, code, session }: FinishEmailCodeLoginRequest): Promise<void> {
    const generation = invalidateAuthOperations()
    await applyLoginResponse(generation, () => apiFinishEmailCodeLogin({ channel, address, code, session }))
  }

  async function loginWithPhoneCode({ channel, address, code, session }: FinishPhoneCodeLoginRequest): Promise<void> {
    const generation = invalidateAuthOperations()
    await applyLoginResponse(generation, () => apiFinishPhoneCodeLogin({ channel, address, code, session }))
  }

  function loginWithAuthResponse({ accessToken, user: userData }: PersistedAuthState): void {
    applyAuthState({ accessToken, user: userData || null })
  }

  function logout(): void {
    clearAuthState()
  }

  function updateCurrentUser(patch: Partial<User>): void {
    if (!user.value) {
      return
    }
    const nextUser = {
      ...user.value,
      ...patch,
    }
    const changed = Object.keys(patch).some((key) => {
      const typedKey = key as keyof User
      return user.value?.[typedKey] !== nextUser[typedKey]
    })
    if (!changed) {
      return
    }
    user.value = nextUser
    persistAuthState({ accessToken: token.value, user: user.value })
  }

  function handleUnauthorized(requestToken?: string): boolean {
    const currentToken = token.value
    if (!hasRealToken(currentToken)) return false
    if (requestToken !== undefined && requestToken !== currentToken) return false
    clearAuthState()
    return true
  }

  return {
    user,
    token,
    isLoggedIn,
    applyAuthState,
    clearAuthState,
    getAuthGeneration,
    handleUnauthorized,
    invalidateAuthOperations,
    isAuthGenerationCurrent,
    loginWithPhonePassword,
    loginWithEmailPassword,
    loginWithPhoneCode,
    loginWithEmailCode,
    loginWithAuthResponse,
    logout,
    updateCurrentUser,
  }
})

import { defineStore, storeToRefs } from 'pinia'
import {
  finishPhoneCodeLogin as apiFinishPhoneCodeLogin,
  finishEmailCodeLogin as apiFinishEmailCodeLogin,
  loginWithPhonePassword as apiLoginWithPhonePassword,
  loginWithEmailPassword as apiLoginWithEmailPassword,
} from '@/api/auth'
import { getCurrentUser } from '@/api/users'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { OAuthBrowserProof } from '@/utils/oauthBrowserProof'
import { useAuthSessionStore, type AuthOperationContext } from './authSession'
import type {
  FinishPhoneCodeLoginRequest,
  FinishEmailCodeLoginRequest,
  LoginResponse,
  LoginWithEmailPasswordRequest,
  LoginWithPhonePasswordRequest,
  PersistedAuthState,
  User,
} from '@/types'

export type { AuthOperationContext } from './authSession'

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

/** Network orchestration over the per-Pinia session owner. */
export const useAuthStore = defineStore('auth', () => {
  const session = useAuthSessionStore()
  const { token, user, isLoggedIn } = storeToRefs(session)
  let identityRequest: { token: string; generation: number; revision: number; promise: Promise<boolean> } | null = null

  // Keep facade actions as real actions: consumers subscribe to names such as
  // invalidateAuthOperations through this store's $onAction API.
  function invalidateAuthOperations(): number { return session.invalidateAuthOperations() }
  function beginOAuthCallbackOperation(proof: OAuthBrowserProof): number | null { return session.beginOAuthCallbackOperation(proof) }
  function getAuthGeneration(): number { return session.getAuthGeneration() }
  function isAuthGenerationCurrent(generation: number): boolean { return session.isAuthGenerationCurrent(generation) }
  function captureAuthOperationContext(): AuthOperationContext { return session.captureAuthOperationContext() }
  function isAuthOperationContextCurrent(context: AuthOperationContext): boolean { return session.isAuthOperationContextCurrent(context) }
  function applyAuthState(state: Partial<PersistedAuthState> = {}): void { session.applyAuthState(state) }
  function clearAuthState(options: { clearDraft?: boolean } = {}): void { session.clearAuthState(options) }
  function loginWithAuthResponse(state: PersistedAuthState): void { session.loginWithAuthResponse(state) }
  function tryLoginWithAuthResponse(response: PersistedAuthState, context: AuthOperationContext): boolean {
    return session.tryLoginWithAuthResponse(response, context)
  }
  function logout(): void { session.logout() }
  function updateCurrentUser(patch: Partial<User>): void { session.updateCurrentUser(patch) }
  function syncAuthFromStorage(): boolean { return session.syncAuthFromStorage() }
  function hydrateCurrentUser(patch: Partial<User>, expectedToken: string): boolean {
    return session.hydrateCurrentUser(patch, expectedToken)
  }
  function handleUnauthorized(requestToken?: string): boolean { return session.handleUnauthorized(requestToken) }

  async function applyLoginResponse(
    generation: number,
    request: () => Promise<LoginResponse>,
  ): Promise<void> {
    const context = captureAuthOperationContext()
    const isCurrentLogin = (): boolean => context.generation === generation
      && isAuthOperationContextCurrent(context)
    let response: LoginResponse
    try {
      response = await request()
    } catch (error: unknown) {
      if (!isCurrentLogin()) throw new AuthOperationSupersededError()
      throw error
    }

    if (!isCurrentLogin()) throw new AuthOperationSupersededError()

    let nextUser: User | null = response.user || null
    if (!nextUser) {
      try {
        const fetched = await getCurrentUser(response.accessToken)
        if (fetched) nextUser = fetched
      } catch {}
      if (!isCurrentLogin()) throw new AuthOperationSupersededError()
    }

    if (!isCurrentLogin()) throw new AuthOperationSupersededError()
    session.commitAuthState({ accessToken: response.accessToken, user: nextUser })
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

  function refreshCurrentUser(): Promise<boolean> {
    const ownerToken = token.value
    const generation = getAuthGeneration()
    const revision = session.getMetadataRevision()
    if (!hasRealToken(ownerToken) || getStoredAccessToken() !== ownerToken) return Promise.resolve(false)
    if (identityRequest?.token === ownerToken && identityRequest.generation === generation && identityRequest.revision === revision) return identityRequest.promise
    const operation = (async () => {
      const currentUser = await getCurrentUser(ownerToken)
      return isAuthGenerationCurrent(generation) && session.getMetadataRevision() === revision && !!currentUser && hydrateCurrentUser(currentUser, ownerToken)
    })()
    const promise = operation.finally(() => {
      if (identityRequest?.promise === promise) identityRequest = null
    })
    identityRequest = { token: ownerToken, generation, revision, promise }
    return promise
  }

  return {
    user,
    token,
    isLoggedIn,
    applyAuthState,
    clearAuthState,
    beginOAuthCallbackOperation,
    captureAuthOperationContext,
    getAuthGeneration,
    handleUnauthorized,
    invalidateAuthOperations,
    isAuthGenerationCurrent,
    isAuthOperationContextCurrent,
    loginWithPhonePassword,
    loginWithEmailPassword,
    loginWithPhoneCode,
    loginWithEmailCode,
    loginWithAuthResponse,
    tryLoginWithAuthResponse,
    logout,
    updateCurrentUser,
    syncAuthFromStorage,
    hydrateCurrentUser,
    refreshCurrentUser,
  }
})

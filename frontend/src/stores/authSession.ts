import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  clearPersistedAuthState,
  getStoredAccessToken,
  getStoredCurrentUser,
  hasRealToken,
  isMockToken,
  persistAuthState,
  persistCurrentUser,
} from '@/utils/authStorage'
import {
  clearOAuthBrowserProof,
  readOAuthBrowserProof,
  type OAuthBrowserProof,
} from '@/utils/oauthBrowserProof'
import type { PersistedAuthState, User } from '@/types'

export interface AuthOperationContext {
  readonly generation: number
  readonly memoryToken: string
  readonly persistedToken: string
}

/**
 * Owns synchronous authentication session state for one Pinia instance.
 *
 * Network operations deliberately stay in the auth facade. Keeping this store
 * limited to credentials, persistence, and operation guards means importing it
 * never initializes an API client or another auth store.
 */
export const useAuthSessionStore = defineStore('auth-session', () => {
  const token = ref<string>(getStoredAccessToken())
  const user = ref<User | null>(getStoredCurrentUser(token.value))
  const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'
  let authGeneration = 0
  let metadataRevision = 0
  let oauthCallbackClaim: {
    provider: OAuthBrowserProof['provider']
    verifier: string
    expiresAt: number
    generation: number
  } | null = null

  // Clean mock token if needed. Mock mode owns its mock token; normal mode
  // must not leave a non-authenticated mock credential in persisted storage.
  if (!useMock && isMockToken(token.value)) {
    token.value = ''
    clearPersistedAuthState()
  }

  const isLoggedIn = computed(() => hasRealToken(token.value))

  function emitAuthChanged(loggedIn: boolean): void {
    try {
      window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn } }))
    } catch (_) {}
  }

  // This is the old writeAuthState primitive. It intentionally does not
  // advance authGeneration; callers choose whether a session operation should
  // invalidate pending work before committing state.
  function commitAuthState({ accessToken = '', user: nextUser = null }: Partial<PersistedAuthState> = {}): void {
    token.value = hasRealToken(accessToken) ? accessToken : ''
    user.value = nextUser || null
    persistAuthState({ accessToken: token.value, user: user.value })
    emitAuthChanged(!!token.value)
  }

  function invalidateAuthOperations(): number {
    authGeneration += 1
    clearOAuthBrowserProof()
    oauthCallbackClaim = null
    return authGeneration
  }

  function beginOAuthCallbackOperation(proof: OAuthBrowserProof): number | null {
    const current = readOAuthBrowserProof(proof.provider)
    if (!current
      || current.provider !== proof.provider
      || current.verifier !== proof.verifier
      || current.expiresAt !== proof.expiresAt) {
      return null
    }

    if (oauthCallbackClaim
      && oauthCallbackClaim.provider === proof.provider
      && oauthCallbackClaim.verifier === proof.verifier
      && oauthCallbackClaim.expiresAt === proof.expiresAt
      && oauthCallbackClaim.generation === authGeneration) {
      return oauthCallbackClaim.generation
    }

    authGeneration += 1
    oauthCallbackClaim = {
      provider: proof.provider,
      verifier: proof.verifier,
      expiresAt: proof.expiresAt,
      generation: authGeneration,
    }
    return authGeneration
  }

  function getAuthGeneration(): number {
    return authGeneration
  }

  function isAuthGenerationCurrent(generation: number): boolean {
    return generation === authGeneration
  }

  function captureAuthOperationContext(): AuthOperationContext {
    return Object.freeze({
      generation: authGeneration,
      memoryToken: token.value,
      persistedToken: getStoredAccessToken(),
    })
  }

  function isAuthOperationContextCurrent(context: AuthOperationContext): boolean {
    return isAuthGenerationCurrent(context.generation)
      && token.value === context.memoryToken
      && getStoredAccessToken() === context.persistedToken
  }

  function applyAuthState({ accessToken = '', user: nextUser = null }: Partial<PersistedAuthState> = {}): void {
    invalidateAuthOperations()
    commitAuthState({ accessToken, user: nextUser })
  }

  // Browser drafts are independent of auth cleanup and survive logout for
  // account-scoped recovery. Keep the option for caller compatibility.
  function clearAuthState(_options: { clearDraft?: boolean } = {}): void {
    invalidateAuthOperations()
    commitAuthState()
  }

  function loginWithAuthResponse({ accessToken, user: userData }: PersistedAuthState): void {
    applyAuthState({ accessToken, user: userData || null })
  }

  function tryLoginWithAuthResponse(response: PersistedAuthState, context: AuthOperationContext): boolean {
    if (!isAuthOperationContextCurrent(context)) return false
    loginWithAuthResponse(response)
    // Auth listeners run synchronously and may replace the state just written.
    return isAuthGenerationCurrent(context.generation + 1)
      && token.value === response.accessToken
      && getStoredAccessToken() === response.accessToken
  }

  function logout(): void {
    clearAuthState()
  }

  function updateCurrentUser(patch: Partial<User>): void {
    if (!user.value || !hasRealToken(token.value) || getStoredAccessToken() !== token.value) {
      return
    }
    const ownerId = user.value.id ?? user.value.userId
    if ([patch.id, patch.userId].some(id => id != null && String(id) !== String(ownerId))) return
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
    // A confirmed update is newer than identity reads already in flight. This
    // changes metadata ownership without beginning another login session.
    metadataRevision += 1
    user.value = nextUser
    persistCurrentUser(token.value, user.value)
  }

  function getMetadataRevision(): number {
    return metadataRevision
  }

  function syncAuthFromStorage(): boolean {
    const stored = getStoredAccessToken()
    const nextToken = hasRealToken(stored) ? stored : ''
    if (nextToken === token.value) return false
    invalidateAuthOperations()
    token.value = nextToken
    // The token and cached user are separate storage writes. Confirm the new
    // identity with the server before assigning drafts or rendering its data.
    user.value = null
    emitAuthChanged(!!nextToken)
    return true
  }

  function hydrateCurrentUser(patch: Partial<User>, expectedToken: string): boolean {
    if (!hasRealToken(expectedToken) || token.value !== expectedToken || getStoredAccessToken() !== expectedToken) return false
    const id = patch.id ?? patch.userId ?? user.value?.id ?? user.value?.userId
    const validId = typeof id === 'number'
      ? Number.isSafeInteger(id) && id > 0
      : typeof id === 'string' && /^[1-9]\d*$/.test(id)
    const username = patch.username ?? user.value?.username
    if (id == null || !validId || typeof username !== 'string' || !username.trim()) return false
    user.value = { ...(user.value || {}), ...patch, id, username }
    persistCurrentUser(expectedToken, user.value)
    return token.value === expectedToken && getStoredAccessToken() === expectedToken
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
    commitAuthState,
    applyAuthState,
    clearAuthState,
    beginOAuthCallbackOperation,
    captureAuthOperationContext,
    getAuthGeneration,
    getMetadataRevision,
    isAuthGenerationCurrent,
    isAuthOperationContextCurrent,
    invalidateAuthOperations,
    loginWithAuthResponse,
    tryLoginWithAuthResponse,
    logout,
    updateCurrentUser,
    syncAuthFromStorage,
    hydrateCurrentUser,
    handleUnauthorized,
  }
})

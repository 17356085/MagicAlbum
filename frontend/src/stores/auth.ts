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
  LoginWithEmailPasswordRequest,
  LoginWithPhonePasswordRequest,
  PersistedAuthState,
  User,
} from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(getStoredCurrentUser())
  const token = ref<string>(getStoredAccessToken())
  const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

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

  function applyAuthState({ accessToken = '', user: nextUser = null }: Partial<PersistedAuthState> = {}): void {
    token.value = hasRealToken(accessToken) ? accessToken : ''
    user.value = nextUser || null
    persistAuthState({ accessToken: token.value, user: user.value })
    emitAuthChanged(!!token.value)
  }

  function clearAuthState({ clearDraft = false }: { clearDraft?: boolean } = {}): void {
    token.value = ''
    user.value = null
    clearPersistedAuthState()
    if (clearDraft) {
      try { localStorage.removeItem('thread-draft-v1') } catch (_) {}
    }
    emitAuthChanged(false)
  }

  // Actions
  async function loginWithPhonePassword({ phone, password, verifyToken, verifyProvider, verifyScene }: LoginWithPhonePasswordRequest): Promise<void> {
    const { accessToken, user: userData } = await apiLoginWithPhonePassword({ phone, password, verifyToken, verifyProvider, verifyScene })
    let nextUser: User | null = userData || null
    if (!nextUser) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) nextUser = fetched
      } catch {}
    }
    applyAuthState({ accessToken, user: nextUser })
  }

  async function loginWithEmailPassword({ email, password, verifyToken, verifyProvider, verifyScene }: LoginWithEmailPasswordRequest): Promise<void> {
    const { accessToken, user: userData } = await apiLoginWithEmailPassword({ email, password, verifyToken, verifyProvider, verifyScene })
    let nextUser: User | null = userData || null
    if (!nextUser) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) nextUser = fetched
      } catch {}
    }
    applyAuthState({ accessToken, user: nextUser })
  }

  async function loginWithEmailCode({ channel, address, code, session }: FinishEmailCodeLoginRequest): Promise<void> {
    const { accessToken, user: userData } = await apiFinishEmailCodeLogin({ channel, address, code, session })
    let nextUser: User | null = userData || null
    if (!nextUser) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) nextUser = fetched
      } catch {}
    }
    applyAuthState({ accessToken, user: nextUser })
  }

  async function loginWithPhoneCode({ channel, address, code, session }: FinishPhoneCodeLoginRequest): Promise<void> {
    const { accessToken, user: userData } = await apiFinishPhoneCodeLogin({ channel, address, code, session })
    let nextUser: User | null = userData || null
    if (!nextUser) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) nextUser = fetched
      } catch {}
    }
    applyAuthState({ accessToken, user: nextUser })
  }

  function loginWithAuthResponse({ accessToken, user: userData }: PersistedAuthState): void {
    applyAuthState({ accessToken, user: userData || null })
  }

  function logout(): void {
    clearAuthState({ clearDraft: true })
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

  function handleUnauthorized(): void {
    clearAuthState()
  }

  return {
    user,
    token,
    isLoggedIn,
    applyAuthState,
    clearAuthState,
    handleUnauthorized,
    loginWithPhonePassword,
    loginWithEmailPassword,
    loginWithPhoneCode,
    loginWithEmailCode,
    loginWithAuthResponse,
    logout,
    updateCurrentUser,
  }
})

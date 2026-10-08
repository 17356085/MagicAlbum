import type { PersistedAuthState, User } from '@/types'

const AUTH_REDIRECT_KEY = 'authRedirect'

export function isMockToken(token?: string | null): boolean {
  return !!token && String(token).startsWith('mock-token-')
}

export function hasRealToken(token?: string | null): boolean {
  return !!token && !isMockToken(token)
}

export function getStoredAccessToken(): string {
  try {
    return localStorage.getItem('accessToken') || ''
  } catch (_) {
    return ''
  }
}

export function getStoredCurrentUser(): User | null {
  try {
    const raw = localStorage.getItem('currentUser')
    return raw ? (JSON.parse(raw) as User) : null
  } catch (_) {
    return null
  }
}

export function persistAuthState({ accessToken = '', user = null }: Partial<PersistedAuthState> = {}): void {
  try {
    if (hasRealToken(accessToken)) localStorage.setItem('accessToken', accessToken)
    else localStorage.removeItem('accessToken')
  } catch (_) {}

  try {
    if (user) localStorage.setItem('currentUser', JSON.stringify(user))
    else localStorage.removeItem('currentUser')
  } catch (_) {}
}

export function clearPersistedAuthState(): void {
  try { localStorage.removeItem('accessToken') } catch (_) {}
  try { localStorage.removeItem('currentUser') } catch (_) {}
}

export function setPendingAuthRedirect(path: string): void {
  try {
    const next = String(path || '').trim()
    if (next) sessionStorage.setItem(AUTH_REDIRECT_KEY, next)
  } catch (_) {}
}

export function getPendingAuthRedirect(): string {
  try {
    return sessionStorage.getItem(AUTH_REDIRECT_KEY) || ''
  } catch (_) {
    return ''
  }
}

export function clearPendingAuthRedirect(): void {
  try { sessionStorage.removeItem(AUTH_REDIRECT_KEY) } catch (_) {}
}

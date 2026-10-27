import type { PersistedAuthState, User } from '@/types'

const AUTH_REDIRECT_KEY = 'authRedirect'
const CURRENT_USER_SESSION_KEY = 'currentUserSession.v1'

type UserRecord = Record<string, unknown>

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

function normalizeId(value: unknown): string | number | null {
  if (typeof value === 'number') {
    return Number.isSafeInteger(value) && value > 0 ? value : null
  }
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) {
    return value
  }
  return null
}

function normalizeUser(value: unknown): User | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const record = value as UserRecord
  const id = normalizeId(record.id ?? record.userId)
  if (id === null || typeof record.username !== 'string' || !record.username.trim()) return null
  return { ...record, id, username: record.username } as User
}

function tryNormalizeUser(value: unknown): User | null {
  try {
    return normalizeUser(value)
  } catch (_) {
    return null
  }
}

function isUserRecord(value: unknown): value is UserRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function removeBoundIdentity(): void {
  try { localStorage.removeItem(CURRENT_USER_SESSION_KEY) } catch (_) {}
}

function removeBoundIdentityIfMatches(expectedValue: string): void {
  try {
    if (localStorage.getItem(CURRENT_USER_SESSION_KEY) === expectedValue) {
      localStorage.removeItem(CURRENT_USER_SESSION_KEY)
    }
  } catch (_) {}
}

export function getStoredCurrentUser(expectedToken?: string): User | null {
  try {
    const currentToken = getStoredAccessToken()
    if (!hasRealToken(currentToken)) return null
    if (expectedToken !== undefined && expectedToken !== currentToken) return null

    const raw = localStorage.getItem(CURRENT_USER_SESSION_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (!isUserRecord(parsed) || parsed.accessToken !== currentToken) return null
    if (expectedToken !== undefined && parsed.accessToken !== expectedToken) return null

    const user = normalizeUser(parsed.user)
    if (!user) return null

    const finalToken = getStoredAccessToken()
    if (finalToken !== currentToken) return null
    if (expectedToken !== undefined && finalToken !== expectedToken) return null
    return user
  } catch (_) {
    return null
  }
}

export function persistAuthState({ accessToken = '', user = null }: Partial<PersistedAuthState> = {}): void {
  removeBoundIdentity()

  const realToken = hasRealToken(accessToken)
  try {
    if (realToken) localStorage.setItem('accessToken', accessToken)
    else localStorage.removeItem('accessToken')
  } catch (_) {}

  try {
    if (user) localStorage.setItem('currentUser', JSON.stringify(user))
    else localStorage.removeItem('currentUser')
  } catch (_) {}

  if (!realToken || !user) return
  const normalized = tryNormalizeUser(user)
  if (!normalized) return

  let boundValue: string
  try {
    boundValue = JSON.stringify({ accessToken, user: normalized })
  } catch (_) {
    return
  }

  try {
    if (getStoredAccessToken() !== accessToken) return
    localStorage.setItem(CURRENT_USER_SESSION_KEY, boundValue)
    if (getStoredAccessToken() !== accessToken) removeBoundIdentityIfMatches(boundValue)
  } catch (_) {
    removeBoundIdentityIfMatches(boundValue)
  }
}

export function persistCurrentUser(expectedToken: string, user: User): boolean {
  if (!hasRealToken(expectedToken)) return false
  const normalized = tryNormalizeUser(user)
  if (!normalized) return false

  let flatValue: string
  let boundValue: string
  try {
    flatValue = JSON.stringify(normalized)
    boundValue = JSON.stringify({ accessToken: expectedToken, user: normalized })
  } catch (_) {
    return false
  }

  let boundInvalidated = false
  const failClosed = (): false => {
    if (boundInvalidated) removeBoundIdentityIfMatches(boundValue)
    return false
  }

  try {
    if (getStoredAccessToken() !== expectedToken) return false
    boundInvalidated = true
    localStorage.removeItem(CURRENT_USER_SESSION_KEY)
    if (getStoredAccessToken() !== expectedToken) return failClosed()

    localStorage.setItem('currentUser', flatValue)
    if (localStorage.getItem('currentUser') !== flatValue
      || getStoredAccessToken() !== expectedToken) return failClosed()

    localStorage.setItem(CURRENT_USER_SESSION_KEY, boundValue)
    if (localStorage.getItem(CURRENT_USER_SESSION_KEY) !== boundValue
      || getStoredAccessToken() !== expectedToken) return failClosed()
    return true
  } catch (_) {
    return failClosed()
  }
}

export function clearPersistedAuthState(): void {
  removeBoundIdentity()
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

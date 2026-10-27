import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { useAuthSessionStore } from '@/stores/authSession'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 10000,
  withCredentials: false,
})
const requestAuthGenerations = new WeakMap<InternalAxiosRequestConfig, number>()
const publicAuthenticationPaths = new Set([
  '/auth/login', '/users/register', '/auth/code/start', '/auth/code/finish',
  '/auth/oauth/authorize', '/auth/oauth/exchange', '/auth/qr', '/auth/qr/status',
  '/auth/qr/cancel', '/auth/qr/exchange',
])

export class AuthSessionChangedError extends Error {
  readonly code = 'AUTH_SESSION_CHANGED'

  constructor() {
    super('账号已在其他标签页变更，请刷新后重试')
    this.name = 'AuthSessionChangedError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

function isPublicAuthenticationRequest(url: string): boolean {
  try {
    const pathname = new URL(url, window.location.origin).pathname.replace(/^\/api\/v1(?=\/)/, '')
    return publicAuthenticationPaths.has(pathname)
  } catch { return false }
}

export function captureAuthGeneration(): number | undefined {
  try { return useAuthSessionStore().getAuthGeneration() } catch { return undefined }
}

function getAuthSessionToken(): string | undefined {
  try {
    const auth = useAuthSessionStore() as { token?: unknown }
    return typeof auth.token === 'string' ? auth.token : undefined
  } catch (_) {
    return undefined
  }
}

function normalizeRealToken(token?: string | null): string {
  const normalized = typeof token === 'string' ? token.trim() : ''
  return hasRealToken(normalized) ? normalized : ''
}

export function assertAutomaticRequestOwnsCurrentSession(persistedToken: string): void {
  const storeToken = getAuthSessionToken()
  // Some callers/tests use the client without an active Pinia store. Preserve
  // the existing storage-only behavior in that case.
  if (storeToken === undefined) return

  if (normalizeRealToken(storeToken) !== normalizeRealToken(persistedToken)) {
    throw new AuthSessionChangedError()
  }
}

function createRequestId(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return 'ui-' + Date.now() + '-' + Math.random().toString(36).slice(2)
  }
}

function getRequestBearerToken(error: AxiosError): string {
  const headers = error.config?.headers
  if (!headers) return ''

  const authorization = typeof headers.get === 'function'
    ? headers.get('Authorization')
    : headers.Authorization ?? headers.authorization

  if (typeof authorization !== 'string') return ''
  const match = authorization.match(/^Bearer\s+(.+)$/i)
  return match?.[1]?.trim() || ''
}

/**
 * Expire a session only when the failed request used the token that is still
 * current in storage. Native fetch callers (for example the AI SSE stream)
 * use the same bridge as Axios responses.
 */
export function handleCurrentTokenUnauthorized(requestToken: string, requestGeneration?: number): boolean {
  const currentToken = getStoredAccessToken()
  if (!hasRealToken(currentToken) || !hasRealToken(requestToken) || requestToken !== currentToken) {
    return false
  }

  try {
    const auth = useAuthSessionStore()
    // A new login invalidates old requests before its replacement token arrives.
    if (requestGeneration !== undefined && auth.getAuthGeneration() !== requestGeneration) return false
    const result = auth.handleUnauthorized(requestToken)
    return result !== false
  } catch (_) {
    return false
  }
}

api.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const generation = captureAuthGeneration()
    if (generation !== undefined) requestAuthGenerations.set(config, generation)
    config.headers['X-Request-Id'] = config.headers['X-Request-Id'] || createRequestId()
    const existingAuthorization = typeof config.headers.get === 'function'
      ? config.headers.get('Authorization')
      : config.headers.Authorization ?? config.headers.authorization
    if (!existingAuthorization) {
      const token = getStoredAccessToken()
      const requestUrl = String(config.url || '')
      if (!isPublicAuthenticationRequest(requestUrl)) {
        assertAutomaticRequestOwnsCurrentSession(token)
      }
      if (hasRealToken(token)) {
        config.headers.Authorization = `Bearer ${token}`
      }
    }
    return config
  },
  (error: unknown) => { throw error },
  { synchronous: true },
)

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const status = error?.response?.status
    // A 403 means the account is authenticated but lacks permission; it must not
    // be treated as an expired session (for example, a non-admin opening admin UI).
    if (status === 401) {
      try {
        const requestToken = getRequestBearerToken(error)
        const requestUrl = String(error?.config?.url || '')
        const isAuthRequest = isPublicAuthenticationRequest(requestUrl)
        const generation = error.config ? requestAuthGenerations.get(error.config) : undefined
        // A credential rejection describes this login attempt, not an existing bearer session.
        const sameAuthenticatedSession = !isAuthRequest && handleCurrentTokenUnauthorized(requestToken, generation)

        if (sameAuthenticatedSession && !window.__authExpiredAlertShown) {
          window.__authExpiredAlertShown = true
          alert('登录已过期，请重新登录')
          setTimeout(() => {
            window.__authExpiredAlertShown = false
          }, 5000)
        }
      } catch (_) {}
    }
    return Promise.reject(error)
  }
)

export default api

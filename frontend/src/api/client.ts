import axios from 'axios'
import type { AxiosError, InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 10000,
  withCredentials: false,
})

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
export function handleCurrentTokenUnauthorized(requestToken: string): boolean {
  const currentToken = getStoredAccessToken()
  if (!hasRealToken(currentToken) || !hasRealToken(requestToken) || requestToken !== currentToken) {
    return false
  }

  try {
    const result = useAuthStore().handleUnauthorized(requestToken)
    return result !== false
  } catch (_) {
    return false
  }
}

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers['X-Request-Id'] = config.headers['X-Request-Id'] || createRequestId()
  const existingAuthorization = typeof config.headers.get === 'function'
    ? config.headers.get('Authorization')
    : config.headers.Authorization ?? config.headers.authorization
  if (!existingAuthorization) {
    const token = getStoredAccessToken()
    if (hasRealToken(token)) {
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    const status = error?.response?.status
    // A 403 means the account is authenticated but lacks permission; it must not
    // be treated as an expired session (for example, a non-admin opening admin UI).
    if (status === 401) {
      try {
        const requestToken = getRequestBearerToken(error)
        const sameAuthenticatedSession = handleCurrentTokenUnauthorized(requestToken)
        const requestUrl = String(error?.config?.url || '')
        const isAuthRequest =
          /\/auth\/login\b/.test(requestUrl) ||
          /\/users\/register\b/.test(requestUrl)

        if (sameAuthenticatedSession && !isAuthRequest && !window.__authExpiredAlertShown) {
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

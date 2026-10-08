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

api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers['X-Request-Id'] = config.headers['X-Request-Id'] || createRequestId()
  const token = getStoredAccessToken()
  if (hasRealToken(token)) {
    config.headers.Authorization = `Bearer ${token}`
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
        const token = getStoredAccessToken()
        const hadRealToken = hasRealToken(token)
        const requestUrl = String(error?.config?.url || '')
        const isAuthRequest =
          /\/auth\/login\b/.test(requestUrl) ||
          /\/users\/register\b/.test(requestUrl)

        if (hadRealToken) useAuthStore().handleUnauthorized()

        if (hadRealToken && !isAuthRequest && !window.__authExpiredAlertShown) {
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

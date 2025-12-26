import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1',
  timeout: 10000,
  // 如果后端使用 Cookie 会话（Set-Cookie + Access-Control-Allow-Credentials）需要开启
  withCredentials: true,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('accessToken')
  // 避免在非真实登录时发送 mock 令牌
  if (token && !String(token).startsWith('mock-token-')) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      try {
        // 清除过期令牌
        localStorage.removeItem('accessToken')
        // 友好提醒（避免重复弹窗）
        if (!window.__authExpiredAlertShown) {
          window.__authExpiredAlertShown = true
          alert('登录已过期，请重新登录')
          setTimeout(() => { window.__authExpiredAlertShown = false }, 5000)
        }
      } catch (_) {}
    }
    return Promise.reject(error)
  }
)

export default api
import { ref, computed } from 'vue'
import { loginWithPhonePassword as apiLoginWithPhonePassword, loginWithEmailPassword as apiLoginWithEmailPassword } from '@/api/auth'
import { getCurrentUser } from '@/api/users'

const userRef = ref(null)
const tokenRef = ref(localStorage.getItem('accessToken') || '')
const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

// 初始化：若本地有用户信息则载入
try {
  const stored = localStorage.getItem('currentUser')
  if (stored) {
    userRef.value = JSON.parse(stored)
  }
} catch {}

// 若当前非 Mock 模式，但本地仍有 mock-token，自动清理避免误判为已登录
if (!useMock && String(tokenRef.value).startsWith('mock-token-')) {
  tokenRef.value = ''
  localStorage.removeItem('accessToken')
}

export function useAuth() {
  async function loginWithPhonePassword({ phone, password }) {
    const { accessToken, user } = await apiLoginWithPhonePassword({ phone, password })
    tokenRef.value = accessToken || ''
    userRef.value = user || null
    if (!userRef.value) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) userRef.value = fetched
      } catch {}
    }
    if (tokenRef.value) localStorage.setItem('accessToken', tokenRef.value)
    if (userRef.value) localStorage.setItem('currentUser', JSON.stringify(userRef.value))
    // 登录后强制刷新页面，避免残留旧数据或缓存状态
    try { setTimeout(() => { window.location.reload() }, 10) } catch {}
  }

  async function loginWithEmailPassword({ email, password }) {
    const { accessToken, user } = await apiLoginWithEmailPassword({ email, password })
    tokenRef.value = accessToken || ''
    userRef.value = user || null
    if (!userRef.value) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) userRef.value = fetched
      } catch {}
    }
    if (tokenRef.value) localStorage.setItem('accessToken', tokenRef.value)
    if (userRef.value) localStorage.setItem('currentUser', JSON.stringify(userRef.value))
    // 登录后强制刷新页面，避免残留旧数据或缓存状态
    try { setTimeout(() => { window.location.reload() }, 10) } catch {}
  }

  function logout() {
    tokenRef.value = ''
    userRef.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('currentUser')
    // 登出后强制刷新页面，确保所有页面状态重置
    try { setTimeout(() => { window.location.reload() }, 10) } catch {}
  }

  return {
    // 登录状态：存在非 mock 令牌 或 已获取用户信息（适配 Cookie 会话型后端）
    isLoggedIn: computed(() => (
      (!!tokenRef.value && !String(tokenRef.value).startsWith('mock-token-')) || !!userRef.value
    )),
    user: userRef,
    token: tokenRef,
    loginWithPhonePassword,
    loginWithEmailPassword,
    logout,
  }
}
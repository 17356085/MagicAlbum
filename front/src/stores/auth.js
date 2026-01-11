import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loginWithPhonePassword as apiLoginWithPhonePassword, loginWithEmailPassword as apiLoginWithEmailPassword } from '@/api/auth'
import { getCurrentUser } from '@/api/users'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(null)
  const token = ref(localStorage.getItem('accessToken') || '')
  const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

  // Initialize
  try {
    const stored = localStorage.getItem('currentUser')
    if (stored) {
      user.value = JSON.parse(stored)
    }
  } catch {}

  // Clean mock token if needed
  if (!useMock && String(token.value).startsWith('mock-token-')) {
    token.value = ''
    localStorage.removeItem('accessToken')
  }

  // Getters
  const isLoggedIn = computed(() => (
    (!!token.value && !String(token.value).startsWith('mock-token-')) || !!user.value
  ))

  // Actions
  async function loginWithPhonePassword({ phone, password }) {
    const { accessToken, user: userData } = await apiLoginWithPhonePassword({ phone, password })
    token.value = accessToken || ''
    user.value = userData || null
    if (!user.value) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) user.value = fetched
      } catch {}
    }
    if (token.value) localStorage.setItem('accessToken', token.value)
    if (user.value) localStorage.setItem('currentUser', JSON.stringify(user.value))
    
    try { setTimeout(() => { window.location.reload() }, 10) } catch {}
  }

  async function loginWithEmailPassword({ email, password }) {
    const { accessToken, user: userData } = await apiLoginWithEmailPassword({ email, password })
    token.value = accessToken || ''
    user.value = userData || null
    if (!user.value) {
      try {
        const fetched = await getCurrentUser()
        if (fetched) user.value = fetched
      } catch {}
    }
    if (token.value) localStorage.setItem('accessToken', token.value)
    if (user.value) localStorage.setItem('currentUser', JSON.stringify(user.value))
    
    try { setTimeout(() => { window.location.reload() }, 10) } catch {}
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('currentUser')
    try { localStorage.removeItem('thread-draft-v1') } catch {}
    try { setTimeout(() => { window.location.reload() }, 10) } catch {}
  }

  return {
    user,
    token,
    isLoggedIn,
    loginWithPhonePassword,
    loginWithEmailPassword,
    logout
  }
})

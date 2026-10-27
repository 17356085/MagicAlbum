import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { useAuthStorageSync } from '../useAuthStorageSync'

function mountSync(): VueWrapper {
  return mount(defineComponent({
    setup() {
      useAuthStorageSync()
      return () => null
    },
  }))
}

function dispatchStorage(
  key: string | null,
  storageArea: Storage | null = localStorage,
  newValue: string | null = null,
): void {
  window.dispatchEvent(new StorageEvent('storage', {
    key,
    oldValue: null,
    newValue,
    storageArea,
  }))
}

function activateAccount(token: string, user = { id: 1, username: 'alice' }): void {
  useAuthStore().applyAuthState({ accessToken: token, user })
}

describe('useAuthStorageSync', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })

  it('reconciles an external token on mount and preserves stale identity storage and drafts', () => {
    const auth = useAuthStore()
    activateAccount('account-a')
    const staleUser = localStorage.getItem('currentUser')
    localStorage.setItem('thread-draft-v1:1', 'draft-a')
    const generation = auth.getAuthGeneration()
    localStorage.setItem('accessToken', 'account-b')

    const wrapper = mountSync()
    try {
      expect(auth.token).toBe('account-b')
      expect(auth.user).toBeNull()
      expect(auth.getAuthGeneration()).toBeGreaterThan(generation)
      expect(localStorage.getItem('currentUser')).toBe(staleUser)
      expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft-a')
    } finally {
      wrapper.unmount()
    }
  })

  it('reconciles external login and logout without writing stale identity or drafts', () => {
    const auth = useAuthStore()
    activateAccount('account-a')
    const staleUser = localStorage.getItem('currentUser')
    localStorage.setItem('thread-draft-v1:1', 'draft-a')
    const authChanged = vi.fn()
    window.addEventListener('auth-state-changed', authChanged)
    const wrapper = mountSync()

    try {
      const initialGeneration = auth.getAuthGeneration()
      localStorage.setItem('accessToken', 'account-b')
      dispatchStorage('accessToken', localStorage, 'account-b')

      expect(auth.token).toBe('account-b')
      expect(auth.user).toBeNull()
      expect(auth.getAuthGeneration()).toBeGreaterThan(initialGeneration)
      expect(localStorage.getItem('accessToken')).toBe('account-b')
      expect(localStorage.getItem('currentUser')).toBe(staleUser)
      expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft-a')

      const loginGeneration = auth.getAuthGeneration()
      localStorage.removeItem('accessToken')
      dispatchStorage('accessToken', null, null)

      expect(auth.token).toBe('')
      expect(auth.isLoggedIn).toBe(false)
      expect(auth.user).toBeNull()
      expect(auth.getAuthGeneration()).toBeGreaterThan(loginGeneration)
      expect(localStorage.getItem('accessToken')).toBeNull()
      expect(localStorage.getItem('currentUser')).toBe(staleUser)
      expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft-a')
      expect(authChanged.mock.calls.map(([event]) => (event as CustomEvent).detail)).toEqual([
        { loggedIn: true },
        { loggedIn: false },
      ])
    } finally {
      wrapper.unmount()
      window.removeEventListener('auth-state-changed', authChanged)
    }
  })

  it('does not change generation or emit auth events for the same token', () => {
    const auth = useAuthStore()
    activateAccount('account-a')
    const wrapper = mountSync()
    const generation = auth.getAuthGeneration()
    const authChanged = vi.fn()
    window.addEventListener('auth-state-changed', authChanged)

    try {
      dispatchStorage('accessToken', localStorage, 'account-a')
      window.dispatchEvent(new Event('focus'))
      document.dispatchEvent(new Event('visibilitychange'))

      expect(auth.token).toBe('account-a')
      expect(auth.getAuthGeneration()).toBe(generation)
      expect(authChanged).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      window.removeEventListener('auth-state-changed', authChanged)
    }
  })

  it('ignores unrelated localStorage keys and sessionStorage events', () => {
    const auth = useAuthStore()
    activateAccount('account-a')
    const wrapper = mountSync()
    const generation = auth.getAuthGeneration()
    const authChanged = vi.fn()
    window.addEventListener('auth-state-changed', authChanged)

    try {
      localStorage.setItem('accessToken', 'account-b')
      dispatchStorage('currentUser', localStorage, JSON.stringify({ id: 2, username: 'bob' }))
      dispatchStorage('unrelated-key', localStorage, 'value')
      dispatchStorage('accessToken', sessionStorage, 'account-b')
      dispatchStorage(null, sessionStorage, null)

      expect(auth.token).toBe('account-a')
      expect(auth.user).toMatchObject({ id: 1, username: 'alice' })
      expect(auth.getAuthGeneration()).toBe(generation)
      expect(authChanged).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
      window.removeEventListener('auth-state-changed', authChanged)
    }
  })

  it('ignores hidden visibility changes and reconciles when visible or focused', () => {
    const auth = useAuthStore()
    activateAccount('account-a')
    const wrapper = mountSync()
    const authChanged = vi.fn()
    window.addEventListener('auth-state-changed', authChanged)

    try {
      Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
      localStorage.setItem('accessToken', 'account-b')
      document.dispatchEvent(new Event('visibilitychange'))

      expect(auth.token).toBe('account-a')
      expect(authChanged).not.toHaveBeenCalled()

      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
      document.dispatchEvent(new Event('visibilitychange'))
      expect(auth.token).toBe('account-b')
      expect(auth.user).toBeNull()
      expect(authChanged).toHaveBeenCalledTimes(1)

      localStorage.setItem('accessToken', 'account-c')
      window.dispatchEvent(new Event('focus'))
      expect(auth.token).toBe('account-c')
      expect(auth.user).toBeNull()
      expect(authChanged).toHaveBeenCalledTimes(2)
    } finally {
      wrapper.unmount()
      window.removeEventListener('auth-state-changed', authChanged)
    }
  })

  it('removes every listener when unmounted', () => {
    const auth = useAuthStore()
    activateAccount('account-a')
    const wrapper = mountSync()
    const generation = auth.getAuthGeneration()
    const authChanged = vi.fn()
    window.addEventListener('auth-state-changed', authChanged)
    wrapper.unmount()

    localStorage.setItem('accessToken', 'account-b')
    dispatchStorage('accessToken', localStorage, 'account-b')
    window.dispatchEvent(new Event('focus'))
    document.dispatchEvent(new Event('visibilitychange'))

    expect(auth.token).toBe('account-a')
    expect(auth.user).toMatchObject({ id: 1, username: 'alice' })
    expect(auth.getAuthGeneration()).toBe(generation)
    expect(authChanged).not.toHaveBeenCalled()
    window.removeEventListener('auth-state-changed', authChanged)
  })
})

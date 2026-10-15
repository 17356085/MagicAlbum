import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../auth'

describe('auth draft ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('keeps legacy and account-scoped drafts when clearAuthState is called', () => {
    const legacy = JSON.stringify({ title: '旧无归属草稿' })
    const accountDraft = JSON.stringify({ title: '账号草稿' })
    localStorage.setItem('thread-draft-v1', legacy)
    localStorage.setItem('thread-draft-v1:7', accountDraft)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-7', user: { id: 7, username: 'seven' } })

    auth.clearAuthState({ clearDraft: true })

    expect(localStorage.getItem('thread-draft-v1')).toBe(legacy)
    expect(localStorage.getItem('thread-draft-v1:7')).toBe(accountDraft)
    expect(auth.isLoggedIn).toBe(false)
  })

  it('keeps account-scoped drafts across logout for local recovery', () => {
    const accountDraft = JSON.stringify({ title: '退出后仍可恢复' })
    localStorage.setItem('thread-draft-v1:7', accountDraft)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-7', user: { id: 7, username: 'seven' } })

    auth.logout()

    expect(localStorage.getItem('thread-draft-v1:7')).toBe(accountDraft)
    expect(localStorage.getItem('currentUser')).toBeNull()
    expect(localStorage.getItem('accessToken')).toBeNull()
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@/types'
import {
  clearPersistedAuthState,
  getStoredAccessToken,
  getStoredCurrentUser,
  persistAuthState,
  persistCurrentUser,
} from '../authStorage'

const TOKEN_A = 'account-a'
const TOKEN_B = 'account-b'
const BOUND_KEY = 'currentUserSession.v1'

const USER_A: User = {
  id: 1,
  username: 'alice',
  nickname: 'Alice',
  avatarUrl: '/alice.png',
}

const USER_B: User = {
  id: 2,
  username: 'bob',
  nickname: 'Bob',
}

function storeBoundIdentity(accessToken: string, user: unknown): void {
  localStorage.setItem(BOUND_KEY, JSON.stringify({ accessToken, user }))
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('bound persisted auth identity', () => {
  it('reproduces the old bug by rejecting a legacy flat user without a bound entry', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    localStorage.setItem('currentUser', JSON.stringify(USER_A))

    expect(getStoredCurrentUser()).toBeNull()
  })

  it('rejects an identity bound to account A after the persisted token changes to account B', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    storeBoundIdentity(TOKEN_A, USER_A)
    localStorage.setItem('accessToken', TOKEN_B)

    expect(getStoredCurrentUser()).toBeNull()
  })

  it('restores a paired identity with extra metadata after a cold read', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    storeBoundIdentity(TOKEN_A, {
      userId: '42',
      username: 'alice',
      nickname: 'Alice',
      profile: { theme: 'blue' },
    })

    expect(getStoredCurrentUser()).toEqual({
      id: '42',
      userId: '42',
      username: 'alice',
      nickname: 'Alice',
      profile: { theme: 'blue' },
    })
  })

  it('requires a real current token and an optional expected token match', () => {
    storeBoundIdentity(TOKEN_A, USER_A)
    expect(getStoredCurrentUser()).toBeNull()

    localStorage.setItem('accessToken', 'mock-token-test')
    expect(getStoredCurrentUser()).toBeNull()

    localStorage.setItem('accessToken', TOKEN_A)
    expect(getStoredCurrentUser(TOKEN_B)).toBeNull()
    expect(getStoredCurrentUser(TOKEN_A)).toEqual(USER_A)
  })

  it('does not read or clear the legacy user cache or unrelated drafts and history', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    localStorage.setItem('currentUser', JSON.stringify(USER_A))
    localStorage.setItem('thread-draft-v1:1', 'draft')
    localStorage.setItem('history-v1:1', 'history')
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')

    expect(getStoredCurrentUser()).toBeNull()
    expect(getItem.mock.calls.some(([key]) => key === 'currentUser')).toBe(false)
    expect(removeItem).not.toHaveBeenCalled()
    expect(localStorage.getItem('currentUser')).toBe(JSON.stringify(USER_A))
    expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft')
    expect(localStorage.getItem('history-v1:1')).toBe('history')
  })

  it('rejects token-only, malformed, and invalid bound records', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    expect(getStoredCurrentUser()).toBeNull()

    const invalidRecords = [
      '{',
      JSON.stringify({ accessToken: TOKEN_A }),
      JSON.stringify({ accessToken: TOKEN_A, user: null }),
      JSON.stringify({ accessToken: TOKEN_A, user: [] }),
      JSON.stringify({ accessToken: TOKEN_A, user: { id: 0, username: 'alice' } }),
      JSON.stringify({ accessToken: TOKEN_A, user: { id: -1, username: 'alice' } }),
      JSON.stringify({ accessToken: TOKEN_A, user: { id: Number.MAX_SAFE_INTEGER + 1, username: 'alice' } }),
      JSON.stringify({ accessToken: TOKEN_A, user: { id: '01', username: 'alice' } }),
      JSON.stringify({ accessToken: TOKEN_A, user: { id: '42', username: ' ' } }),
    ]

    for (const raw of invalidRecords) {
      localStorage.setItem(BOUND_KEY, raw)
      expect(getStoredCurrentUser()).toBeNull()
    }
  })

  it('keeps a token-only persistence unbound and refuses invalid identities', () => {
    persistAuthState({ accessToken: TOKEN_A, user: null })
    expect(getStoredAccessToken()).toBe(TOKEN_A)
    expect(getStoredCurrentUser()).toBeNull()
    expect(localStorage.getItem(BOUND_KEY)).toBeNull()

    persistAuthState({
      accessToken: TOKEN_A,
      user: { id: 0, username: 'alice' } as User,
    })
    expect(localStorage.getItem('currentUser')).toBe(JSON.stringify({ id: 0, username: 'alice' }))
    expect(localStorage.getItem(BOUND_KEY)).toBeNull()
    expect(getStoredCurrentUser()).toBeNull()
  })

  it('rejects a token change during the bound read', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    storeBoundIdentity(TOKEN_A, USER_A)
    const originalGetItem = Storage.prototype.getItem
    const originalSetItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (this: Storage, key: string) {
      const value = originalGetItem.call(this, key)
      if (key === BOUND_KEY) originalSetItem.call(this, 'accessToken', TOKEN_B)
      return value
    })

    expect(getStoredCurrentUser()).toBeNull()
  })

  it('does not accept a mismatched identity while persistence writes are observable', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    storeBoundIdentity(TOKEN_A, USER_A)
    localStorage.setItem('currentUser', JSON.stringify(USER_A))
    const snapshots: Array<unknown> = []
    const originalSetItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      originalSetItem.call(this, key, value)
      if (key !== BOUND_KEY) snapshots.push(getStoredCurrentUser())
    })

    persistAuthState({ accessToken: TOKEN_B, user: USER_B })

    expect(snapshots).toEqual([null, null])
    expect(getStoredCurrentUser()).toEqual(USER_B)
  })

  it('updates metadata for the same token without touching the bearer', () => {
    persistAuthState({ accessToken: TOKEN_A, user: USER_A })
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')
    const nextUser = {
      ...USER_A,
      nickname: 'Alice updated',
      extra: { source: 'profile' },
    }

    expect(persistCurrentUser(TOKEN_A, nextUser as User)).toBe(true)
    expect(getStoredAccessToken()).toBe(TOKEN_A)
    expect(setItem.mock.calls.some(([key]) => key === 'accessToken')).toBe(false)
    expect(removeItem.mock.calls.some(([key]) => key === 'accessToken')).toBe(false)
    expect(getStoredCurrentUser(TOKEN_A)).toEqual(nextUser)
    expect(JSON.parse(localStorage.getItem('currentUser') || 'null')).toEqual(nextUser)
  })

  it('refuses stale metadata writes before changing either identity cache', () => {
    persistAuthState({ accessToken: TOKEN_A, user: USER_A })
    const beforeFlat = localStorage.getItem('currentUser')
    const beforeBound = localStorage.getItem(BOUND_KEY)
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    const removeItem = vi.spyOn(Storage.prototype, 'removeItem')

    expect(persistCurrentUser(TOKEN_B, USER_B)).toBe(false)
    expect(setItem).not.toHaveBeenCalled()
    expect(removeItem).not.toHaveBeenCalled()
    expect(getStoredAccessToken()).toBe(TOKEN_A)
    expect(localStorage.getItem('currentUser')).toBe(beforeFlat)
    expect(localStorage.getItem(BOUND_KEY)).toBe(beforeBound)
    expect(getStoredCurrentUser()).toEqual(USER_A)
  })

  it('fails closed when the token changes during metadata persistence', () => {
    persistAuthState({ accessToken: TOKEN_A, user: USER_A })
    const originalSetItem = Storage.prototype.setItem
    let tokenSwitches = 0
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      originalSetItem.call(this, key, value)
      if (key === 'currentUser') {
        tokenSwitches += 1
        originalSetItem.call(this, 'accessToken', TOKEN_B)
      }
    })

    expect(persistCurrentUser(TOKEN_A, USER_B)).toBe(false)
    expect(getStoredAccessToken()).toBe(TOKEN_B)
    expect(getStoredCurrentUser()).toBeNull()
    expect(tokenSwitches).toBe(1)
  })

  it('does not remove a newer bound identity during stale metadata cleanup', () => {
    persistAuthState({ accessToken: TOKEN_A, user: USER_A })
    const nextUser = { ...USER_A, nickname: 'Alice updated' }
    const accountABound = JSON.stringify({ accessToken: TOKEN_A, user: nextUser })
    const accountBBound = JSON.stringify({ accessToken: TOKEN_B, user: USER_B })
    const originalSetItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      originalSetItem.call(this, key, value)
      if (key === BOUND_KEY && value === accountABound) {
        originalSetItem.call(this, 'accessToken', TOKEN_B)
        originalSetItem.call(this, BOUND_KEY, accountBBound)
      }
    })

    expect(persistCurrentUser(TOKEN_A, nextUser)).toBe(false)
    expect(localStorage.getItem('accessToken')).toBe(TOKEN_B)
    expect(localStorage.getItem(BOUND_KEY)).toBe(accountBBound)
    expect(getStoredCurrentUser()).toEqual(USER_B)
  })

  it('does not remove a newer bound identity during login persistence cleanup', () => {
    const accountABound = JSON.stringify({ accessToken: TOKEN_A, user: USER_A })
    const accountBBound = JSON.stringify({ accessToken: TOKEN_B, user: USER_B })
    const originalSetItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      originalSetItem.call(this, key, value)
      if (key === BOUND_KEY && value === accountABound) {
        originalSetItem.call(this, 'accessToken', TOKEN_B)
        originalSetItem.call(this, BOUND_KEY, accountBBound)
      }
    })

    persistAuthState({ accessToken: TOKEN_A, user: USER_A })

    expect(localStorage.getItem('accessToken')).toBe(TOKEN_B)
    expect(localStorage.getItem(BOUND_KEY)).toBe(accountBBound)
    expect(getStoredCurrentUser()).toEqual(USER_B)
  })

  it('returns false on bound storage failure and preserves unrelated data', () => {
    persistAuthState({ accessToken: TOKEN_A, user: USER_A })
    localStorage.setItem('thread-draft-v1:1', 'draft')
    localStorage.setItem('history-v1:1', 'history')
    const originalSetItem = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      if (key === BOUND_KEY) throw new DOMException('Blocked', 'QuotaExceededError')
      originalSetItem.call(this, key, value)
    })

    expect(persistCurrentUser(TOKEN_A, USER_B)).toBe(false)
    expect(getStoredAccessToken()).toBe(TOKEN_A)
    expect(getStoredCurrentUser()).toBeNull()
    expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft')
    expect(localStorage.getItem('history-v1:1')).toBe('history')
  })

  it('clears the bound identity while keeping drafts and history', () => {
    localStorage.setItem('accessToken', TOKEN_A)
    localStorage.setItem('currentUser', JSON.stringify(USER_A))
    storeBoundIdentity(TOKEN_A, USER_A)
    localStorage.setItem('thread-draft-v1:1', 'draft')
    localStorage.setItem('history-v1:1', 'history')

    clearPersistedAuthState()

    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(localStorage.getItem('currentUser')).toBeNull()
    expect(localStorage.getItem(BOUND_KEY)).toBeNull()
    expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft')
    expect(localStorage.getItem('history-v1:1')).toBe('history')
  })
})

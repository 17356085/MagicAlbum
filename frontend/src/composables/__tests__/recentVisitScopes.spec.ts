import { beforeEach, describe, expect, it, vi } from 'vitest'
import { persistAuthState } from '@/utils/authStorage'
import {
  captureRecentVisitsScope,
  clearAllRecentVisits,
  getAllRecentVisits,
  getRecentVisits,
  isRecentVisitsScopeCurrent,
  pruneExpired,
  removeRecentVisit,
  type RecentVisitsScope,
} from '../useRecentVisits'

const A_KEY = 'recent_visits_v1:1'
const B_KEY = 'recent_visits_v1:2'
const GUEST_KEY = 'recent_visits_v1:guest'
const A_RECORD = { path: '/threads/a', title: '账号 A', id: 1, ts: Date.now() }
const B_RECORD = { path: '/threads/b', title: '账号 B', id: 2, ts: Date.now() }

function persistAccount(token: string, id: number, username: string): void {
  persistAuthState({ accessToken: token, user: { id, username } })
}

function requireScope(scope: ReturnType<typeof captureRecentVisitsScope>): RecentVisitsScope {
  if (!scope) throw new Error('expected a captured recent-visits scope')
  return scope
}

describe('recent visit storage scopes', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('rejects an old A scope after storage moves to B without reads, writes, pruning, or events', () => {
    persistAccount('token-a', 1, 'a')
    const scopeA = requireScope(captureRecentVisitsScope({ token: 'token-a', ownerId: 1 }))
    expect(Object.isFrozen(scopeA)).toBe(true)
    localStorage.setItem(A_KEY, JSON.stringify([A_RECORD]))

    persistAccount('token-b', 2, 'b')
    localStorage.setItem(B_KEY, JSON.stringify([B_RECORD]))
    const before = {
      a: localStorage.getItem(A_KEY),
      b: localStorage.getItem(B_KEY),
    }
    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(captureRecentVisitsScope({ token: 'token-a', ownerId: 1 })).toBeNull()
      expect(isRecentVisitsScopeCurrent(scopeA)).toBe(false)
      expect(getRecentVisits(5, scopeA)).toEqual([])
      expect(getAllRecentVisits(scopeA)).toEqual([])
      clearAllRecentVisits(scopeA)
      removeRecentVisit(A_RECORD, scopeA)
      pruneExpired(scopeA)
      expect(updated).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }

    expect(localStorage.getItem(A_KEY)).toBe(before.a)
    expect(localStorage.getItem(B_KEY)).toBe(before.b)
  })

  it('refuses an old scope when the same owner rotates its token', () => {
    persistAccount('token-a', 1, 'a')
    const scopeA = requireScope(captureRecentVisitsScope({ token: 'token-a', ownerId: 1 }))
    localStorage.setItem(A_KEY, JSON.stringify([A_RECORD]))

    persistAccount('token-a-rotated', 1, 'a')
    const before = localStorage.getItem(A_KEY)
    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(captureRecentVisitsScope({ token: 'token-a', ownerId: 1 })).toBeNull()
      expect(isRecentVisitsScopeCurrent(scopeA)).toBe(false)
      expect(getRecentVisits(5, scopeA)).toEqual([])
      clearAllRecentVisits(scopeA)
      pruneExpired(scopeA)
      expect(updated).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }
    expect(localStorage.getItem(A_KEY)).toBe(before)
  })

  it('requires a confirmed memory owner for every real-token scope', () => {
    persistAccount('token-a', 1, 'a')
    const unscoped = requireScope(captureRecentVisitsScope())
    expect(unscoped.ownerId).toBe('1')
    expect(captureRecentVisitsScope({ token: 'token-a', ownerId: null })).toBeNull()

    localStorage.setItem(A_KEY, JSON.stringify([A_RECORD]))
    const forgedMissingOwner: RecentVisitsScope = Object.freeze({ token: 'token-a', ownerId: null, key: A_KEY })
    const before = localStorage.getItem(A_KEY)
    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(getRecentVisits(5, forgedMissingOwner)).toEqual([])
      clearAllRecentVisits(forgedMissingOwner)
      removeRecentVisit(A_RECORD, forgedMissingOwner)
      pruneExpired(forgedMissingOwner)
      expect(updated).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }
    expect(localStorage.getItem(A_KEY)).toBe(before)
  })

  it('rejects a same-token owner drift before any scoped mutation', () => {
    persistAccount('token-same', 1, 'a')
    const scopeA = requireScope(captureRecentVisitsScope({ token: 'token-same', ownerId: 1 }))
    localStorage.setItem(A_KEY, JSON.stringify([A_RECORD]))

    persistAccount('token-same', 2, 'b')
    localStorage.setItem(B_KEY, JSON.stringify([B_RECORD]))
    const before = {
      a: localStorage.getItem(A_KEY),
      b: localStorage.getItem(B_KEY),
    }
    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(captureRecentVisitsScope({ token: 'token-same', ownerId: 1 })).toBeNull()
      expect(isRecentVisitsScopeCurrent(scopeA)).toBe(false)
      expect(getAllRecentVisits(scopeA)).toEqual([])
      clearAllRecentVisits(scopeA)
      removeRecentVisit(A_RECORD, scopeA)
      pruneExpired(scopeA)
      expect(updated).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }
    expect(localStorage.getItem(A_KEY)).toBe(before.a)
    expect(localStorage.getItem(B_KEY)).toBe(before.b)
  })

  it('allows a valid guest scope and only clears the guest key', () => {
    persistAuthState({ accessToken: 'mock-token-guest', user: { id: 99, username: 'guest' } })
    const guestScope = requireScope(captureRecentVisitsScope({ token: 'mock-token-guest', ownerId: 99 }))
    expect(guestScope).toMatchObject({ token: '', ownerId: null, key: GUEST_KEY })
    localStorage.setItem(GUEST_KEY, JSON.stringify([A_RECORD]))
    localStorage.setItem(A_KEY, JSON.stringify([B_RECORD]))
    const accountBytes = localStorage.getItem(A_KEY)

    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      clearAllRecentVisits(guestScope)
      expect(updated).toHaveBeenCalledTimes(1)
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }

    expect(localStorage.getItem(GUEST_KEY)).toBeNull()
    expect(localStorage.getItem(A_KEY)).toBe(accountBytes)
  })

  it('treats an explicit null scope as invalid for every scoped operation', () => {
    persistAccount('token-a', 1, 'a')
    localStorage.setItem(A_KEY, JSON.stringify([A_RECORD]))
    localStorage.setItem(B_KEY, JSON.stringify([B_RECORD]))
    const before = {
      a: localStorage.getItem(A_KEY),
      b: localStorage.getItem(B_KEY),
    }
    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(getRecentVisits(5, null)).toEqual([])
      expect(getAllRecentVisits(null)).toEqual([])
      clearAllRecentVisits(null)
      removeRecentVisit(A_RECORD, null)
      pruneExpired(null)
      expect(updated).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }
    expect(localStorage.getItem(A_KEY)).toBe(before.a)
    expect(localStorage.getItem(B_KEY)).toBe(before.b)
  })
})

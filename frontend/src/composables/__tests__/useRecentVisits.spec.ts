import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { persistAuthState } from '@/utils/authStorage'
import {
  addVisit,
  captureRecentVisitsScope,
  clearAllRecentVisits,
  getAllRecentVisits,
  getRecentVisits,
  initRecentVisitsTracking,
  pruneExpired,
  removeRecentVisit,
  updateVisitSectionById,
  updateVisitTitleById,
  updateVisitTitleByPath,
} from '../useRecentVisits'

function setCurrentUser(id: number | null, token = id == null ? '' : `account-${id}`): void {
  if (id == null) {
    persistAuthState({ accessToken: '', user: null })
  } else {
    persistAuthState({ accessToken: token, user: { id, username: `user-${id}` } })
  }
}

afterEach(() => {
  delete window.__recent_visits_hook_installed
  vi.restoreAllMocks()
})

function runTracking(identityProvider?: unknown): void {
  delete window.__recent_visits_hook_installed
  const afterEachHook = vi.fn()
  const router = { afterEach: afterEachHook }
  Reflect.apply(initRecentVisitsTracking, null, [router, identityProvider])
  const callback = afterEachHook.mock.calls[0]?.[0] as ((to: unknown) => void) | undefined
  if (!callback) return
  callback({
    name: 'thread-detail',
    path: '/threads/tracked',
    fullPath: '/threads/tracked?source=history',
    params: { id: 'tracked' },
    meta: { title: '导航记录' },
  })
}

describe('useRecentVisits account-scoped clearing', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('clears only the active account key and notifies listeners', () => {
    setCurrentUser(1)
    addVisit({ path: '/threads/one', title: '账号 A 的帖子', id: 11 })
    setCurrentUser(2)
    addVisit({ path: '/threads/two', title: '账号 B 的帖子', id: 22 })
    setCurrentUser(1)

    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    clearAllRecentVisits()

    expect(localStorage.getItem('recent_visits_v1:1')).toBeNull()
    expect(JSON.parse(localStorage.getItem('recent_visits_v1:2') || '[]')).toEqual([
      expect.objectContaining({ title: '账号 B 的帖子', id: 22 }),
    ])
    expect(getRecentVisits()).toEqual([])
    expect(updated).toHaveBeenCalledTimes(1)
    window.removeEventListener('recent-visits-updated', updated)
  })

  it('uses the guest key when no account is stored', () => {
    setCurrentUser(null)
    localStorage.setItem('currentUser', JSON.stringify({ id: 99, username: 'stale-user' }))
    addVisit({ path: '/threads/guest', title: '访客帖子', id: 33 })
    setCurrentUser(1)
    addVisit({ path: '/threads/account', title: '账号帖子', id: 44 })
    setCurrentUser(null)

    clearAllRecentVisits()

    expect(localStorage.getItem('recent_visits_v1:guest')).toBeNull()
    expect(JSON.parse(localStorage.getItem('recent_visits_v1:1') || '[]')).toEqual([
      expect.objectContaining({ title: '账号帖子', id: 44 }),
    ])
  })

  it('does not expose or mutate owner history when the real token has mismatched bound metadata', () => {
    setCurrentUser(1, 'account-a')
    addVisit({ path: '/threads/account', title: '账号帖子', id: 11 })
    localStorage.removeItem('accessToken')
    addVisit({ path: '/threads/guest', title: '访客帖子', id: 22 })

    localStorage.setItem('accessToken', 'account-b')
    localStorage.setItem('currentUser', JSON.stringify({ id: 1, username: 'user-1' }))

    const updated = vi.fn()
    window.addEventListener('recent-visits-updated', updated)
    try {
      expect(getRecentVisits()).toEqual([])
      addVisit({ path: '/threads/attacker', title: '不应写入', id: 33 })
      clearAllRecentVisits()
      pruneExpired()
      removeRecentVisit({ path: '/threads/account', id: 11, ts: Date.now() })
      updateVisitTitleByPath('/threads/account', '不应修改')
      updateVisitTitleById(11, '不应修改')
      updateVisitSectionById(11, 99, '不应修改')
      expect(getAllRecentVisits()).toEqual([])
      expect(updated).not.toHaveBeenCalled()
    } finally {
      window.removeEventListener('recent-visits-updated', updated)
    }

    expect(JSON.parse(localStorage.getItem('recent_visits_v1:1') || '[]')).toEqual([
      expect.objectContaining({ title: '账号帖子', id: 11 }),
    ])
    expect(JSON.parse(localStorage.getItem('recent_visits_v1:guest') || '[]')).toEqual([
      expect.objectContaining({ title: '访客帖子', id: 22 }),
    ])
    expect(localStorage.getItem('recent_visits_v1:2')).toBeNull()
  })

  it('ignores legacy flat currentUser metadata for a real token', () => {
    setCurrentUser(1, 'account-a')
    addVisit({ path: '/threads/account', title: '账号帖子', id: 11 })
    localStorage.setItem('accessToken', 'account-b')
    localStorage.removeItem('currentUserSession.v1')
    localStorage.setItem('currentUser', JSON.stringify({ id: 1, username: 'user-1' }))

    expect(getRecentVisits()).toEqual([])
    addVisit({ path: '/threads/should-not-write', title: '不应写入', id: 12 })
    expect(JSON.parse(localStorage.getItem('recent_visits_v1:1') || '[]')).toEqual([
      expect.objectContaining({ title: '账号帖子', id: 11 }),
    ])
  })

  it('reloads a verified owner history from its existing per-id key', () => {
    setCurrentUser(7, 'account-seven')
    addVisit({ path: '/threads/saved', title: '保留帖子', id: 77 })

    setCurrentUser(7, 'account-seven')

    expect(getRecentVisits()).toEqual([
      expect.objectContaining({ path: '/threads/saved', title: '保留帖子', id: 77 }),
    ])
  })

  it('fails closed when the tracking provider still represents account A after storage moved to B', () => {
    setCurrentUser(2, 'account-b')
    const beforeB = JSON.stringify([{ path: '/threads/b', title: '账号 B', id: 2, ts: Date.now() }])
    localStorage.setItem('recent_visits_v1:2', beforeB)

    runTracking(() => ({ token: 'account-a', ownerId: 1 }))

    expect(localStorage.getItem('recent_visits_v1:1')).toBeNull()
    expect(localStorage.getItem('recent_visits_v1:2')).toBe(beforeB)
    expect(localStorage.getItem('recent_visits_v1:guest')).toBeNull()
  })

  it('rejects same-token owner drift before the global hook can append to the new owner key', () => {
    setCurrentUser(2, 'same-token')
    const beforeB = JSON.stringify([{ path: '/threads/b', title: '账号 B', id: 2, ts: Date.now() }])
    localStorage.setItem('recent_visits_v1:2', beforeB)

    runTracking(() => ({ token: 'same-token', ownerId: 1 }))

    expect(localStorage.getItem('recent_visits_v1:1')).toBeNull()
    expect(localStorage.getItem('recent_visits_v1:2')).toBe(beforeB)
  })

  it('keeps normal account and guest tracking when the caller supplies the current identity', () => {
    setCurrentUser(1, 'account-a')
    runTracking(() => ({ token: 'account-a', ownerId: 1 }))
    expect(getAllRecentVisits()).toEqual([
      expect.objectContaining({ path: '/threads/tracked', title: '导航记录' }),
    ])

    setCurrentUser(null)
    runTracking(() => ({ token: '', ownerId: null }))
    expect(localStorage.getItem('recent_visits_v1:1')).toContain('导航记录')
    expect(JSON.parse(localStorage.getItem('recent_visits_v1:guest') || '[]')).toEqual([
      expect.objectContaining({ path: '/threads/tracked', title: '导航记录' }),
    ])
  })

  it('does not infer an account from storage when the identity provider is missing', () => {
    setCurrentUser(1, 'account-a')
    runTracking()
    expect(localStorage.getItem('recent_visits_v1:1')).toBeNull()
    expect(localStorage.getItem('recent_visits_v1:guest')).toBeNull()
  })

  it('revalidates a supplied scope after reading before writing the list', () => {
    setCurrentUser(1, 'account-a')
    const scope = captureRecentVisitsScope({ token: 'account-a', ownerId: 1 })
    if (!scope) throw new Error('expected account A scope')
    const beforeA = JSON.stringify([{ path: '/threads/a', title: '账号 A', id: 1, ts: Date.now() }])
    localStorage.setItem('recent_visits_v1:1', beforeA)
    const beforeB = JSON.stringify([{ path: '/threads/b', title: '账号 B', id: 2, ts: Date.now() }])
    let switched = false
    const originalGetItem = Storage.prototype.getItem
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (this: Storage, key: string): string | null {
      const value = originalGetItem.call(this, key)
      if (key === 'recent_visits_v1:1' && !switched) {
        switched = true
        setCurrentUser(2, 'account-b')
        localStorage.setItem('recent_visits_v1:2', beforeB)
      }
      return value
    })

    Reflect.apply(addVisit, null, [{
      path: '/threads/should-not-write', title: '不应写入', id: 9, scope,
    }])

    expect(localStorage.getItem('recent_visits_v1:1')).toBe(beforeA)
    expect(localStorage.getItem('recent_visits_v1:2')).toBe(beforeB)
  })
})

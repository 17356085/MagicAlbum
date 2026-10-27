import { beforeEach, describe, expect, it, vi } from 'vitest'
import { persistAuthState } from '@/utils/authStorage'
import {
  addVisit,
  clearAllRecentVisits,
  getAllRecentVisits,
  getRecentVisits,
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
})

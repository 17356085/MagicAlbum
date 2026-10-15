import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addVisit,
  clearAllRecentVisits,
  getRecentVisits,
} from '../useRecentVisits'

function setCurrentUser(id: number | null): void {
  if (id == null) {
    localStorage.removeItem('currentUser')
  } else {
    localStorage.setItem('currentUser', JSON.stringify({ id, username: `user-${id}` }))
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
})

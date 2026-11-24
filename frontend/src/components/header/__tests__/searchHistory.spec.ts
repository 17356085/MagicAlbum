import { beforeEach, describe, expect, it } from 'vitest'
import { clearSearchHistory, readSearchHistory, recordSearchHistory } from '../searchHistory'

describe('search history', () => {
  beforeEach(() => localStorage.clear())

  it('keeps guest and account histories separate and clears only the selected owner', () => {
    recordSearchHistory('guest', 'threads', '公开帖子')
    recordSearchHistory('account:1', 'users', 'alice')
    recordSearchHistory('account:2', 'users', 'bob')
    clearSearchHistory('account:1')

    expect(readSearchHistory('account:1')).toEqual([])
    expect(readSearchHistory('guest')).toEqual([{ type: 'threads', query: '公开帖子' }])
    expect(readSearchHistory('account:2')).toEqual([{ type: 'users', query: 'bob' }])
    expect(recordSearchHistory(null, 'threads', 'unresolved account')).toEqual([])
  })

  it('deduplicates a query within its own search type and keeps the newest ten', () => {
    for (let index = 0; index < 12; index++) recordSearchHistory('guest', 'threads', `go-${index}`)
    recordSearchHistory('guest', 'users', 'go-11')
    const entries = recordSearchHistory('guest', 'threads', 'Go-11')

    expect(entries).toHaveLength(10)
    expect(entries.slice(0, 2)).toEqual([
      { type: 'threads', query: 'Go-11' },
      { type: 'users', query: 'go-11' },
    ])
    expect(entries).not.toContainEqual({ type: 'threads', query: 'go-11' })
  })
})

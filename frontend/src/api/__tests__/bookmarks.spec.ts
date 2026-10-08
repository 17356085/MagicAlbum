import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import { bookmarkThread, getThreadBookmarkState, listBookmarks, unbookmarkThread } from '../bookmarks'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('bookmarks API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the bookmark toggle endpoints and DTO', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { bookmarked: false } })
    vi.mocked(api.post).mockResolvedValueOnce({ data: { bookmarked: true } })
    vi.mocked(api.delete).mockResolvedValueOnce({ data: { bookmarked: false } })

    await expect(getThreadBookmarkState(7)).resolves.toEqual({ bookmarked: false })
    await expect(bookmarkThread(7)).resolves.toEqual({ bookmarked: true })
    await expect(unbookmarkThread(7)).resolves.toEqual({ bookmarked: false })

    expect(api.get).toHaveBeenCalledWith('/threads/7/bookmark')
    expect(api.post).toHaveBeenCalledWith('/threads/7/bookmark')
    expect(api.delete).toHaveBeenCalledWith('/threads/7/bookmark')
  })

  it('requests the authenticated bookmark page with the contract pagination params', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        items: [{ threadId: 7, title: '收藏的帖子', sectionName: '编程', bookmarkedAt: '2026-10-07T00:00:00Z' }],
        page: 2,
        size: 10,
        total: 11,
      },
    })

    await expect(listBookmarks({ page: 2, size: 10 })).resolves.toEqual({
      items: [{ threadId: 7, title: '收藏的帖子', sectionName: '编程', bookmarkedAt: '2026-10-07T00:00:00Z' }],
      page: 2,
      size: 10,
      total: 11,
    })
    expect(api.get).toHaveBeenCalledWith('/users/me/bookmarks', { params: { page: 2, size: 10 } })
  })
})

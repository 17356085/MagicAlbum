import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listFollowingFeed } from '../followingFeed'
import api from '../client'

vi.mock('../client', () => ({ default: { get: vi.fn() } }))

describe('listFollowingFeed', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses the authenticated server feed and its real page metadata', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        items: [{ id: 51, title: 'Followed post', authorAvatar: '/avatar.png' }],
        page: 6,
        size: 10,
        total: 51,
      },
    })

    const result = await listFollowingFeed({ page: 6, size: 10 })

    expect(api.get).toHaveBeenCalledWith('/threads/following', { params: { page: 6, size: 10 } })
    expect(result.total).toBe(51)
    expect(result.page).toBe(6)
    expect(result.items[0].authorAvatarUrl).toBe('/avatar.png')
  })

  it('opts into cursor paging and preserves opaque adjacent tokens', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        items: [{ id: 52, title: 'Cursor post' }],
        page: 2,
        size: 10,
        total: 51,
        nextCursor: 'opaque-next',
        previousCursor: 'opaque-previous',
      },
    })

    const result = await listFollowingFeed({ page: 2, size: 10, pagination: 'cursor', after: 'opaque-before' })

    expect(api.get).toHaveBeenCalledWith('/threads/following', {
      params: { page: 2, size: 10, pagination: 'cursor', after: 'opaque-before' },
    })
    expect(result).toMatchObject({ nextCursor: 'opaque-next', previousCursor: 'opaque-previous' })
  })

  it('keeps explicit null cursors as boundaries', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        items: [],
        page: 1,
        size: 10,
        total: 0,
        nextCursor: null,
        previousCursor: null,
      },
    })

    const result = await listFollowingFeed({ pagination: 'cursor' })

    expect(result).toMatchObject({ nextCursor: null, previousCursor: null })
  })

  it('uses legacy metadata when an older server ignores cursor parameters', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { items: [{ id: 53, title: 'Legacy post' }], page: 3, size: 10, total: 51 },
    })

    const result = await listFollowingFeed({ page: 3, size: 10, pagination: 'cursor', before: 'opaque-previous' })

    expect(result).toEqual(expect.objectContaining({ page: 3, size: 10, total: 51 }))
    expect('nextCursor' in result).toBe(false)
    expect('previousCursor' in result).toBe(false)
  })

  it('rejects mutually exclusive cursor directions before making a request', async () => {
    await expect(listFollowingFeed({ pagination: 'cursor', after: 'a', before: 'b' })).rejects.toThrow('mutually exclusive')
    expect(api.get).not.toHaveBeenCalled()
  })
})

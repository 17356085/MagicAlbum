import { describe, expect, it, vi } from 'vitest'
import { listFollowingFeed } from '../followingFeed'
import api from '../client'

vi.mock('../client', () => ({ default: { get: vi.fn() } }))

describe('listFollowingFeed', () => {
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
})

import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ThreadDetailActions from '../../pages/thread-detail/ThreadDetailActions.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  getThreadBookmarkState: vi.fn(),
  bookmarkThread: vi.fn(),
  unbookmarkThread: vi.fn(),
  getThreadLikeState: vi.fn(),
  likeThread: vi.fn(),
  unlikeThread: vi.fn(),
}))

vi.mock('@/api/bookmarks', () => ({
  getThreadBookmarkState: mocks.getThreadBookmarkState,
  bookmarkThread: mocks.bookmarkThread,
  unbookmarkThread: mocks.unbookmarkThread,
}))

vi.mock('@/api/threads', () => ({
  getThreadLikeState: mocks.getThreadLikeState,
  likeThread: mocks.likeThread,
  unlikeThread: mocks.unlikeThread,
}))

function createWrapper() {
  return mount(ThreadDetailActions, {
    props: {
      thread: { id: 7, title: '可收藏的帖子', liked: false, likeCount: 0 },
    },
  })
}

describe('ThreadDetailActions bookmark toggle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    mocks.getThreadLikeState.mockResolvedValue({ liked: false, likeCount: 0 })
    mocks.getThreadBookmarkState.mockResolvedValue({ bookmarked: false })
  })

  it('locks duplicate bookmark toggles until the request resolves', async () => {
    let resolveBookmark!: (value: { bookmarked: boolean }) => void
    mocks.bookmarkThread.mockReturnValueOnce(new Promise((resolve) => { resolveBookmark = resolve }))
    const wrapper = createWrapper()
    await flushPromises()

    const bookmarkButton = wrapper.get('button[aria-label="收藏"]')
    await bookmarkButton.trigger('click')
    await bookmarkButton.trigger('click')

    expect(mocks.bookmarkThread).toHaveBeenCalledTimes(1)
    expect(bookmarkButton.attributes('disabled')).toBeDefined()
    expect(bookmarkButton.attributes('aria-busy')).toBe('true')

    resolveBookmark({ bookmarked: true })
    await flushPromises()

    expect(wrapper.get('button[aria-label="取消收藏"]').text()).toContain('已收藏')
    expect(bookmarkButton.attributes('disabled')).toBeUndefined()
  })

  it('prompts unauthenticated users without mutating the bookmark', async () => {
    useAuthStore().clearAuthState()
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('button[aria-label="收藏"]').trigger('click')

    expect(mocks.bookmarkThread).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('登录后可以收藏')
  })

  it('reads the bookmark state again for a logged-in detail view', async () => {
    mocks.getThreadBookmarkState.mockResolvedValueOnce({ bookmarked: true })
    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.getThreadBookmarkState).toHaveBeenCalledWith(7)
    expect(wrapper.get('button[aria-label="取消收藏"]').text()).toContain('已收藏')
    expect(mocks.getThreadLikeState).toHaveBeenCalledWith(7)
  })
})

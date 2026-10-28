import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

vi.mock('@/components/ReportDialog.vue', () => ({
  default: {
    name: 'ReportDialogStub',
    props: ['modelValue', 'targetType', 'targetId', 'isLoggedIn'],
    emits: ['update:modelValue'],
    template: '<div data-testid="report-dialog">report {{ targetId }}</div>',
  },
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function thread(id = 7, patch: Record<string, unknown> = {}) {
  return { id, title: `帖子 ${id}`, liked: false, likeCount: 0, ...patch }
}

function createWrapper(value = thread()): VueWrapper {
  return mount(ThreadDetailActions, { props: { thread: value } })
}

let wrapper: VueWrapper | undefined

describe('ThreadDetailActions request ownership and retry handling', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    mocks.getThreadLikeState.mockResolvedValue({ liked: false, likeCount: 0 })
    mocks.getThreadBookmarkState.mockResolvedValue({ bookmarked: false })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    localStorage.clear()
    sessionStorage.clear()
  })

  it('locks duplicate bookmark toggles until the request resolves', async () => {
    const response = deferred<{ bookmarked: boolean }>()
    mocks.bookmarkThread.mockReturnValueOnce(response.promise)
    wrapper = createWrapper()
    await flushPromises()

    const bookmarkButton = wrapper.get('button[aria-label="收藏"]')
    await bookmarkButton.trigger('click')
    await bookmarkButton.trigger('click')

    expect(mocks.bookmarkThread).toHaveBeenCalledTimes(1)
    expect(bookmarkButton.attributes('disabled')).toBeDefined()
    expect(bookmarkButton.attributes('aria-busy')).toBe('true')

    response.resolve({ bookmarked: true })
    await flushPromises()

    expect(wrapper.get('button[aria-label="取消收藏"]').text()).toContain('已收藏')
    expect(bookmarkButton.attributes('disabled')).toBeUndefined()
  })

  it('prompts unauthenticated users without mutating personalized state', async () => {
    useAuthStore().clearAuthState()
    wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('button[aria-label="收藏"]').trigger('click')
    await wrapper.get('button[aria-label="举报"]').trigger('click')

    expect(mocks.bookmarkThread).not.toHaveBeenCalled()
    expect(mocks.getThreadBookmarkState).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('登录后可以收藏')
    expect(wrapper.text()).toContain('登录后可以举报')
    expect(wrapper.find('[data-testid="report-dialog"]').exists()).toBe(false)
  })

  it('reads personalized state again for a logged-in detail view', async () => {
    mocks.getThreadBookmarkState.mockResolvedValueOnce({ bookmarked: true })
    wrapper = createWrapper()
    await flushPromises()

    expect(mocks.getThreadBookmarkState).toHaveBeenCalledWith(7)
    expect(wrapper.get('button[aria-label="取消收藏"]').text()).toContain('已收藏')
    expect(mocks.getThreadLikeState).toHaveBeenCalledWith(7)
  })

  it('exposes the unlike action for a liked thread and updates its state after the write', async () => {
    mocks.getThreadLikeState.mockResolvedValueOnce({ liked: true, likeCount: 1 })
    mocks.unlikeThread.mockResolvedValueOnce({ liked: false, likeCount: 0 })
    wrapper = createWrapper()
    await flushPromises()

    const unlikeButton = wrapper.get('button[aria-label="取消点赞"]')
    expect(unlikeButton.attributes('aria-pressed')).toBe('true')
    expect(unlikeButton.text()).toContain('已点赞')
    await unlikeButton.trigger('click')
    await flushPromises()

    expect(mocks.unlikeThread).toHaveBeenCalledWith(7)
    expect(wrapper.get('button[aria-label="点赞"]').attributes('aria-pressed')).toBe('false')
  })

  it('does not let an old like read patch a newly displayed thread', async () => {
    const oldRead = deferred<{ liked: boolean; likeCount: number }>()
    mocks.getThreadLikeState.mockReturnValueOnce(oldRead.promise)
    wrapper = createWrapper(thread(7))
    await flushPromises()

    await wrapper.setProps({ thread: thread(8) })
    await flushPromises()
    const emittedBeforeOldReply = wrapper.emitted('updated')?.length || 0

    oldRead.resolve({ liked: true, likeCount: 99 })
    await flushPromises()

    expect(wrapper.emitted('updated')?.length || 0).toBe(emittedBeforeOldReply)
    expect(wrapper.find('button[aria-label="收藏"]').exists()).toBe(true)
  })

  it('does not let an old like write patch another account', async () => {
    const write = deferred<{ liked: boolean; likeCount: number }>()
    mocks.likeThread.mockReturnValueOnce(write.promise)
    wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    useAuthStore().applyAuthState({ accessToken: 'account-b' })
    write.resolve({ liked: true, likeCount: 1 })
    await flushPromises()

    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(wrapper.text()).not.toContain('操作失败，请稍后再试')
    expect(wrapper.get('button[aria-label="点赞"]').attributes('disabled')).toBeUndefined()
  })

  it('ignores a late like write after the component unmounts', async () => {
    const write = deferred<{ liked: boolean; likeCount: number }>()
    mocks.likeThread.mockReturnValueOnce(write.promise)
    wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    wrapper.unmount()
    write.resolve({ liked: true, likeCount: 1 })
    await flushPromises()

    expect(wrapper.emitted('updated')).toBeUndefined()
  })

  it('does not write before the like GET establishes the current state', async () => {
    const read = deferred<{ liked: boolean; likeCount: number }>()
    mocks.getThreadLikeState.mockReturnValueOnce(read.promise)
    wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    expect(mocks.likeThread).not.toHaveBeenCalled()

    read.resolve({ liked: false, likeCount: 4 })
    await flushPromises()
    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    expect(mocks.likeThread).toHaveBeenCalledWith(7)
  })

  it('survives an account A to B to A transition without accepting the old read', async () => {
    const oldRead = deferred<{ liked: boolean; likeCount: number }>()
    mocks.getThreadLikeState
      .mockReturnValueOnce(oldRead.promise)
      .mockResolvedValue({ liked: false, likeCount: 0 })
    wrapper = createWrapper()
    await flushPromises()

    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-b' })
    auth.applyAuthState({ accessToken: 'real-token' })
    oldRead.resolve({ liked: true, likeCount: 77 })
    await flushPromises()

    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(mocks.getThreadLikeState.mock.calls.length).toBeGreaterThan(1)
  })

  it('accepts same-token identity hydration without issuing another GET', async () => {
    const read = deferred<{ liked: boolean; likeCount: number }>()
    mocks.getThreadLikeState.mockReturnValueOnce(read.promise)
    wrapper = createWrapper()
    await flushPromises()
    const callsBeforeHydration = mocks.getThreadLikeState.mock.calls.length

    expect(useAuthStore().hydrateCurrentUser({ id: 1, username: 'alice' }, 'real-token')).toBe(true)
    read.resolve({ liked: false, likeCount: 0 })
    await flushPromises()

    expect(mocks.getThreadLikeState).toHaveBeenCalledTimes(callsBeforeHydration)
  })

  it('keeps personalized UI while a login attempt only advances auth generation', async () => {
    const read = deferred<{ liked: boolean; likeCount: number }>()
    mocks.getThreadLikeState.mockReturnValueOnce(read.promise)
    wrapper = createWrapper()
    await flushPromises()
    const auth = useAuthStore()
    auth.invalidateAuthOperations()

    read.resolve({ liked: false, likeCount: 2 })
    await flushPromises()

    expect(wrapper.get('button[aria-label="点赞"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('登录状态已变化')
  })

  it('rejects a storage-only drift before a like click', async () => {
    wrapper = createWrapper()
    await flushPromises()
    localStorage.setItem('accessToken', 'account-b')

    await wrapper.get('button[aria-label="点赞"]').trigger('click')

    expect(mocks.likeThread).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('登录状态已变化')
  })

  it('rejects a storage-only drift after a like reply', async () => {
    const write = deferred<{ liked: boolean; likeCount: number }>()
    mocks.likeThread.mockReturnValueOnce(write.promise)
    wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    localStorage.setItem('accessToken', 'account-b')
    write.resolve({ liked: true, likeCount: 1 })
    await flushPromises()

    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(wrapper.text()).toContain('登录状态已变化')
  })

  it('offers a retry and never treats a failed like read as unliked', async () => {
    const retry = deferred<{ liked: boolean; likeCount: number }>()
    mocks.getThreadLikeState.mockRejectedValueOnce(new Error('read failed')).mockReturnValueOnce(retry.promise)
    wrapper = createWrapper()
    await flushPromises()

    const likeButton = wrapper.get('button[aria-label="点赞"]')
    expect(likeButton.attributes('disabled')).toBeDefined()
    const retryButton = wrapper.get('[data-testid="like-state-retry"]')
    await retryButton.trigger('click')
    await retryButton.trigger('click')
    expect(mocks.getThreadLikeState).toHaveBeenCalledTimes(2)
    expect(mocks.likeThread).not.toHaveBeenCalled()

    retry.resolve({ liked: true, likeCount: 3 })
    await flushPromises()
    expect(wrapper.find('button[aria-label="取消点赞"]').exists()).toBe(true)
  })

  it('keeps a malformed like read unknown and retries before any opposite write', async () => {
    mocks.getThreadLikeState
      .mockResolvedValueOnce({ likeCount: 3 })
      .mockResolvedValueOnce({ liked: true, likeCount: 3 })
    wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('button[aria-label="点赞"]').attributes('disabled')).toBeDefined()
    expect(mocks.likeThread).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="like-state-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.getThreadLikeState).toHaveBeenCalledTimes(2)
    expect(mocks.likeThread).not.toHaveBeenCalled()
    expect(wrapper.find('button[aria-label="取消点赞"]').exists()).toBe(true)
  })

  it('keeps a malformed like write response unknown until a state retry', async () => {
    mocks.likeThread.mockResolvedValueOnce({ likeCount: 1 })
    mocks.getThreadLikeState.mockResolvedValueOnce({ liked: false, likeCount: 0 }).mockResolvedValueOnce({ liked: true, likeCount: 1 })
    wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('updated')).toBeUndefined()
    expect(wrapper.find('button[aria-label="点赞"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="like-state-retry"]')).toBeTruthy()

    await wrapper.get('[data-testid="like-state-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.getThreadLikeState).toHaveBeenCalledTimes(2)
    expect(wrapper.find('button[aria-label="取消点赞"]').exists()).toBe(true)
  })

  it('keeps a new bookmark read busy when an old read finally settles', async () => {
    const oldRead = deferred<{ bookmarked: boolean }>()
    const newRead = deferred<{ bookmarked: boolean }>()
    mocks.getThreadBookmarkState.mockReturnValueOnce(oldRead.promise).mockReturnValueOnce(newRead.promise)
    wrapper = createWrapper(thread(7))
    await flushPromises()

    await wrapper.setProps({ thread: thread(8) })
    await flushPromises()
    oldRead.resolve({ bookmarked: true })
    await flushPromises()

    expect(wrapper.get('button[aria-label="收藏"]').attributes('disabled')).toBeDefined()
    newRead.resolve({ bookmarked: false })
    await flushPromises()
    expect(wrapper.get('button[aria-label="收藏"]').attributes('disabled')).toBeUndefined()
  })

  it('offers a bookmark retry without choosing an opposite write', async () => {
    const retry = deferred<{ bookmarked: boolean }>()
    mocks.getThreadBookmarkState.mockRejectedValueOnce(new Error('read failed')).mockReturnValueOnce(retry.promise)
    wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('button[aria-label="收藏"]').attributes('disabled')).toBeDefined()
    const retryButton = wrapper.get('[data-testid="bookmark-state-retry"]')
    await retryButton.trigger('click')
    await retryButton.trigger('click')
    expect(mocks.getThreadBookmarkState).toHaveBeenCalledTimes(2)
    expect(mocks.bookmarkThread).not.toHaveBeenCalled()
    retry.resolve({ bookmarked: true })
    await flushPromises()
    expect(wrapper.find('button[aria-label="取消收藏"]').exists()).toBe(true)
  })

  it('keeps a malformed bookmark read unknown and retries before any opposite write', async () => {
    mocks.getThreadBookmarkState
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce({ bookmarked: true })
    wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('button[aria-label="收藏"]').attributes('disabled')).toBeDefined()
    expect(mocks.bookmarkThread).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="bookmark-state-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.getThreadBookmarkState).toHaveBeenCalledTimes(2)
    expect(mocks.bookmarkThread).not.toHaveBeenCalled()
    expect(wrapper.find('button[aria-label="取消收藏"]').exists()).toBe(true)
  })

  it('closes an open report when the displayed thread changes', async () => {
    wrapper = createWrapper(thread(7))
    await flushPromises()
    await wrapper.get('button[aria-label="举报"]').trigger('click')
    expect(wrapper.find('[data-testid="report-dialog"]').text()).toContain('7')

    await wrapper.setProps({ thread: thread(8) })
    expect(wrapper.find('[data-testid="report-dialog"]').exists()).toBe(false)
  })

  it('closes an open report when the authenticated account changes', async () => {
    wrapper = createWrapper(thread(7))
    await flushPromises()
    await wrapper.get('button[aria-label="举报"]').trigger('click')
    expect(wrapper.find('[data-testid="report-dialog"]').exists()).toBe(true)

    useAuthStore().applyAuthState({ accessToken: 'account-b' })
    await flushPromises()
    expect(wrapper.find('[data-testid="report-dialog"]').exists()).toBe(false)
  })

  it('keeps an accepted like state when same-thread props arrive with an older value', async () => {
    mocks.likeThread.mockResolvedValueOnce({ liked: true, likeCount: 5 })
    wrapper = createWrapper(thread(7))
    await flushPromises()
    await wrapper.get('button[aria-label="点赞"]').trigger('click')
    await flushPromises()

    await wrapper.setProps({ thread: thread(7, { liked: false, likeCount: 0 }) })
    expect(wrapper.find('button[aria-label="取消点赞"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('5')
  })
})

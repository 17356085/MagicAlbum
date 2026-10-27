import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FollowingFeed from '../FollowingFeed.vue'
import FollowingFeedPanel from '@/components/sidebar/FollowingFeedPanel.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  getCurrentUser: vi.fn(),
  listUserFollowing: vi.fn(),
  listUserThreads: vi.fn(),
  listFollowingFeed: vi.fn(),
}))

vi.mock('@/api/users', () => ({
  getCurrentUser: mocks.getCurrentUser,
  listUserFollowing: mocks.listUserFollowing,
  listUserThreads: mocks.listUserThreads,
}))
vi.mock('@/api/followingFeed', () => ({
  listFollowingFeed: mocks.listFollowingFeed,
}))
vi.mock('@/utils/image', () => ({
  normalizeImageUrl: (value: string | undefined) => value || '',
}))
vi.mock('@/composables/time', () => ({
  formatRelativeTime: () => '',
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function followingPage(items = [{ id: 2, username: 'alice', nickname: 'Alice' }]) {
  return { items, page: 1, size: 12, total: items.length }
}

function feedPage(title: string, page = 1, total = 1) {
  return {
    items: [{ id: page, title, content: `${title} 内容`, authorId: 2, authorUsername: 'alice' }],
    page,
    size: 10,
    total,
  }
}

function panelFeedPage(title: string) {
  return { items: [{ id: 90, title, authorUsername: 'alice' }], page: 1, size: 4, total: 1 }
}

function mountPage(pinia = createPinia()): VueWrapper {
  return mount(FollowingFeed, {
    global: {
      plugins: [pinia],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
}

describe('FollowingFeed session ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mocks.getCurrentUser.mockResolvedValue(null)
    mocks.listUserFollowing.mockResolvedValue(followingPage())
    mocks.listUserThreads.mockResolvedValue(feedPage('用户帖子'))
    mocks.listFollowingFeed.mockResolvedValue(panelFeedPage('全部动态'))
  })

  it('confirms a token-only owner through refreshCurrentUser before loading the feed', async () => {
    mocks.getCurrentUser.mockResolvedValue({ id: 7, username: 'seven' })
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'token-seven', user: null })
    const wrapper = mountPage(pinia)

    try {
      await flushPromises()
      await flushPromises()

      expect(mocks.getCurrentUser).toHaveBeenCalledTimes(1)
      expect(mocks.getCurrentUser).toHaveBeenCalledWith('token-seven')
      expect(auth.user).toMatchObject({ id: 7, username: 'seven' })
      expect(JSON.parse(localStorage.getItem('currentUserSession.v1') || '{}')).toMatchObject({
        accessToken: 'token-seven',
        user: { id: 7, username: 'seven' },
      })
      expect(mocks.listUserFollowing).toHaveBeenCalledWith(7, { page: 1, size: 12 })
      expect(mocks.listFollowingFeed).toHaveBeenCalledWith({ page: 1, size: 10 })
    } finally {
      wrapper.unmount()
    }
  })

  it('does not use the identity fallback when the cached owner is known', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'known-token', user: { id: 4, username: 'known' } })
    const wrapper = mountPage()

    try {
      await flushPromises()

      expect(mocks.getCurrentUser).not.toHaveBeenCalled()
      expect(mocks.listUserFollowing).toHaveBeenCalledWith(4, { page: 1, size: 12 })
      expect(mocks.listFollowingFeed).toHaveBeenCalledWith({ page: 1, size: 10 })
    } finally {
      wrapper.unmount()
    }
  })

  it('coalesces concurrent page and sidebar identity fallback requests', async () => {
    const current = deferred<{ id: number; username: string }>()
    mocks.getCurrentUser.mockReturnValue(current.promise)
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'shared-token', user: null })
    const page = mountPage(pinia)
    const sidebar = mount(FollowingFeedPanel, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: RouterLinkStub },
      },
    })

    try {
      await flushPromises()
      expect(mocks.getCurrentUser).toHaveBeenCalledTimes(1)
      expect(mocks.getCurrentUser).toHaveBeenCalledWith('shared-token')

      current.resolve({ id: 8, username: 'shared-owner' })
      await flushPromises()
      await flushPromises()

      expect(auth.user).toMatchObject({ id: 8, username: 'shared-owner' })
      expect(mocks.listUserFollowing).toHaveBeenCalledTimes(1)
      expect(mocks.listFollowingFeed).toHaveBeenCalledTimes(2)
    } finally {
      page.unmount()
      sidebar.unmount()
    }
  })

  it('suppresses an old identity fallback after unmount and token replacement', async () => {
    const current = deferred<{ id: number; username: string }>()
    mocks.getCurrentUser.mockReturnValue(current.promise)
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const wrapper = mountPage(pinia)
    await flushPromises()
    expect(mocks.getCurrentUser).toHaveBeenCalledWith('token-a')

    wrapper.unmount()
    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'owner-b' } })
    current.resolve({ id: 1, username: 'owner-a' })
    await flushPromises()

    expect(auth.user).toMatchObject({ id: 2, username: 'owner-b' })
    expect(mocks.listUserFollowing).not.toHaveBeenCalled()
    expect(mocks.listFollowingFeed).not.toHaveBeenCalled()
  })

  it('does not continue an old list-following response after the account changes', async () => {
    const following = deferred<ReturnType<typeof followingPage>>()
    mocks.listUserFollowing.mockReturnValueOnce(following.promise)
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'owner-a' } })
    const wrapper = mountPage(pinia)
    await flushPromises()
    expect(mocks.listUserFollowing).toHaveBeenCalledWith(1, { page: 1, size: 12 })

    wrapper.unmount()
    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'owner-b' } })
    following.resolve(followingPage([{ id: 9, username: 'old-owner', nickname: 'Old owner' }]))
    await flushPromises()

    expect(mocks.listFollowingFeed).not.toHaveBeenCalled()
  })

  it('invalidates a same-id cached session when its token changes', async () => {
    const oldFeed = deferred<ReturnType<typeof panelFeedPage>>()
    mocks.listFollowingFeed.mockReturnValueOnce(oldFeed.promise).mockResolvedValueOnce(panelFeedPage('新账号动态'))
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'owner-a' } })
    const wrapper = mountPage(pinia)

    try {
      await flushPromises()
      expect(mocks.listFollowingFeed).toHaveBeenCalledTimes(1)

      auth.applyAuthState({ accessToken: 'token-b', user: { id: 1, username: 'owner-a' } })
      await flushPromises()
      await flushPromises()
      expect(mocks.listFollowingFeed).toHaveBeenCalledTimes(2)

      oldFeed.resolve(panelFeedPage('旧账号动态'))
      await flushPromises()
      expect(wrapper.text()).not.toContain('旧账号动态')
      expect(wrapper.text()).toContain('新账号动态')
    } finally {
      wrapper.unmount()
    }
  })

  it('keeps the current loading state when an older request fails', async () => {
    const oldFeed = deferred<ReturnType<typeof panelFeedPage>>()
    const newFeed = deferred<ReturnType<typeof panelFeedPage>>()
    mocks.listFollowingFeed.mockReturnValueOnce(oldFeed.promise).mockReturnValueOnce(newFeed.promise)
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'owner-a' } })
    const wrapper = mountPage(pinia)

    try {
      await flushPromises()
      auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'owner-b' } })
      await flushPromises()
      await flushPromises()
      expect(wrapper.text()).toContain('正在加载...')

      oldFeed.reject(new Error('stale failure'))
      await flushPromises()
      expect(wrapper.text()).toContain('正在加载...')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)

      newFeed.resolve(panelFeedPage('当前账号动态'))
      await flushPromises()
      expect(wrapper.text()).toContain('当前账号动态')
      expect(wrapper.text()).not.toContain('正在加载...')
    } finally {
      wrapper.unmount()
    }
  })

  it('preserves user selection and pagination requests', async () => {
    mocks.listFollowingFeed
      .mockReset()
      .mockResolvedValueOnce(feedPage('第一页', 1, 21))
      .mockResolvedValueOnce(feedPage('第二页', 2, 21))
    mocks.listUserThreads.mockResolvedValueOnce({
      items: [{ id: 30, title: 'Alice 的帖子', content: '内容' }],
      page: 1,
      size: 10,
      total: 1,
    })
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'known-token', user: { id: 4, username: 'known' } })
    const wrapper = mountPage()

    try {
      await flushPromises()
      await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
      await flushPromises()
      expect(mocks.listFollowingFeed).toHaveBeenLastCalledWith({ page: 2, size: 10 })
      expect(wrapper.text()).toContain('第二页')

      await wrapper.findAll('button').find((button) => button.text().includes('Alice'))!.trigger('click')
      await flushPromises()
      expect(mocks.listUserThreads).toHaveBeenCalledWith(2, { page: 1, size: 10 })
      expect(wrapper.text()).toContain('Alice 的帖子')
    } finally {
      wrapper.unmount()
    }
  })

  it.each([['page', FollowingFeed], ['sidebar', FollowingFeedPanel]] as const)('shows a retryable identity error in the %s instead of claiming the feed is empty', async (_surface, component) => {
    const pinia = createPinia()
    useAuthStore(pinia).applyAuthState({ accessToken: 'retry-owner', user: null })
    mocks.getCurrentUser.mockRejectedValueOnce(new Error('temporary identity failure'))
      .mockResolvedValueOnce({ id: 7, username: 'seven' })
    const wrapper = mount(component, { global: { plugins: [pinia], stubs: { RouterLink: RouterLinkStub } } })
    try {
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain('账号信息加载失败')
      expect(wrapper.text()).not.toContain('暂无关注动态')
      expect(wrapper.text()).not.toContain('暂无动态')
      await wrapper.get('[role="alert"] button').trigger('click')
      await flushPromises()
      await flushPromises()
      expect(mocks.getCurrentUser).toHaveBeenCalledTimes(2)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('全部动态')
    } finally { wrapper.unmount() }
  })
})

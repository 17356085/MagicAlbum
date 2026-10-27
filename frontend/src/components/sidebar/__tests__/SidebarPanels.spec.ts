import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import PopularTagsPanel from '../PopularTagsPanel.vue'
import FollowingFeedPanel from '../FollowingFeedPanel.vue'
import RecentVisitsPanel from '../RecentVisitsPanel.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  listPopularTags: vi.fn(),
  listTags: vi.fn(),
  listFollowingFeed: vi.fn(),
  getCurrentUser: vi.fn(),
  getRecentVisits: vi.fn(),
}))

vi.mock('@/api/tags', () => ({
  listPopularTags: mocks.listPopularTags,
  listTags: mocks.listTags,
}))
vi.mock('@/api/followingFeed', () => ({
  listFollowingFeed: mocks.listFollowingFeed,
}))
vi.mock('@/api/users', () => ({
  getCurrentUser: mocks.getCurrentUser,
}))
vi.mock('@/composables/useRecentVisits', async () => ({
  ...(await vi.importActual<typeof import('@/composables/useRecentVisits')>('@/composables/useRecentVisits')),
  getRecentVisits: mocks.getRecentVisits,
}))
vi.mock('@/composables/time', () => ({
  formatRelativeTime: () => '',
}))
vi.mock('@/utils/image', () => ({
  normalizeImageUrl: (url: string | undefined) => url || '',
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

async function createRouterForPanel() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/feed', name: 'feed', component: { template: '<div />' } },
      { path: '/history', name: 'history', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover')
  await router.isReady()
  return router
}

describe('sidebar data panels', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    mocks.listPopularTags.mockResolvedValue([{ id: 1, name: 'Vue', threadCount: 3 }])
    mocks.listTags.mockResolvedValue({ items: [], page: 1, size: 18, total: 0 })
    mocks.listFollowingFeed.mockResolvedValue({ items: [{ id: 9, title: '动态', authorUsername: 'reviewer' }], page: 1, size: 4, total: 1 })
    mocks.getCurrentUser.mockResolvedValue(null)
    mocks.getRecentVisits.mockReturnValue([{ path: '/discover', title: '发现', ts: Date.now() }])
  })

  it('loads popular tags and removes refresh listeners on unmount', async () => {
    const router = await createRouterForPanel()
    const wrapper = mount(PopularTagsPanel, {
      props: { sections: [] },
      global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('#Vue')
    const calls = mocks.listPopularTags.mock.calls.length
    wrapper.unmount()
    window.dispatchEvent(new Event('thread-tags-updated'))
    await flushPromises()
    expect(mocks.listPopularTags).toHaveBeenCalledTimes(calls)
  })

  it('loads followed activity and stops responding after unmount', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().applyAuthState({ accessToken: 'real-token', user: { id: 1, username: 'reviewer' } })
    const router = await createRouterForPanel()
    const wrapper = mount(FollowingFeedPanel, {
      global: { plugins: [pinia, router], stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('动态')
    const calls = mocks.listFollowingFeed.mock.calls.length
    wrapper.unmount()
    window.dispatchEvent(new Event('follow-state-updated'))
    await flushPromises()
    expect(mocks.listFollowingFeed).toHaveBeenCalledTimes(calls)
  })

  it('does not hydrate the current user from a stale response after an account switch', async () => {
    let resolveCurrentUser: ((value: unknown) => void) | undefined
    mocks.getCurrentUser.mockImplementationOnce(() => new Promise((resolve) => {
      resolveCurrentUser = resolve
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'first-token', user: null })
    const router = await createRouterForPanel()
    const wrapper = mount(FollowingFeedPanel, {
      global: { plugins: [pinia, router], stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()
    expect(resolveCurrentUser).toBeDefined()

    auth.applyAuthState({ accessToken: 'second-token', user: { id: 2, username: 'second-user' } })
    resolveCurrentUser?.({ id: 1, username: 'stale-user' })
    await flushPromises()

    expect(auth.user).toEqual({ id: 2, username: 'second-user' })
    wrapper.unmount()
  })

  it('does not render an older feed response after logout', async () => {
    let resolveFeed: ((value: unknown) => void) | undefined
    mocks.listFollowingFeed.mockImplementationOnce(() => new Promise((resolve) => {
      resolveFeed = resolve
    }))
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'real-token', user: { id: 1, username: 'reviewer' } })
    const router = await createRouterForPanel()
    const wrapper = mount(FollowingFeedPanel, {
      global: { plugins: [pinia, router], stubs: { RouterLink: RouterLinkStub } },
    })
    await flushPromises()
    expect(resolveFeed).toBeDefined()

    auth.clearAuthState()
    resolveFeed?.({ items: [{ id: 99, title: '旧账号动态', authorUsername: 'old-user' }], page: 1, size: 4, total: 1 })
    await flushPromises()

    expect(wrapper.text()).not.toContain('旧账号动态')
    wrapper.unmount()
  })

  it('refreshes recent visits and removes the window listener on unmount', async () => {
    const router = await createRouterForPanel()
    const wrapper = mount(RecentVisitsPanel, {
      global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } },
    })
    expect(wrapper.text()).toContain('发现')
    const calls = mocks.getRecentVisits.mock.calls.length
    wrapper.unmount()
    window.dispatchEvent(new Event('recent-visits-updated'))
    expect(mocks.getRecentVisits).toHaveBeenCalledTimes(calls)
  })

  it('reloads recent visits for the active account after an auth change', async () => {
    mocks.getRecentVisits.mockImplementation(() => {
      const raw = localStorage.getItem('currentUser')
      const user = raw ? JSON.parse(raw) as { id?: number } : null
      const title = user?.id === 1 ? '账号 A 浏览' : user?.id === 2 ? '账号 B 浏览' : '访客浏览'
      return [{ path: '/discover', title, ts: Date.now() }]
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
    const router = await createRouterForPanel()
    const wrapper = mount(RecentVisitsPanel, {
      global: { plugins: [pinia, router], stubs: { RouterLink: RouterLinkStub } },
    })

    expect(wrapper.text()).toContain('账号 A 浏览')

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'account-b' } })
    await flushPromises()
    expect(wrapper.text()).toContain('账号 B 浏览')
    expect(wrapper.text()).not.toContain('账号 A 浏览')

    auth.clearAuthState()
    await flushPromises()
    expect(wrapper.text()).toContain('访客浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')

    wrapper.unmount()
  })

  it('refreshes recent visits when the current token gains its confirmed owner identity', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-seven', user: null })
    mocks.getRecentVisits.mockImplementation(() => auth.user?.id === 7
      ? [{ path: '/threads/7', title: '确认身份后的历史', ts: Date.now() }]
      : [])
    const router = await createRouterForPanel()
    const wrapper = mount(RecentVisitsPanel, { global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } } })
    try {
      expect(wrapper.text()).not.toContain('确认身份后的历史')
      auth.hydrateCurrentUser({ id: 7, username: 'seven' }, 'token-seven')
      await flushPromises()
      expect(wrapper.text()).toContain('确认身份后的历史')
      const calls = mocks.getRecentVisits.mock.calls.length
      wrapper.unmount()
      auth.updateCurrentUser({ nickname: 'Seven' })
      await flushPromises()
      expect(mocks.getRecentVisits).toHaveBeenCalledTimes(calls)
    } finally { if (wrapper.exists()) wrapper.unmount() }
  })
})

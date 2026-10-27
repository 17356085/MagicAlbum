import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import SidebarLeft from '../SidebarLeft.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  getGovernanceMe: vi.fn(),
  listSections: vi.fn(),
  listPopularTags: vi.fn(),
  listTags: vi.fn(),
  listFollowingFeed: vi.fn(),
  getCurrentUser: vi.fn(),
}))

vi.mock('@/api/governance', () => ({ getGovernanceMe: mocks.getGovernanceMe }))
vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))
vi.mock('@/api/tags', () => ({ listPopularTags: mocks.listPopularTags, listTags: mocks.listTags }))
vi.mock('@/api/followingFeed', () => ({ listFollowingFeed: mocks.listFollowingFeed }))
vi.mock('@/api/users', () => ({ getCurrentUser: mocks.getCurrentUser }))
vi.mock('@/utils/image', () => ({ normalizeImageUrl: (url: string | undefined) => url || '' }))
vi.mock('@/composables/time', () => ({ formatRelativeTime: () => '' }))
vi.mock('@/composables/useRecentVisits', async () => ({
  ...(await vi.importActual<typeof import('@/composables/useRecentVisits')>('@/composables/useRecentVisits')),
  getRecentVisits: () => [],
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

async function createWrapper() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().applyAuthState({
    accessToken: 'real-token',
    user: { id: 1, username: 'reviewer' },
  })

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/admin/reports', name: 'admin-reports', component: { template: '<div />' } },
      { path: '/admin/community', name: 'admin-community', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover')
  await router.isReady()

  const wrapper = mount(SidebarLeft, {
    global: {
      plugins: [pinia, router],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
  return { wrapper, auth: useAuthStore() }
}

describe('SidebarLeft admin visibility', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    mocks.listPopularTags.mockResolvedValue([])
    mocks.listTags.mockResolvedValue({ items: [], page: 1, size: 18, total: 0 })
    mocks.listFollowingFeed.mockResolvedValue({ items: [], page: 1, size: 4, total: 0 })
    mocks.getCurrentUser.mockResolvedValue(null)
  })

  it('hides the admin link when server verification says the account is not an admin', async () => {
    mocks.getGovernanceMe.mockRejectedValue({ response: { status: 403 } })
    const { wrapper } = await createWrapper()

    await flushPromises()

    expect(mocks.getGovernanceMe).toHaveBeenCalledWith()
    expect(wrapper.find('a[href="/admin/reports"]').exists()).toBe(false)
  })

  it('shows the admin link only after server verification succeeds', async () => {
    mocks.getGovernanceMe.mockResolvedValue({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
    const { wrapper } = await createWrapper()

    await flushPromises()

    expect(wrapper.get('a[href="/admin/reports"]').text()).toContain('举报审核')
    expect(wrapper.get('a[href="/admin/community"]').text()).toContain('社区管理')
  })

  it('shows a reviewer link for a scoped moderator without showing community management', async () => {
    mocks.getGovernanceMe.mockResolvedValue({ admin: false, moderatedSectionIds: [3], postingRestricted: false, restrictedUntil: null })
    const { wrapper } = await createWrapper()

    await flushPromises()

    expect(wrapper.find('a[href="/admin/reports"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/community"]').exists()).toBe(false)
  })

  it('clears the admin link immediately when authentication changes', async () => {
    mocks.getGovernanceMe.mockResolvedValue({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
    const { wrapper, auth } = await createWrapper()
    await flushPromises()
    expect(wrapper.find('a[href="/admin/reports"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/community"]').exists()).toBe(true)

    auth.clearAuthState()
    await flushPromises()

    expect(wrapper.find('a[href="/admin/reports"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/admin/community"]').exists()).toBe(false)
  })
})

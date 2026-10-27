import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { defineComponent, nextTick, onMounted } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import App from '../App.vue'
import productionRouter from '@/router'
import UserProfile from '@/pages/UserProfile.vue'
import UserRelations from '@/pages/UserRelations.vue'
import { useAuthStore } from '@/stores/auth'

const api = vi.hoisted(() => ({ profile: vi.fn(), threads: vi.fn(), followers: vi.fn(), following: vi.fn() }))
vi.mock('@/api/users', () => ({
  getUserProfile: api.profile,
  listUserThreads: api.threads,
  listUserFollowers: api.followers,
  listUserFollowing: api.following,
  followUser: vi.fn(), unfollowUser: vi.fn(), getCurrentUser: vi.fn(),
}))
vi.mock('@/composables/useLiveNotifications', () => ({ useLiveNotifications: vi.fn() }))
vi.mock('@/composables/useMediaProcessing', () => ({ useMediaProcessing: vi.fn() }))

let wrapper: VueWrapper | undefined
let router: Router
let auth: ReturnType<typeof useAuthStore>
let settingsMounts = 0

async function start(path: string) {
  const pinia = createPinia()
  auth = useAuthStore(pinia)
  auth.applyAuthState({ accessToken: 'viewer-a', user: { id: 90, username: 'viewer-a' } })
  const Settings = defineComponent({ setup() { onMounted(() => settingsMounts++); return () => 'settings' } })
  router = createRouter({
    history: createMemoryHistory(),
    routes: productionRouter.options.routes.map(route => route.path === '/settings'
      ? { path: route.path, name: route.name, meta: route.meta, component: Settings }
      : route),
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(App, {
    global: {
      plugins: [pinia, router],
      stubs: { AppHeader: true, SidebarLeft: true, AiChat: true, MobileNavigation: true, MediaProcessingStatus: true },
    },
  })
  await flushPromises()
}

function nextButton() {
  return wrapper!.findAll('button').find(button => button.text() === '下一页')!
}

beforeEach(() => {
  localStorage.clear()
  settingsMounts = 0
  vi.clearAllMocks()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
  api.profile.mockImplementation(async (id: string) => ({ id, username: `user-${id}`, nickname: `owner-${id}`, followedByMe: auth.token === 'viewer-b' }))
  api.threads.mockImplementation(async (id: string, { page }: { page: number }) => ({
    items: [{ id: Number(id) * 10 + page, title: `posts-${id}-${page}` }], page, size: 10, total: 30,
  }))
  api.followers.mockImplementation(async (id: string, { page }: { page: number }) => ({
    items: [{ id: 30, username: `followers-${id}-${page}` }], page, size: 20, total: 60,
  }))
  api.following.mockImplementation(async (id: string, { page }: { page: number }) => ({
    items: [{ id: 40, username: `following-${id}-${page}` }], page, size: 20, total: 60,
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('App public route state through the production route records', () => {
  it('retains profile pagination across unrelated query and viewer changes, refreshing viewer flags once', async () => {
    await start('/users/1')
    const instance = wrapper!.findComponent(UserProfile).vm.$.uid
    await nextButton().trigger('click')
    await flushPromises()
    expect(wrapper!.text()).toContain('posts-1-2')
    await router.push('/users/1?source=search')
    await flushPromises()
    expect(wrapper!.findComponent(UserProfile).vm.$.uid === instance).toBe(true)
    expect(api.profile).toHaveBeenCalledTimes(1)
    expect(api.threads).toHaveBeenCalledTimes(2)

    auth.applyAuthState({ accessToken: 'viewer-b', user: { id: 91, username: 'viewer-b' } })
    await flushPromises()
    expect(wrapper!.findComponent(UserProfile).vm.$.uid === instance).toBe(true)
    expect(wrapper!.text()).toContain('posts-1-2')
    expect(wrapper!.find('[aria-label="页码"]').element).toHaveProperty('value', '2')
    expect(wrapper!.text()).toContain('已关注')
    expect(api.profile).toHaveBeenCalledTimes(2)
    expect(api.threads).toHaveBeenCalledTimes(2)
  })

  it('reuses a profile instance for a new owner and resets its public list to page one', async () => {
    await start('/users/1')
    const instance = wrapper!.findComponent(UserProfile).vm.$.uid
    await nextButton().trigger('click')
    await flushPromises()
    await router.push('/users/2')
    await flushPromises()
    expect(wrapper!.findComponent(UserProfile).vm.$.uid === instance).toBe(true)
    expect(wrapper!.text()).toContain('owner-2')
    expect(wrapper!.text()).toContain('posts-2-1')
    expect(api.profile).toHaveBeenCalledTimes(2)
    expect(api.threads).toHaveBeenLastCalledWith('2', { page: 1, size: 10 })
  })

  it('reconciles another tab through the App storage listener without resetting public pagination', async () => {
    await start('/users/1')
    const instance = wrapper!.findComponent(UserProfile).vm.$.uid
    await nextButton().trigger('click')
    await flushPromises()
    localStorage.setItem('accessToken', 'viewer-b')
    window.dispatchEvent(new StorageEvent('storage', { key: 'accessToken', storageArea: localStorage, newValue: 'viewer-b' }))
    await flushPromises()
    expect(auth.token).toBe('viewer-b')
    expect(auth.user).toBeNull()
    expect(wrapper!.findComponent(UserProfile).vm.$.uid === instance).toBe(true)
    expect(wrapper!.text()).toContain('posts-1-2')
    expect(wrapper!.text()).toContain('已关注')
    expect(api.profile).toHaveBeenCalledTimes(2)
    expect(api.threads).toHaveBeenCalledTimes(2)
  })

  it('shares relation instances across tabs and preserves owner profile without redundant reads', async () => {
    await start('/users/1/followers')
    const instance = wrapper!.findComponent(UserRelations).vm.$.uid
    await nextButton().trigger('click')
    await flushPromises()
    expect(api.profile).toHaveBeenCalledTimes(1)
    await router.push('/users/1/following')
    await flushPromises()
    expect(wrapper!.findComponent(UserRelations).vm.$.uid === instance).toBe(true)
    expect(wrapper!.text()).toContain('following-1-1')
    expect(api.profile).toHaveBeenCalledTimes(1)
    expect(api.following).toHaveBeenCalledTimes(1)
    await nextButton().trigger('click')
    await flushPromises()
    auth.applyAuthState({ accessToken: 'viewer-b', user: { id: 91, username: 'viewer-b' } })
    await router.push('/users/1/following?source=profile')
    await flushPromises()
    expect(wrapper!.findComponent(UserRelations).vm.$.uid === instance).toBe(true)
    expect(wrapper!.text()).toContain('following-1-2')
    expect(api.profile).toHaveBeenCalledTimes(1)
    expect(api.following).toHaveBeenCalledTimes(2)
    await router.push('/users/2/following')
    await flushPromises()
    expect(wrapper!.findComponent(UserRelations).vm.$.uid === instance).toBe(true)
    expect(wrapper!.text()).toContain('owner-2')
    expect(wrapper!.text()).toContain('following-2-1')
    expect(api.profile).toHaveBeenCalledTimes(2)
  })

  it('continues to recreate other pages on auth and full-path changes', async () => {
    await start('/settings')
    expect(settingsMounts).toBe(1)
    auth.applyAuthState({ accessToken: 'viewer-b', user: { id: 91, username: 'viewer-b' } })
    await nextTick()
    expect(settingsMounts).toBe(2)
    await router.push('/settings?tab=account')
    await flushPromises()
    expect(settingsMounts).toBe(3)
  })
})

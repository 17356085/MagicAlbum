import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import type { User, UserProfile } from '@/types'

const mocks = vi.hoisted(() => ({
  suggestUsers: vi.fn(),
  getUserProfile: vi.fn(),
  getMyProfile: vi.fn(),
}))

vi.mock('@/api/users', () => ({
  suggestUsers: mocks.suggestUsers,
  getUserProfile: mocks.getUserProfile,
  getCurrentUser: vi.fn(),
}))
vi.mock('@/api/settings', () => ({
  getMyProfile: mocks.getMyProfile,
}))

const SearchBoxStub = defineComponent({
  props: [
    'searchType',
    'searchQuery',
    'suggestOpen',
    'suggestLoading',
    'suggestError',
    'visibleSuggestions',
    'suggestProfiles',
    'activeIndex',
  ],
  emits: [
    'update:search-type',
    'update:search-query',
    'input-focus',
    'input-blur',
  ],
  template: `
    <div data-testid="search-box">
      <button data-testid="use-users" @click="$emit('update:search-type', 'users')">users</button>
      <button data-testid="use-threads" @click="$emit('update:search-type', 'threads')">threads</button>
      <input data-testid="search-query" :value="searchQuery" @input="$emit('update:search-query', $event.target.value)" />
      <button data-testid="blur-search" @click="$emit('input-blur')">blur</button>
      <button data-testid="focus-search" @click="$emit('input-focus')">focus</button>
      <output data-testid="visible-suggestions">{{ visibleSuggestions.map((item) => item.username).join(',') }}</output>
    </div>
  `,
})

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function user(id: number, username: string): User {
  return { id, username }
}

function profile(id: number, nickname: string): UserProfile & Partial<User> {
  return {
    id,
    userId: id,
    username: `user-${id}`,
    nickname,
    bio: '',
    homepageUrl: '',
    location: '',
    avatarUrl: '',
  }
}

async function createWrapper() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().clearAuthState()
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/discover', name: 'discover', component: { template: '<div />' } }],
  })
  await router.push('/discover')
  await router.isReady()
  const wrapper = mount(AppHeader, {
    global: {
      plugins: [pinia, router],
      stubs: {
        SearchBox: SearchBoxStub,
        BrandBar: true,
        NotificationsMenu: true,
        AuthEntry: true,
        UserMenu: true,
        AuthModal: true,
        RouterLink: RouterLinkStub,
      },
    },
  })
  return { wrapper, search: wrapper.findComponent(SearchBoxStub) }
}

describe('AppHeader suggestions', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    mocks.suggestUsers.mockResolvedValue([])
    mocks.getUserProfile.mockResolvedValue(profile(1, ''))
    mocks.getMyProfile.mockResolvedValue(profile(1, ''))
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps a completed newer keyword when the older request resolves later', async () => {
    const older = deferred<User[]>()
    const newer = deferred<User[]>()
    mocks.suggestUsers.mockImplementation((query: string) => query === 'old' ? older.promise : newer.promise)
    const { wrapper, search } = await createWrapper()

    search.vm.$emit('update:search-type', 'users')
    search.vm.$emit('update:search-query', 'old')
    await nextTick()
    vi.advanceTimersByTime(250)
    await flushPromises()
    expect(mocks.suggestUsers).toHaveBeenCalledWith('old', 5)

    search.vm.$emit('update:search-query', 'new')
    await nextTick()
    vi.advanceTimersByTime(250)
    await flushPromises()
    expect(mocks.suggestUsers).toHaveBeenCalledWith('new', 5)

    newer.resolve([user(2, 'new-user')])
    await flushPromises()
    expect(search.props('visibleSuggestions')).toEqual([user(2, 'new-user')])

    older.resolve([user(1, 'old-user')])
    await flushPromises()
    expect(search.props('visibleSuggestions')).toEqual([user(2, 'new-user')])
    wrapper.unmount()
  })

  it('clears pending suggestions when the query is empty or search type changes', async () => {
    const older = deferred<User[]>()
    mocks.suggestUsers.mockReturnValue(older.promise)
    const { wrapper, search } = await createWrapper()

    search.vm.$emit('update:search-type', 'users')
    search.vm.$emit('update:search-query', 'old')
    await nextTick()
    vi.advanceTimersByTime(250)
    await flushPromises()

    search.vm.$emit('update:search-query', '')
    await nextTick()
    search.vm.$emit('update:search-type', 'threads')
    older.resolve([user(1, 'old-user')])
    await flushPromises()

    expect(search.props('visibleSuggestions')).toEqual([])
    expect(search.props('suggestOpen')).toBe(false)
    expect(mocks.suggestUsers).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('does not write a late profile prefetch after the request changes', async () => {
    const avatar = deferred<UserProfile & Partial<User>>()
    mocks.suggestUsers.mockResolvedValue([user(1, 'alpha-user')])
    mocks.getUserProfile.mockReturnValue(avatar.promise)
    const { wrapper, search } = await createWrapper()

    search.vm.$emit('update:search-type', 'users')
    search.vm.$emit('update:search-query', 'alpha')
    await nextTick()
    vi.advanceTimersByTime(250)
    await flushPromises()
    expect(mocks.getUserProfile).toHaveBeenCalledWith(1)

    search.vm.$emit('update:search-query', 'beta')
    await nextTick()
    avatar.resolve(profile(1, '过期昵称'))
    await flushPromises()

    expect(search.props('suggestProfiles')).toEqual({})
    wrapper.unmount()
  })

  it('cancels suggestion and blur timers when unmounted', async () => {
    const pending = deferred<User[]>()
    mocks.suggestUsers.mockReturnValue(pending.promise)
    const { wrapper, search } = await createWrapper()

    search.vm.$emit('update:search-type', 'users')
    search.vm.$emit('update:search-query', 'pending')
    await nextTick()
    vi.advanceTimersByTime(250)
    await flushPromises()
    search.vm.$emit('input-blur')
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
    pending.resolve([user(3, 'late-user')])
    await flushPromises()
    expect(mocks.getUserProfile).not.toHaveBeenCalled()
  })
})

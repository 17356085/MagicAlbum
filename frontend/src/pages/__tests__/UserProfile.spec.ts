import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { InteractionResponseError } from '@/api/interactionState'
import UserProfile from '../UserProfile.vue'
import type { PageResult, Thread, UserProfile as UserProfileModel } from '@/types'

const mocks = vi.hoisted(() => ({
  getUserProfile: vi.fn(),
  listUserThreads: vi.fn(),
  followUser: vi.fn(),
  unfollowUser: vi.fn(),
  route: { params: { id: '1' } } as { params: { id: string } },
  router: { push: vi.fn() },
}))

vi.mock('@/api/users', () => ({
  getUserProfile: mocks.getUserProfile,
  listUserThreads: mocks.listUserThreads,
  followUser: mocks.followUser,
  unfollowUser: mocks.unfollowUser,
}))

vi.mock('@/utils/image', () => ({
  normalizeImageUrl: (url: string | undefined) => url || '',
}))

vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: () => ({}),
  renderMarkdown: (_renderer: unknown, text: string | undefined) => text || '',
}))

vi.mock('@/utils/router', () => ({
  safeBack: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
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

function profile(id: string, nickname = `用户${id}`): UserProfileModel {
  return {
    id,
    username: `user${id}`,
    nickname,
    bio: '',
    homepageUrl: '',
    location: '',
    avatarUrl: '',
    followerCount: 0,
    followingCount: 0,
    followedByMe: false,
    followingMe: false,
  }
}

function threads(id: number, title: string, page = 1, total = 1): PageResult<Thread> {
  return {
    items: [{ id, title, content: `${title} 内容`, createdAt: '2026-10-07T00:00:00Z' }],
    page,
    size: 10,
    total,
  }
}

const mountedWrappers: VueWrapper[] = []

function loginAs(id: number, token = `account-${id}`) {
  useAuthStore().loginWithAuthResponse({ accessToken: token, user: { id, username: `viewer${id}` } })
}

function createWrapper(viewerId?: number, viewerToken = viewerId === undefined ? undefined : `account-${viewerId}`) {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (viewerId !== undefined) loginAs(viewerId, viewerToken)
  const wrapper = mount(UserProfile, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

function followButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find(button => ['关注', '已关注', '处理中...', '关注状态未知'].includes(button.text()))
}

describe('UserProfile request ordering', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    mocks.getUserProfile.mockReset()
    mocks.listUserThreads.mockReset()
    mocks.followUser.mockReset()
    mocks.unfollowUser.mockReset()
    mocks.router.push.mockReset()
    mocks.route = reactive({ params: reactive({ id: '1' }) })
    mocks.getUserProfile.mockResolvedValue(profile('1'))
    mocks.listUserThreads.mockResolvedValue(threads(11, '用户1的帖子'))
  })

  afterEach(() => {
    mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
  })

  it('keeps the newest profile and thread results after switching users', async () => {
    const oldProfile = deferred<UserProfileModel>()
    const newProfile = deferred<UserProfileModel>()
    const oldThreads = deferred<PageResult<Thread>>()
    const newThreads = deferred<PageResult<Thread>>()
    mocks.getUserProfile.mockReturnValueOnce(oldProfile.promise).mockReturnValueOnce(newProfile.promise)
    mocks.listUserThreads.mockReturnValueOnce(oldThreads.promise).mockReturnValueOnce(newThreads.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.params.id = '2'
    await nextTick()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(2)
    expect(mocks.listUserThreads).toHaveBeenLastCalledWith('2', { page: 1, size: 10 })

    newProfile.resolve(profile('2', '新用户'))
    newThreads.resolve(threads(22, '新用户的帖子'))
    await flushPromises()
    oldProfile.resolve(profile('1', '旧用户'))
    oldThreads.resolve(threads(11, '旧用户的帖子'))
    await flushPromises()

    expect(wrapper.text()).toContain('新用户')
    expect(wrapper.text()).toContain('新用户的帖子')
    expect(wrapper.text()).not.toContain('旧用户')
    expect(wrapper.text()).not.toContain('旧用户的帖子')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps the current user loading while stale profile and thread requests fail', async () => {
    const oldProfile = deferred<UserProfileModel>()
    const newProfile = deferred<UserProfileModel>()
    const oldThreads = deferred<PageResult<Thread>>()
    const newThreads = deferred<PageResult<Thread>>()
    mocks.getUserProfile.mockReturnValueOnce(oldProfile.promise).mockReturnValueOnce(newProfile.promise)
    mocks.listUserThreads.mockReturnValueOnce(oldThreads.promise).mockReturnValueOnce(newThreads.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.params.id = '2'
    await nextTick()

    oldProfile.reject(new Error('旧资料失败'))
    oldThreads.reject(new Error('旧帖子失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('加载中...')
    expect(wrapper.text()).not.toContain('旧资料失败')
    expect(wrapper.text()).not.toContain('旧帖子失败')

    newProfile.resolve(profile('2', '新用户'))
    newThreads.resolve(threads(22, '新用户的帖子'))
    await flushPromises()
    expect(wrapper.text()).toContain('新用户')
    expect(wrapper.text()).toContain('新用户的帖子')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('keeps the newest page when pagination requests resolve out of order', async () => {
    const pageTwo = deferred<PageResult<Thread>>()
    const pageThree = deferred<PageResult<Thread>>()
    mocks.listUserThreads
      .mockResolvedValueOnce(threads(11, '第1页帖子', 1, 30))
      .mockReturnValueOnce(pageTwo.promise)
      .mockReturnValueOnce(pageThree.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await nextButton!.trigger('click')
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(3)

    pageThree.resolve(threads(33, '第3页帖子', 3, 30))
    await flushPromises()
    pageTwo.resolve(threads(22, '第2页帖子', 2, 30))
    await flushPromises()

    expect(wrapper.text()).toContain('第3页帖子')
    expect(wrapper.text()).not.toContain('第2页帖子')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an in-flight thread response after unmount', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listUserThreads.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await nextTick()
    const vm = wrapper.vm as unknown as { threads: PageResult<Thread>; threadsLoading: boolean }
    wrapper.unmount()

    request.resolve(threads(11, '卸载后帖子'))
    await flushPromises()

    expect(vm.threads.items).toEqual([])
    expect(vm.threadsLoading).toBe(true)
  })

  it('refreshes viewer-specific follow state on account change while preserving the public post page', async () => {
    mocks.getUserProfile.mockResolvedValueOnce({ ...profile('1'), followedByMe: true })
      .mockResolvedValueOnce(profile('1'))
    mocks.listUserThreads.mockResolvedValueOnce(threads(11, '第1页帖子', 1, 20))
      .mockResolvedValueOnce(threads(22, '第2页帖子', 2, 20))
    const wrapper = createWrapper(10)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '下一页')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已关注')

    loginAs(20)
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('已关注')
    expect(wrapper.text()).toContain('第2页帖子')
  })

  it('ignores an old viewer profile response after the new viewer profile loads', async () => {
    const oldProfile = deferred<UserProfileModel>()
    const newProfile = deferred<UserProfileModel>()
    mocks.getUserProfile.mockReturnValueOnce(oldProfile.promise).mockReturnValueOnce(newProfile.promise)
    const wrapper = createWrapper(10)
    await nextTick()
    loginAs(20)
    await flushPromises()
    newProfile.resolve(profile('1', '当前资料'))
    await flushPromises()
    oldProfile.resolve({ ...profile('1', '过期资料'), followedByMe: true })
    await flushPromises()

    expect(wrapper.text()).toContain('当前资料')
    expect(wrapper.text()).not.toContain('过期资料')
    expect(wrapper.text()).not.toContain('已关注')
  })

  it.each(['success', 'failure'])('ignores an old account follow %s and emits no stale follow signal', async outcome => {
    const write = deferred<{ following: boolean; followerCount: number; followingCount: number }>()
    mocks.followUser.mockReturnValue(write.promise)
    const wrapper = createWrapper(10)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '关注')!.trigger('click')
    const followSignal = vi.fn()
    window.addEventListener('follow-state-updated', followSignal)
    try {
      loginAs(20)
      await flushPromises()
      if (outcome === 'success') write.resolve({ following: true, followerCount: 99, followingCount: 88 })
      else write.reject(new Error('old write failed'))
      await flushPromises()

      expect(wrapper.text()).not.toContain('已关注')
      expect(wrapper.text()).not.toContain('处理中...')
      expect(wrapper.text()).not.toContain('关注操作失败')
      expect(wrapper.text()).not.toContain('粉丝 99')
      expect(followSignal).not.toHaveBeenCalled()
    } finally { window.removeEventListener('follow-state-updated', followSignal) }
  })

  it('invalidates a prior session even when logout and login restore the same token in one turn', async () => {
    const write = deferred<{ following: boolean; followerCount: number; followingCount: number }>()
    mocks.followUser.mockReturnValue(write.promise)
    const wrapper = createWrapper(10)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '关注')!.trigger('click')

    useAuthStore().logout()
    loginAs(10)
    await flushPromises()
    write.resolve({ following: true, followerCount: 99, followingCount: 88 })
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('已关注')
    expect(wrapper.text()).not.toContain('粉丝 99')
  })

  it('coalesces a simultaneous route and account change into one current profile load', async () => {
    const wrapper = createWrapper(10)
    await flushPromises()
    loginAs(20)
    mocks.route.params.id = '2'
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.getUserProfile).toHaveBeenLastCalledWith('2')
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('cancels a queued account refresh when the page unmounts', async () => {
    const wrapper = createWrapper(10)
    await flushPromises()
    loginAs(20)
    wrapper.unmount()
    await flushPromises()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)
  })

  it('retries a failed profile load without rereading public posts', async () => {
    mocks.getUserProfile.mockRejectedValueOnce(new Error('profile unavailable')).mockResolvedValueOnce(profile('1'))
    const wrapper = createWrapper()
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('profile unavailable')
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('用户1')
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(1)
  })

  it('keeps valid anonymous empty storage reads public', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledExactlyOnceWith('1')
    expect(mocks.listUserThreads).toHaveBeenCalledExactlyOnceWith('1', { page: 1, size: 10 })
    expect(wrapper.text()).toContain('用户1')
  })

  it('blocks profile and post GETs when storage changes before the request', async () => {
    const wrapper = createWrapper(10, 'account-a')
    localStorage.setItem('accessToken', 'account-b')
    await flushPromises()

    expect(mocks.getUserProfile).not.toHaveBeenCalled()
    expect(mocks.listUserThreads).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('登录状态已变化，请重试')
  })

  it('rejects storage drift before a follow write', async () => {
    const wrapper = createWrapper(10, 'account-a')
    await flushPromises()
    localStorage.setItem('accessToken', 'account-b')

    await followButton(wrapper)!.trigger('click')

    expect(mocks.followUser).not.toHaveBeenCalled()
    expect(mocks.unfollowUser).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('登录状态已变化，请重试')
    expect(wrapper.text()).not.toContain('处理中...')
  })

  it('rejects a pending profile success after storage drift', async () => {
    const read = deferred<UserProfileModel>()
    mocks.getUserProfile.mockReset().mockReturnValueOnce(read.promise)
    const wrapper = createWrapper(10, 'account-a')
    await nextTick()
    localStorage.setItem('accessToken', 'account-b')
    read.resolve({ ...profile('1'), followedByMe: true })
    await flushPromises()

    expect(wrapper.text()).toContain('登录状态已变化，请重试')
    expect(wrapper.text()).not.toContain('已关注')
  })

  it('rejects a pending profile error after storage drift', async () => {
    const read = deferred<UserProfileModel>()
    mocks.getUserProfile.mockReset().mockReturnValueOnce(read.promise)
    const wrapper = createWrapper(10, 'account-a')
    await nextTick()
    localStorage.setItem('accessToken', 'account-b')
    read.reject(new Error('旧资料失败'))
    await flushPromises()

    expect(wrapper.text()).toContain('登录状态已变化，请重试')
    expect(wrapper.text()).not.toContain('旧资料失败')
  })

  it.each(['success', 'error'] as const)('rejects a pending follow %s after storage drift', async outcome => {
    const write = deferred<{ following: boolean; followedByMe: boolean; followerCount: number; followingCount: number }>()
    mocks.followUser.mockReturnValueOnce(write.promise)
    const wrapper = createWrapper(10, 'account-a')
    await flushPromises()
    await followButton(wrapper)!.trigger('click')
    localStorage.setItem('accessToken', 'account-b')

    if (outcome === 'success') write.resolve({ following: true, followedByMe: true, followerCount: 99, followingCount: 88 })
    else write.reject(new Error('旧关注失败'))
    await flushPromises()

    expect(wrapper.text()).toContain('登录状态已变化，请重试')
    expect(wrapper.text()).not.toContain('已关注')
    expect(wrapper.text()).not.toContain('旧关注失败')
    expect(wrapper.text()).not.toContain('处理中...')
  })

  it('invalidates nested known-owner A to B to A responses without a token event', async () => {
    const first = deferred<UserProfileModel>()
    const second = deferred<UserProfileModel>()
    mocks.getUserProfile.mockReset().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = createWrapper(10, 'account-a')
    await nextTick()
    const auth = useAuthStore()
    auth.user!.id = 20
    auth.user!.id = 10
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    second.resolve({ ...profile('1'), followedByMe: false })
    await flushPromises()
    first.resolve({ ...profile('1'), followedByMe: true })
    await flushPromises()

    expect(wrapper.text()).not.toContain('已关注')
  })

  it('accepts same-token unknown-to-known hydration without reloading posts or dropping the current page', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    loginAs(10, 'account-a')
    const auth = useAuthStore()
    auth.user = null
    localStorage.removeItem('currentUser')
    localStorage.removeItem('currentUserSession.v1')
    mocks.listUserThreads.mockResolvedValueOnce(threads(11, '第1页帖子', 1, 20)).mockResolvedValueOnce(threads(22, '第2页帖子', 2, 20))
    const wrapper = mount(UserProfile, {
      global: {
        plugins: [pinia],
        stubs: { RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' } },
      },
    })
    mountedWrappers.push(wrapper)
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '下一页')!.trigger('click')
    await flushPromises()
    const profileCalls = mocks.getUserProfile.mock.calls.length
    const postCalls = mocks.listUserThreads.mock.calls.length

    auth.hydrateCurrentUser({ id: 10, username: 'viewer10' }, 'account-a')
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(profileCalls)
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(postCalls)
    expect(wrapper.text()).toContain('第2页帖子')
  })

  it('does not refresh on an auth-generation-only change', async () => {
    createWrapper(10, 'account-a')
    await flushPromises()
    const profileCalls = mocks.getUserProfile.mock.calls.length
    const postCalls = mocks.listUserThreads.mock.calls.length

    useAuthStore().invalidateAuthOperations()
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(profileCalls)
    expect(mocks.listUserThreads).toHaveBeenCalledTimes(postCalls)
  })

  it('keeps an accepted mutation ahead of an older same-route profile refresh', async () => {
    const staleRead = deferred<UserProfileModel>()
    mocks.getUserProfile.mockResolvedValueOnce({ ...profile('1'), followedByMe: false }).mockReturnValueOnce(staleRead.promise)
    mocks.followUser.mockResolvedValueOnce({ following: true, followedByMe: true, followerCount: 4, followingCount: 5 })
    const wrapper = createWrapper(10, 'account-a')
    await flushPromises()
    window.dispatchEvent(new CustomEvent('auth-state-changed'))
    await nextTick()
    await followButton(wrapper)!.trigger('click')
    staleRead.resolve({ ...profile('1'), followedByMe: false })
    await flushPromises()

    expect(wrapper.text()).toContain('已关注')
  })

  it('shows a retry when the relationship state is unknown and never assumes unfollowed', async () => {
    mocks.getUserProfile.mockResolvedValueOnce({ ...profile('1'), followedByMe: undefined })
    const wrapper = createWrapper(10, 'account-a')
    await flushPromises()

    expect(wrapper.text()).toContain('关注状态加载失败，请重试')
    expect(followButton(wrapper)!.attributes('disabled')).toBeDefined()
    await followButton(wrapper)!.trigger('click')
    expect(mocks.followUser).not.toHaveBeenCalled()
  })

  it('does not announce success or zero counts for a malformed mutation response', async () => {
    mocks.getUserProfile.mockResolvedValueOnce({ ...profile('1'), followerCount: 7, followingCount: 8 })
    mocks.followUser.mockResolvedValueOnce({ following: 'yes', followedByMe: 'yes', followerCount: 'bad', followingCount: null })
    const signal = vi.fn()
    window.addEventListener('follow-state-updated', signal)
    try {
      const wrapper = createWrapper(10, 'account-a')
      await flushPromises()
      await followButton(wrapper)!.trigger('click')
      await flushPromises()

      expect(signal).not.toHaveBeenCalled()
      expect(wrapper.text()).not.toContain('已关注')
      expect(wrapper.text()).toContain('关注结果暂未确认，请重试状态')
      expect(wrapper.text()).toContain('粉丝 7')
      expect(wrapper.text()).toContain('关注 8')
    } finally {
      window.removeEventListener('follow-state-updated', signal)
    }
  })

  it('turns decoder errors into an unknown relationship with a retry state', async () => {
    mocks.getUserProfile.mockResolvedValueOnce({ ...profile('1'), followerCount: 7, followingCount: 8 })
    mocks.followUser.mockRejectedValueOnce(new InteractionResponseError('/users/1/follow', 'followerCount'))
    const wrapper = createWrapper(10, 'account-a')
    await flushPromises()

    await followButton(wrapper)!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('互动状态暂不可用，请稍后重试')
    expect(followButton(wrapper)!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('已关注')
    expect(wrapper.text()).toContain('粉丝 7')
    expect(wrapper.text()).toContain('关注 8')
  })

  it('coalesces repeated relationship retries while keeping the current profile visible', async () => {
    const read = deferred<UserProfileModel>()
    mocks.getUserProfile.mockResolvedValueOnce({ ...profile('1'), followedByMe: undefined }).mockReturnValueOnce(read.promise)
    const wrapper = createWrapper(10, 'account-a')
    await flushPromises()
    const retry = wrapper.findAll('button').find(button => button.text() === '重试')!
    await retry.trigger('click')
    await retry.trigger('click')
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('用户1')
    read.resolve(profile('1'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('关注状态加载失败')
  })
})

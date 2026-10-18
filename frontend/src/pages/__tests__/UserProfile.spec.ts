import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

function createWrapper() {
  return mount(UserProfile, {
    global: {
      plugins: [createPinia()],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
}

describe('UserProfile request ordering', () => {
  beforeEach(() => {
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
})

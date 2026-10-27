import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UserRelations from '../UserRelations.vue'
import type { PageResult, User, UserProfile } from '@/types'

const mocks = vi.hoisted(() => ({
  getUserProfile: vi.fn(),
  listUserFollowers: vi.fn(),
  listUserFollowing: vi.fn(),
  route: {
    params: { id: '1' },
    name: 'user-followers',
    query: {},
  } as { params: { id: string }; name: string; query: Record<string, unknown> },
  router: { push: vi.fn() },
}))

vi.mock('@/api/users', () => ({
  getUserProfile: mocks.getUserProfile,
  listUserFollowers: mocks.listUserFollowers,
  listUserFollowing: mocks.listUserFollowing,
}))

vi.mock('@/utils/image', () => ({
  normalizeImageUrl: (url: string | undefined) => url || '',
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

function profile(id: string, nickname = `用户${id}`): UserProfile {
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

function relation(
  id: number,
  username: string,
  page = 1,
  total = 1,
): PageResult<User> {
  return {
    items: [{ id, username, nickname: username }],
    page,
    size: 20,
    total,
  }
}

function createWrapper() {
  return mount(UserRelations, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
}

function resetRoute(id = '1', name = 'user-followers') {
  mocks.route = reactive({
    params: reactive({ id }),
    name,
    query: {},
  })
}

describe('UserRelations request ordering and independent resources', () => {
  beforeEach(() => {
    mocks.getUserProfile.mockReset()
    mocks.listUserFollowers.mockReset()
    mocks.listUserFollowing.mockReset()
    mocks.router.push.mockReset()
    resetRoute()
    mocks.getUserProfile.mockResolvedValue(profile('1'))
    mocks.listUserFollowers.mockResolvedValue(relation(11, 'follower-1'))
    mocks.listUserFollowing.mockResolvedValue(relation(21, 'following-1'))
  })

  it('keeps the newest owner and relation rows when switching users', async () => {
    const oldProfile = deferred<UserProfile>()
    const newProfile = deferred<UserProfile>()
    const oldRelations = deferred<PageResult<User>>()
    const newRelations = deferred<PageResult<User>>()
    mocks.getUserProfile.mockReturnValueOnce(oldProfile.promise).mockReturnValueOnce(newProfile.promise)
    mocks.listUserFollowers.mockReturnValueOnce(oldRelations.promise).mockReturnValueOnce(newRelations.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.params.id = '2'
    await nextTick()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(2)
    expect(mocks.listUserFollowers).toHaveBeenLastCalledWith('2', { page: 1, size: 20 })

    newProfile.resolve(profile('2', '新用户'))
    newRelations.resolve(relation(22, 'new-user'))
    await flushPromises()
    oldProfile.resolve(profile('1', '旧用户'))
    oldRelations.resolve(relation(11, 'old-user'))
    await flushPromises()

    expect(wrapper.text()).toContain('新用户')
    expect(wrapper.text()).toContain('new-user')
    expect(wrapper.text()).not.toContain('旧用户')
    expect(wrapper.text()).not.toContain('old-user')
    wrapper.unmount()
  })

  it('switches relation kind without refetching the owner profile', async () => {
    const oldRelations = deferred<PageResult<User>>()
    const newRelations = deferred<PageResult<User>>()
    mocks.listUserFollowers.mockReturnValueOnce(oldRelations.promise)
    mocks.listUserFollowing.mockReturnValueOnce(newRelations.promise)

    const wrapper = createWrapper()
    await nextTick()
    await flushPromises()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)

    mocks.route.name = 'user-following'
    await nextTick()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)
    expect(mocks.listUserFollowing).toHaveBeenCalledExactlyOnceWith('1', { page: 1, size: 20 })

    newRelations.resolve(relation(22, 'following-new'))
    await flushPromises()
    oldRelations.resolve(relation(11, 'followers-old'))
    await flushPromises()

    expect(wrapper.text()).toContain('following-new')
    expect(wrapper.text()).not.toContain('followers-old')
    wrapper.unmount()
  })

  it('keeps the newest loading state when an older relation request fails', async () => {
    const oldRelations = deferred<PageResult<User>>()
    const newRelations = deferred<PageResult<User>>()
    mocks.listUserFollowers.mockReturnValueOnce(oldRelations.promise)
    mocks.listUserFollowing.mockReturnValueOnce(newRelations.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.name = 'user-following'
    await nextTick()

    oldRelations.reject(new Error('旧关系失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRelations.resolve(relation(22, 'following-new'))
    await flushPromises()
    expect(wrapper.text()).toContain('following-new')
    expect(wrapper.text()).not.toContain('正在加载...')
    wrapper.unmount()
  })

  it('ignores in-flight profile and relation responses after unmount', async () => {
    const requestedProfile = deferred<UserProfile>()
    const requestedRelations = deferred<PageResult<User>>()
    mocks.getUserProfile.mockReturnValueOnce(requestedProfile.promise)
    mocks.listUserFollowers.mockReturnValueOnce(requestedRelations.promise)

    const wrapper = createWrapper()
    await nextTick()
    const vm = wrapper.vm as unknown as {
      profile: UserProfile | null
      list: PageResult<User>
      loading: boolean
    }
    wrapper.unmount()

    requestedProfile.resolve(profile('1', '卸载后用户'))
    requestedRelations.resolve(relation(11, '卸载后关系'))
    await flushPromises()

    expect(vm.profile).toBeNull()
    expect(vm.list.items).toEqual([])
    expect(vm.loading).toBe(true)
  })

  it('loads only the relation page when paginating', async () => {
    mocks.listUserFollowers
      .mockResolvedValueOnce(relation(11, 'page-1', 1, 40))
      .mockResolvedValueOnce(relation(12, 'page-2', 2, 40))

    const wrapper = createWrapper()
    await flushPromises()
    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledExactlyOnceWith('1')
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(2)
    expect(mocks.listUserFollowers).toHaveBeenLastCalledWith('1', { page: 2, size: 20 })
    expect(wrapper.text()).toContain('page-2')
    wrapper.unmount()
  })

  it('does not reload when navigation only replaces an unrelated query object', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    mocks.getUserProfile.mockClear()
    mocks.listUserFollowers.mockClear()

    mocks.route.query = { source: 'header' }
    await nextTick()
    await flushPromises()

    expect(mocks.getUserProfile).not.toHaveBeenCalled()
    expect(mocks.listUserFollowers).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('follower-1')
    wrapper.unmount()
  })

  it('shows relation rows when the owner profile fails and retries profile independently', async () => {
    mocks.getUserProfile.mockRejectedValueOnce(new Error('资料失败')).mockResolvedValueOnce(profile('1', '资料恢复'))
    mocks.listUserFollowers.mockResolvedValueOnce(relation(11, 'relation-kept'))

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('relation-kept')
    expect(wrapper.text()).toContain('用户 1 的粉丝')
    expect(wrapper.text()).not.toContain('加载粉丝列表失败')
    const retry = wrapper.find('button[aria-label="重试加载用户资料"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.listUserFollowers).toHaveBeenCalledExactlyOnceWith('1', { page: 1, size: 20 })
    expect(wrapper.text()).toContain('资料恢复 的粉丝')
    wrapper.unmount()
  })

  it('shows a list error separately and retries the current page and relation kind', async () => {
    mocks.listUserFollowers
      .mockResolvedValueOnce(relation(11, 'page-1', 1, 40))
      .mockRejectedValueOnce(new Error('关系失败'))
      .mockResolvedValueOnce(relation(12, 'page-2-recovered', 2, 40))

    const wrapper = createWrapper()
    await flushPromises()
    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('加载粉丝列表失败')
    expect(wrapper.text()).not.toContain('暂无粉丝')
    const retry = wrapper.find('button[aria-label="重试加载粉丝列表"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    await flushPromises()

    expect(mocks.listUserFollowers).toHaveBeenLastCalledWith('1', { page: 2, size: 20 })
    expect(mocks.getUserProfile).toHaveBeenCalledExactlyOnceWith('1')
    expect(wrapper.text()).toContain('page-2-recovered')
    wrapper.unmount()
  })

  it('keeps a pager for an empty later page and returns without refetching the profile', async () => {
    mocks.listUserFollowers
      .mockResolvedValueOnce(relation(11, 'page-1', 1, 21))
      .mockResolvedValueOnce({ items: [], page: 2, size: 20, total: 21 })
      .mockResolvedValueOnce(relation(11, 'page-1-returned', 1, 21))

    const wrapper = createWrapper()
    await flushPromises()

    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('当前页暂无粉丝')
    const previousButton = wrapper.findAll('button').find((button) => button.text() === '上一页')
    expect(previousButton).toBeDefined()
    await previousButton!.trigger('click')
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledExactlyOnceWith('1')
    expect(mocks.listUserFollowers).toHaveBeenLastCalledWith('1', { page: 1, size: 20 })
    expect(wrapper.text()).toContain('page-1-returned')
    wrapper.unmount()
  })

  it('hides a stale pager while a later page is loading or has failed', async () => {
    const pendingPage = deferred<PageResult<User>>()
    mocks.listUserFollowers
      .mockResolvedValueOnce(relation(11, 'page-1', 1, 40))
      .mockReturnValueOnce(pendingPage.promise)

    const wrapper = createWrapper()
    await flushPromises()

    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await nextTick()
    expect(wrapper.findAll('button').some((button) => button.text() === '上一页')).toBe(false)

    pendingPage.reject(new Error('关系失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('加载粉丝列表失败')
    expect(wrapper.findAll('button').some((button) => button.text() === '上一页')).toBe(false)
    wrapper.unmount()
  })

  it('coalesces same-tick profile and relation retries after failures', async () => {
    mocks.getUserProfile
      .mockRejectedValueOnce(new Error('资料失败'))
      .mockResolvedValueOnce(profile('1', '资料恢复'))
      .mockResolvedValueOnce(profile('1', '不应重复'))
    mocks.listUserFollowers
      .mockRejectedValueOnce(new Error('关系失败'))
      .mockResolvedValueOnce(relation(11, '关系恢复'))
      .mockResolvedValueOnce(relation(12, '不应重复'))

    const wrapper = createWrapper()
    await flushPromises()

    const profileRetry = wrapper.find('button[aria-label="重试加载用户资料"]')
    const relationRetry = wrapper.find('button[aria-label="重试加载粉丝列表"]')
    expect(profileRetry.exists()).toBe(true)
    expect(relationRetry.exists()).toBe(true)
    await Promise.all([
      profileRetry.trigger('click'),
      profileRetry.trigger('click'),
      relationRetry.trigger('click'),
      relationRetry.trigger('click'),
    ])
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('资料恢复 的粉丝')
    expect(wrapper.text()).toContain('关系恢复')
    wrapper.unmount()
  })

  it('ignores page changes during loading and rejects unsafe input without a request', async () => {
    const pendingPage = deferred<PageResult<User>>()
    mocks.listUserFollowers
      .mockResolvedValueOnce(relation(11, 'page-1', 1, 60))
      .mockReturnValueOnce(pendingPage.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      page: number
      inputPage: string
      setPage: (nextPage: number) => void
    }

    const input = wrapper.find('input[aria-label="页码"]')
    await input.setValue('2')
    const enter = input.trigger('keyup.enter')
    const blur = input.trigger('blur')
    await Promise.all([enter, blur])
    vm.setPage(3)
    expect(vm.page).toBe(2)
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(2)
    expect(mocks.listUserFollowers).toHaveBeenLastCalledWith('1', { page: 2, size: 20 })

    pendingPage.resolve(relation(12, 'page-2', 2, 40))
    await flushPromises()
    const settledInput = wrapper.find<HTMLInputElement>('input[aria-label="页码"]')
    await settledInput.setValue('999')
    await settledInput.trigger('keyup.enter')
    await settledInput.trigger('blur')
    await flushPromises()
    await nextTick()
    expect(vm.page).toBe(2)
    expect(vm.inputPage).toBe('2')
    expect(settledInput.element.value).toBe('2')
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(2)

    await settledInput.setValue('9007199254740993')
    await settledInput.trigger('keyup.enter')
    await settledInput.trigger('blur')
    await flushPromises()

    expect(vm.page).toBe(2)
    expect(vm.inputPage).toBe('2')
    expect(settledInput.element.value).toBe('2')
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it.each([0, 1])('keeps an empty later page returnable when total shrinks to %s', async (shrunkTotal) => {
    mocks.listUserFollowers
      .mockResolvedValueOnce(relation(11, 'page-1', 1, 40))
      .mockResolvedValueOnce({ items: [], page: 2, size: 20, total: shrunkTotal })
      .mockResolvedValueOnce({ items: [], page: 1, size: 20, total: shrunkTotal })

    const wrapper = createWrapper()
    await flushPromises()
    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    const previousButton = wrapper.findAll('button').find((button) => button.text() === '上一页')
    expect(previousButton).toBeDefined()
    expect(wrapper.text()).toContain('当前页暂无粉丝')
    await previousButton!.trigger('click')
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledExactlyOnceWith('1')
    expect(mocks.listUserFollowers).toHaveBeenLastCalledWith('1', { page: 1, size: 20 })
    expect(wrapper.text()).toContain('暂无粉丝')
    expect(wrapper.text()).not.toContain('当前页暂无粉丝')
    expect(wrapper.findAll('button').some((button) => button.text() === '上一页')).toBe(false)
    wrapper.unmount()
  })

  it('does not issue profile or relation retries after unmount', async () => {
    const pendingProfile = deferred<UserProfile>()
    const pendingRelations = deferred<PageResult<User>>()
    mocks.getUserProfile.mockReturnValueOnce(pendingProfile.promise)
    mocks.listUserFollowers.mockReturnValueOnce(pendingRelations.promise)

    const wrapper = createWrapper()
    await nextTick()
    const vm = wrapper.vm as unknown as {
      retryProfile: () => void
      retryRelation: () => void
    }
    wrapper.unmount()
    vm.retryProfile()
    vm.retryRelation()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)
    expect(mocks.listUserFollowers).toHaveBeenCalledTimes(1)
  })
})

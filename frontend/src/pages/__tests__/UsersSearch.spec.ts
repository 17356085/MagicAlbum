import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import UsersSearch from '../UsersSearch.vue'
import type { PageResult, User } from '@/types'

const mocks = vi.hoisted(() => ({
  listUsers: vi.fn(),
  route: { query: {} as Record<string, unknown> },
  router: { push: vi.fn() },
}))

vi.mock('@/api/users', () => ({ listUsers: mocks.listUsers }))
vi.mock('@/utils/image', () => ({ normalizeImageUrl: (url: string | undefined) => url || '' }))
vi.mock('@/composables/time', () => ({ formatAbsoluteDate: () => '2026-10-07' }))
vi.mock('@/utils/router', () => ({
  getSingleQueryValue: (value: unknown) => Array.isArray(value) ? String(value[0] || '') : String(value || ''),
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

function result(id: number, username: string): PageResult<User> {
  return {
    items: [{ id, username, nickname: username, createdAt: '2026-10-07T00:00:00Z' }],
    page: 1,
    size: 20,
    total: 1,
  }
}

function pagedResult(page: number, total: number, username = 'user'): PageResult<User> {
  return {
    ...result(page, username),
    page,
    total,
  }
}

function createWrapper() {
  return mount(UsersSearch, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
}

describe('UsersSearch request ordering', () => {
  beforeEach(() => {
    mocks.listUsers.mockReset()
    mocks.router.push.mockReset()
    mocks.route = reactive({ query: { q: 'old', page: '1' } })
  })

  it('requests nickname matches and displays a nickname-only result', async () => {
    mocks.route.query.q = '同频'
    mocks.listUsers.mockResolvedValue({
      ...result(1, 'user-1'),
      items: [{ id: 1, username: 'user-1', nickname: '同频用户' }],
    })
    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listUsers).toHaveBeenCalledExactlyOnceWith({
      q: '同频', page: 1, size: 20, fields: 'username,nickname',
    })
    expect(wrapper.text()).toContain('同频用户')
    wrapper.unmount()
  })

  it('renders the public search page returned by the server without applying a second matching rule', async () => {
    // The repository supports SQL LIKE patterns. A second JS includes('_')
    // would hide this valid result while still displaying the server total.
    mocks.route.query.q = '_'
    mocks.listUsers.mockResolvedValue(result(1, 'alice'))
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).toContain('共 1 位用户')
    expect(wrapper.text()).not.toContain('暂无匹配用户')
    wrapper.unmount()
  })

  it('makes one request when a navigation changes both keyword and page', async () => {
    mocks.route.query.page = '3'
    mocks.listUsers.mockResolvedValue(result(1, 'old-user'))
    const wrapper = createWrapper()
    await flushPromises()
    mocks.listUsers.mockClear()

    mocks.route.query.q = 'new'
    mocks.route.query.page = '1'
    await flushPromises()

    expect(mocks.listUsers).toHaveBeenCalledExactlyOnceWith({
      q: 'new', page: 1, size: 20, fields: 'username,nickname',
    })
    wrapper.unmount()
  })

  it('keeps the current results when navigation only changes an unrelated query field', async () => {
    mocks.listUsers.mockResolvedValue(result(1, 'old-user'))
    const wrapper = createWrapper()
    await flushPromises()
    mocks.listUsers.mockClear()

    mocks.route.query = { q: 'old', page: '1', source: 'header' }
    await flushPromises()

    expect(mocks.listUsers).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('old-user')
    wrapper.unmount()
  })

  it.each(['0', '-1', '1.5', 'Infinity', 'bad', '9007199254740992'])('normalizes invalid page %s before requesting', async (page) => {
    mocks.route.query.page = page
    mocks.listUsers.mockResolvedValue(result(1, 'old-user'))
    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listUsers).toHaveBeenCalledExactlyOnceWith({
      q: 'old', page: 1, size: 20, fields: 'username,nickname',
    })
    wrapper.unmount()
  })

  it('keeps the newest result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<User>>()
    const newRequest = deferred<PageResult<User>>()
    mocks.listUsers.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.query.q = 'new'
    await nextTick()
    expect(mocks.listUsers).toHaveBeenCalledTimes(2)

    newRequest.resolve(result(2, 'newest-user'))
    await flushPromises()
    oldRequest.resolve(result(1, 'new-stale-user'))
    await flushPromises()

    expect(wrapper.text()).toContain('newest-user')
    expect(wrapper.text()).not.toContain('new-stale-user')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps loading while an older request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<User>>()
    const newRequest = deferred<PageResult<User>>()
    mocks.listUsers.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.query.q = 'new'
    await nextTick()

    oldRequest.reject(new Error('stale failure'))
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result(2, 'newest-user'))
    await flushPromises()
    expect(wrapper.text()).toContain('newest-user')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an in-flight response after unmount', async () => {
    const request = deferred<PageResult<User>>()
    mocks.listUsers.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await nextTick()
    const vm = wrapper.vm as unknown as { items: User[]; loading: boolean }
    wrapper.unmount()

    request.resolve(result(1, 'late-user'))
    await flushPromises()

    expect(vm.items).toEqual([])
    expect(vm.loading).toBe(true)
  })

  it('retries a failed request once when the retry action is repeated in one tick', async () => {
    const initialRequest = deferred<PageResult<User>>()
    const retryRequest = deferred<PageResult<User>>()
    mocks.listUsers.mockReturnValueOnce(initialRequest.promise).mockReturnValueOnce(retryRequest.promise)

    const wrapper = createWrapper()
    await nextTick()
    initialRequest.reject(new Error('temporary failure'))
    await flushPromises()

    const retryButton = wrapper.get('button')
    const firstRetry = retryButton.trigger('click')
    const secondRetry = retryButton.trigger('click')
    await Promise.all([firstRetry, secondRetry])

    expect(mocks.listUsers).toHaveBeenCalledTimes(2)
    expect(mocks.listUsers).toHaveBeenLastCalledWith({
      q: 'old', page: 1, size: 20, fields: 'username,nickname',
    })

    retryRequest.resolve(result(2, 'old-retried-user'))
    await flushPromises()
    expect(wrapper.text()).toContain('old-retried-user')
    wrapper.unmount()
  })

  it('retries the newest route after an older search failure crosses the route change', async () => {
    const oldRequest = deferred<PageResult<User>>()
    const newRequest = deferred<PageResult<User>>()
    const retryRequest = deferred<PageResult<User>>()
    mocks.listUsers
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise)
      .mockReturnValueOnce(retryRequest.promise)

    const wrapper = createWrapper()
    await nextTick()
    mocks.route.query.q = 'new'
    await nextTick()
    expect(mocks.listUsers).toHaveBeenCalledTimes(2)

    oldRequest.reject(new Error('stale failure'))
    newRequest.reject(new Error('new failure'))
    await flushPromises()

    const retryButton = wrapper.get('button')
    await retryButton.trigger('click')
    expect(mocks.listUsers).toHaveBeenCalledTimes(3)
    expect(mocks.listUsers).toHaveBeenLastCalledWith({
      q: 'new', page: 1, size: 20, fields: 'username,nickname',
    })

    retryRequest.resolve(result(3, 'new-retried-user'))
    await flushPromises()
    expect(wrapper.text()).toContain('new-retried-user')
    wrapper.unmount()
  })

  it('keeps a pager on an empty later page so the previous page remains reachable', async () => {
    mocks.route.query.page = '2'
    mocks.listUsers.mockResolvedValue({
      items: [],
      page: 2,
      size: 20,
      total: 21,
    })

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('可返回上一页查看')
    expect(wrapper.text()).toContain('上一页')
    expect(wrapper.text()).toContain('下一页')
    const previousButton = wrapper.findAll('button').find((button) => button.text() === '上一页')
    expect(previousButton).toBeDefined()
    expect(wrapper.find('input[aria-label="页码"]').exists()).toBe(true)
    mocks.router.push.mockReturnValue(new Promise(() => {}))
    await previousButton!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledExactlyOnceWith({
      name: 'users', query: { q: 'old', page: 1 },
    })
    wrapper.unmount()
  })

  it('does not skip pages when next is clicked repeatedly before navigation settles', async () => {
    mocks.listUsers.mockResolvedValue(pagedResult(1, 60, 'old-first-page-user'))
    const wrapper = createWrapper()
    await flushPromises()
    mocks.router.push.mockReturnValue(new Promise(() => {}))

    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    const firstClick = nextButton!.trigger('click')
    const secondClick = nextButton!.trigger('click')
    await Promise.all([firstClick, secondClick])

    expect(mocks.router.push).toHaveBeenCalledTimes(1)
    expect(mocks.router.push).toHaveBeenLastCalledWith({
      name: 'users', query: { q: 'old', page: 2 },
    })
    expect((wrapper.vm as unknown as { page: number }).page).toBe(1)
    wrapper.unmount()
  })

  it('keeps the route page after a rejected navigation and allows an explicit retry', async () => {
    mocks.listUsers.mockResolvedValue(pagedResult(1, 60, 'old-first-page-user'))
    mocks.router.push
      .mockRejectedValueOnce(new Error('navigation blocked'))
      .mockReturnValueOnce(new Promise(() => {}))
    const wrapper = createWrapper()
    await flushPromises()

    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect((wrapper.vm as unknown as { page: number }).page).toBe(1)
    expect(mocks.router.push).toHaveBeenCalledTimes(1)

    await nextButton!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledTimes(2)
    expect(mocks.router.push.mock.calls[1][0]).toEqual({
      name: 'users', query: { q: 'old', page: 2 },
    })
    wrapper.unmount()
  })

  it('keeps the route page after a blocked navigation result and allows retry', async () => {
    mocks.listUsers.mockResolvedValue(pagedResult(1, 60, 'old-first-page-user'))
    mocks.router.push
      .mockResolvedValueOnce({ type: 8 })
      .mockReturnValueOnce(new Promise(() => {}))
    const wrapper = createWrapper()
    await flushPromises()

    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect((wrapper.vm as unknown as { page: number }).page).toBe(1)
    await nextButton!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledTimes(2)
    expect(mocks.router.push.mock.calls[1][0]).toEqual({
      name: 'users', query: { q: 'old', page: 2 },
    })
    wrapper.unmount()
  })

  it('does not push twice when a page input submits on Enter and blur', async () => {
    mocks.listUsers.mockResolvedValue(pagedResult(1, 60, 'old-first-page-user'))
    const wrapper = createWrapper()
    await flushPromises()

    const input = wrapper.get('input[aria-label="页码"]')
    await input.setValue('2')
    await input.trigger('keyup.enter')
    await input.trigger('blur')

    expect(mocks.router.push).toHaveBeenCalledTimes(1)
    expect(mocks.router.push).toHaveBeenLastCalledWith({
      name: 'users', query: { q: 'old', page: 2 },
    })
    expect((wrapper.vm as unknown as { page: number }).page).toBe(1)
    wrapper.unmount()
  })

  it('allows Enter to retry after an input navigation failure', async () => {
    mocks.listUsers.mockResolvedValue(pagedResult(1, 60, 'old-first-page-user'))
    mocks.router.push
      .mockRejectedValueOnce(new Error('navigation blocked'))
      .mockReturnValueOnce(new Promise(() => {}))
    const wrapper = createWrapper()
    await flushPromises()

    const input = wrapper.get('input[aria-label="页码"]')
    await input.setValue('2')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect((wrapper.vm as unknown as { page: number }).page).toBe(1)
    expect(mocks.router.push).toHaveBeenCalledTimes(1)

    await input.trigger('keyup.enter')
    expect(mocks.router.push).toHaveBeenCalledTimes(2)
    expect(mocks.router.push.mock.calls[1][0]).toEqual({
      name: 'users', query: { q: 'old', page: 2 },
    })
    wrapper.unmount()
  })

  it('ignores same-page and invalid page navigation requests', async () => {
    mocks.listUsers.mockResolvedValue(pagedResult(1, 60, 'first-page-user'))
    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { setPage: (value: number) => void }

    vm.setPage(1)
    vm.setPage(1.5)
    vm.setPage(Number.MAX_SAFE_INTEGER + 1)
    vm.setPage(Number.NaN)

    expect(mocks.router.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not let an older navigation failure release a newer search navigation', async () => {
    const oldNavigation = deferred<unknown>()
    const newNavigation = deferred<unknown>()
    mocks.listUsers.mockResolvedValueOnce(pagedResult(1, 60, 'old-user'))
      .mockResolvedValueOnce(pagedResult(1, 60, 'new-user'))
    mocks.router.push.mockReturnValueOnce(oldNavigation.promise).mockReturnValueOnce(newNavigation.promise)
    const wrapper = createWrapper()
    await flushPromises()

    const nextButton = () => wrapper.findAll('button').find(button => button.text() === '下一页')!
    await nextButton().trigger('click')
    mocks.route.query.q = 'new'
    await flushPromises()
    await nextButton().trigger('click')
    expect(mocks.router.push).toHaveBeenCalledTimes(2)

    oldNavigation.reject(new Error('old navigation failed'))
    await flushPromises()
    await nextButton().trigger('click')
    expect(mocks.router.push).toHaveBeenCalledTimes(2)

    newNavigation.resolve({ type: 8 })
    await flushPromises()
    await nextButton().trigger('click')
    expect(mocks.router.push).toHaveBeenCalledTimes(3)
    expect(mocks.router.push).toHaveBeenLastCalledWith({ name: 'users', query: { q: 'new', page: 2 } })
    wrapper.unmount()
  })
})

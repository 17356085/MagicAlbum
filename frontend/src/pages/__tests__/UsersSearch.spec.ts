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
    mocks.route.query = reactive({ q: 'old', page: '1' })
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
})

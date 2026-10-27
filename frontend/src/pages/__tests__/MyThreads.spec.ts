import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MyThreads from '../MyThreads.vue'
import { useAuthStore } from '@/stores/auth'
import type { Id, PageResult, Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  listMyThreads: vi.fn(),
  deleteThread: vi.fn(),
  listSections: vi.fn(),
}))

vi.mock('@/api/my', () => ({ listMyThreads: mocks.listMyThreads, deleteThread: mocks.deleteThread }))
vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function thread(id: Id, title: string): Thread {
  return { id, title, content: `${title} 内容`, sectionId: 2, createdAt: '2026-10-07T00:00:00Z' }
}

function result(items: Thread[], page = 1, total = items.length, size = 10): PageResult<Thread> {
  return { items, page, size, total }
}

let pinia: ReturnType<typeof createPinia>
const wrappers: VueWrapper[] = []

function createWrapper(): VueWrapper {
  const wrapper = mount(MyThreads, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function authState(token = 'token-a', id: Id = 1, username = 'alice') {
  useAuthStore(pinia).applyAuthState({ accessToken: token, user: { id, username } })
}

describe('MyThreads deletion errors', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    authState()
    mocks.listMyThreads.mockReset()
    mocks.deleteThread.mockReset()
    mocks.listSections.mockReset()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.listMyThreads.mockResolvedValue(result([]))
    mocks.deleteThread.mockResolvedValue(undefined)
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('keeps the target and shows an actionable retry when deleting a thread fails', async () => {
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '帖子标题')]))
    mocks.deleteThread.mockRejectedValueOnce(new Error('network failure'))

    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await settle()

    expect(wrapper.get('[role="alert"]').text()).toContain('删除帖子失败，请检查网络后重试')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('帖子标题')
    expect(wrapper.get('[role="dialog"]').findAll('button').some((button) => button.text() === '重试')).toBe(true)
  })
})

describe('MyThreads request ordering', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    authState()
    mocks.listMyThreads.mockReset()
    mocks.deleteThread.mockReset()
    mocks.listSections.mockReset()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.listMyThreads.mockResolvedValue(result([]))
    mocks.deleteThread.mockResolvedValue(undefined)
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('keeps the newest result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await settle()
    await wrapper.get('input[aria-label="搜索我的帖子"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(2)

    newRequest.resolve(result([thread(2, '最新帖子')]))
    await settle()
    oldRequest.resolve(result([thread(1, '旧帖子')]))
    await settle()

    expect(wrapper.text()).toContain('最新帖子')
    expect(wrapper.text()).not.toContain('旧帖子')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps loading while an older request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await settle()
    await wrapper.get('input[aria-label="搜索我的帖子"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')

    oldRequest.reject(new Error('stale failure'))
    await settle()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result([thread(2, '最新帖子')]))
    await settle()
    expect(wrapper.text()).toContain('最新帖子')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an in-flight response after unmount', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { list: PageResult<Thread>; loading: boolean }
    wrapper.unmount()

    request.resolve(result([thread(1, '卸载后帖子')]))
    await settle()

    expect(vm.list.items).toEqual([])
    expect(vm.loading).toBe(true)
  })
})

describe('MyThreads session ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    authState()
    mocks.listMyThreads.mockReset()
    mocks.deleteThread.mockReset()
    mocks.listSections.mockReset()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.listMyThreads.mockResolvedValue(result([]))
    mocks.deleteThread.mockResolvedValue(undefined)
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('rejects a confirmation opened by account A after switching to account B', async () => {
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, 'A 帖子')])).mockResolvedValue(result([thread(8, 'B 帖子')]))
    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')

    authState('token-b', 2, 'bob')
    await settle()
    await (wrapper.vm as unknown as { confirmDelete: () => Promise<void> }).confirmDelete()

    expect(mocks.deleteThread).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('B 帖子')
    expect(wrapper.text()).not.toContain('A 帖子')
  })

  it('clears immediately on a storage-first token switch and accepts only the new owner response', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
    const wrapper = createWrapper()
    await settle()

    localStorage.setItem('accessToken', 'token-b')
    window.dispatchEvent(new StorageEvent('storage', { key: 'accessToken', newValue: 'token-b', storageArea: localStorage }))
    expect((wrapper.vm as unknown as { list: PageResult<Thread> }).list.items).toEqual([])

    const auth = useAuthStore(pinia)
    auth.syncAuthFromStorage()
    await settle()
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(2)

    oldRequest.resolve(result([thread(1, '旧账号帖子')]))
    await settle()
    newRequest.resolve(result([thread(2, '新账号帖子')]))
    await settle()
    expect(wrapper.text()).toContain('新账号帖子')
    expect(wrapper.text()).not.toContain('旧账号帖子')
  })

  it('invalidates every ABA epoch, including an explicit same-token auth event', async () => {
    const first = deferred<PageResult<Thread>>()
    const second = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = createWrapper()
    await settle()

    authState('token-b', 2, 'bob')
    authState('token-a', 1, 'alice')
    await settle()
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(2)

    first.resolve(result([thread(1, '旧 ABA 响应')]))
    await settle()
    expect(wrapper.text()).not.toContain('旧 ABA 响应')
    second.resolve(result([thread(3, '恢复后的 A 帖子')]))
    await settle()
    expect(wrapper.text()).toContain('恢复后的 A 帖子')

    const callsBeforeEvent = mocks.listMyThreads.mock.calls.length
    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await settle()
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(callsBeforeEvent + 1)
  })

  it('does not reread on null-to-known same-token hydration', async () => {
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const request = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReset()
    mocks.listMyThreads.mockReturnValueOnce(request.promise)
    const wrapper = createWrapper()
    await settle()

    auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')
    await settle()
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(1)
    request.resolve(result([thread(7, '同令牌帖子')]))
    await settle()
    expect(wrapper.text()).toContain('同令牌帖子')
  })

  it('keeps an existing request valid when only a failed login generation changes', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReset()
    mocks.listMyThreads.mockReturnValueOnce(request.promise)
    const wrapper = createWrapper()
    await settle()

    useAuthStore(pinia).invalidateAuthOperations()
    request.resolve(result([thread(7, '登录失败期间的帖子')]))
    await settle()
    expect(wrapper.text()).toContain('登录失败期间的帖子')
  })

  it('clears a response that completes after storage drifts without a Pinia or auth event', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(request.promise)
    const wrapper = createWrapper()
    await settle()

    localStorage.setItem('accessToken', 'token-b')
    request.resolve(result([thread(7, '漂移后的旧帖子')]))
    await settle()

    const vm = wrapper.vm as unknown as { list: PageResult<Thread>; loading: boolean }
    expect(vm.list.items).toEqual([])
    expect(vm.loading).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更，请刷新页面后重试')
    expect(wrapper.text()).not.toContain('漂移后的旧帖子')
  })

  it('rejects a delete click after storage drifts before the confirmation starts', async () => {
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '不应删除')]))
    const wrapper = createWrapper()
    await settle()

    localStorage.setItem('accessToken', 'token-b')
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await settle()

    expect(mocks.deleteThread).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更，请刷新页面后重试')
    expect(wrapper.text()).not.toContain('不应删除')
  })

  it('clears a pending confirmation when storage drifts before confirm', async () => {
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '确认前漂移')]))
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    localStorage.setItem('accessToken', 'token-b')
    await (wrapper.vm as unknown as { confirmDelete: () => Promise<void> }).confirmDelete()
    await settle()

    expect(mocks.deleteThread).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更，请刷新页面后重试')
    expect(wrapper.text()).not.toContain('确认前漂移')
  })
})

describe('MyThreads list and delete recovery', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    authState()
    mocks.listMyThreads.mockReset()
    mocks.deleteThread.mockReset()
    mocks.listSections.mockReset()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.listMyThreads.mockResolvedValue(result([]))
    mocks.deleteThread.mockResolvedValue(undefined)
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('offers a separate list retry after the initial read fails', async () => {
    mocks.listMyThreads.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(result([thread(2, '重试成功')]))
    const wrapper = createWrapper()
    await settle()

    expect(wrapper.get('[role="alert"]').text()).toContain('加载我的帖子失败')
    await wrapper.get('[role="alert"]').find('button').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('重试成功')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('removes a deleted row locally, returns from an empty last page, and keeps query filters', async () => {
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '末页帖子')], 2, 11))
      .mockResolvedValueOnce(result([], 1, 10))
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { query: { page: number; q: string; sectionId: string; sort: string } }
    vm.query.page = 2
    vm.query.q = '保留搜索'
    vm.query.sectionId = '3'
    vm.query.sort = 'createdAt'

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await settle()

    expect(mocks.deleteThread).toHaveBeenCalledWith(7)
    expect(mocks.listMyThreads).toHaveBeenLastCalledWith({ q: '保留搜索', sectionId: '3', page: 1, size: 10, sort: 'createdAt' })
    expect(vm.query.page).toBe(1)
    expect(wrapper.text()).not.toContain('末页帖子')
  })

  it('keeps a successful delete separate when its list refresh fails', async () => {
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '刷新失败的帖子')]))
      .mockRejectedValueOnce(new Error('refresh offline'))
    mocks.deleteThread.mockResolvedValueOnce(undefined)
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await settle()

    expect(mocks.deleteThread).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('刷新失败的帖子')
    expect(wrapper.get('[role="alert"]').text()).toContain('加载我的帖子失败')

    const retry = wrapper.get('[role="alert"]').find('button')
    mocks.listMyThreads.mockResolvedValueOnce(result([]))
    await retry.trigger('click')
    await settle()
    expect(mocks.deleteThread).toHaveBeenCalledTimes(1)
  })

  it('invalidates an earlier list read after delete success and refreshes current query', async () => {
    const staleRead = deferred<PageResult<Thread>>()
    const freshRead = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '待删帖子')]))
      .mockReturnValueOnce(staleRead.promise)
      .mockReturnValueOnce(freshRead.promise)
    const wrapper = createWrapper()
    await settle()
    void (wrapper.vm as unknown as { load: () => Promise<void> }).load()
    await settle()

    const deleteRequest = deferred<unknown>()
    mocks.deleteThread.mockReturnValueOnce(deleteRequest.promise)
    const vm = wrapper.vm as unknown as {
      askRemove: (id: Id) => void
      confirmDelete: () => Promise<void>
    }
    vm.askRemove(7)
    void vm.confirmDelete()
    deleteRequest.resolve(undefined)
    await settle()
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(3)

    staleRead.resolve(result([thread(7, '不应复活')]))
    await settle()
    expect(wrapper.text()).not.toContain('不应复活')
    freshRead.resolve(result([]))
    await settle()
    expect(wrapper.text()).not.toContain('待删帖子')
  })

  it('does not clamp a newly selected query using totals from the deleted row query', async () => {
    const deletion = deferred<unknown>()
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '旧筛选目标')], 1, 11))
      .mockResolvedValueOnce(result([], 4, 60))
    mocks.deleteThread.mockReturnValueOnce(deletion.promise)
    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find(button => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find(button => button.text() === '确认')!.trigger('click')

    const vm = wrapper.vm as unknown as { query: { page: number; q: string; sectionId: string; sort: string } }
    vm.query.page = 4
    vm.query.q = '新筛选'
    vm.query.sectionId = '99'
    vm.query.sort = 'createdAt'
    deletion.resolve(undefined)
    await settle()

    expect(mocks.listMyThreads).toHaveBeenLastCalledWith({ q: '新筛选', sectionId: '99', page: 4, size: 10, sort: 'createdAt' })
    expect(vm.query.page).toBe(4)
  })

  it('does not permit duplicate confirms and cancel cannot cancel an issued delete', async () => {
    const deleteRequest = deferred<unknown>()
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '双击帖子')])).mockResolvedValue(result([]))
    mocks.deleteThread.mockReturnValueOnce(deleteRequest.promise)
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    const confirm = wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!
    await confirm.trigger('click')
    await (wrapper.vm as unknown as { confirmDelete: () => Promise<void> }).confirmDelete()
    await (wrapper.vm as unknown as { cancelDelete: () => void }).cancelDelete()
    expect(mocks.deleteThread).toHaveBeenCalledTimes(1)

    deleteRequest.resolve(undefined)
    await settle()
    expect(wrapper.text()).not.toContain('双击帖子')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('does not let a late old delete failure alter the new account', async () => {
    const deleteRequest = deferred<unknown>()
    mocks.listMyThreads.mockResolvedValueOnce(result([thread(7, '旧账号待删')])).mockResolvedValue(result([thread(8, '新账号内容')]))
    mocks.deleteThread.mockReturnValueOnce(deleteRequest.promise)
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    authState('token-b', 2, 'bob')
    await settle()
    deleteRequest.reject(new Error('late failure'))
    await settle()

    expect(wrapper.text()).toContain('新账号内容')
    expect(wrapper.text()).not.toContain('删除帖子失败，请检查网络后重试')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})

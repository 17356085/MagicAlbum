import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MyPosts from '../MyPosts.vue'
import { useAuthStore } from '@/stores/auth'
import type { Id, PageResult, Post } from '@/types'

const mocks = vi.hoisted(() => ({
  listMyPosts: vi.fn(),
  deletePost: vi.fn(),
  listSections: vi.fn(),
}))

vi.mock('@/api/my', () => ({ listMyPosts: mocks.listMyPosts, deletePost: mocks.deletePost }))
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

function post(id: Id, content: string, threadId: Id = 3): Post {
  return { id, threadId, content, createdAt: '2026-10-07T00:00:00Z' }
}

function result(items: Post[], page = 1, total = items.length, size = 10): PageResult<Post> {
  return { items, page, size, total }
}

let pinia: ReturnType<typeof createPinia>
const wrappers: VueWrapper[] = []

function authState(token = 'token-a', id: Id = 1, username = 'alice'): void {
  useAuthStore(pinia).applyAuthState({ accessToken: token, user: { id, username } })
}

function createWrapper(): VueWrapper {
  const wrapper = mount(MyPosts, {
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

function resetMocks(): void {
  localStorage.clear()
  sessionStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  authState()
  mocks.listMyPosts.mockReset()
  mocks.deletePost.mockReset()
  mocks.listSections.mockReset()
  mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
  mocks.listMyPosts.mockResolvedValue(result([]))
  mocks.deletePost.mockResolvedValue(undefined)
}

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  localStorage.clear()
  sessionStorage.clear()
})

describe('MyPosts deletion errors', () => {
  beforeEach(resetMocks)

  it('keeps the target and shows an actionable retry when deleting a comment fails', async () => {
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '评论内容')]))
    mocks.deletePost.mockRejectedValueOnce(new Error('network failure'))

    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await settle()

    expect(wrapper.get('[role="alert"]').text()).toContain('删除评论失败，请检查网络后重试')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('评论内容')
    expect(wrapper.get('[role="dialog"]').findAll('button').some((button) => button.text() === '重试')).toBe(true)
  })

  it('retries the same failed delete and removes the row after success', async () => {
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '可重试评论')])).mockResolvedValueOnce(result([]))
    mocks.deletePost.mockRejectedValueOnce(new Error('network failure')).mockResolvedValueOnce(undefined)

    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await settle()
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '重试')!.trigger('click')
    await settle()

    expect(mocks.deletePost).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('可重试评论')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})

describe('MyPosts request ordering', () => {
  beforeEach(resetMocks)

  it('keeps the newest result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await settle()
    await wrapper.get('input[aria-label="搜索我的评论"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')
    expect(mocks.listMyPosts).toHaveBeenCalledTimes(2)

    newRequest.resolve(result([post(2, '最新评论')]))
    await settle()
    oldRequest.resolve(result([post(1, '旧评论')]))
    await settle()

    expect(wrapper.text()).toContain('最新评论')
    expect(wrapper.text()).not.toContain('旧评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps loading while an older request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await settle()
    await wrapper.get('input[aria-label="搜索我的评论"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')

    oldRequest.reject(new Error('stale failure'))
    await settle()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result([post(2, '最新评论')]))
    await settle()
    expect(wrapper.text()).toContain('最新评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an in-flight response after unmount', async () => {
    const request = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { list: PageResult<Post>; loading: boolean }
    wrapper.unmount()

    request.resolve(result([post(1, '卸载后评论')]))
    await settle()

    expect(vm.list.items).toEqual([])
    expect(vm.loading).toBe(true)
  })

  it('offers a separate list retry after the initial read fails', async () => {
    mocks.listMyPosts.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(result([post(2, '重试成功')]))
    const wrapper = createWrapper()
    await settle()

    expect(wrapper.get('[role="alert"]').text()).toContain('加载我的评论失败')
    await wrapper.get('[role="alert"]').find('button').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('重试成功')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('starts the private read without waiting for public sections', async () => {
    const sectionsRequest = deferred<unknown>()
    const privateRequest = deferred<PageResult<Post>>()
    mocks.listSections.mockReturnValueOnce(sectionsRequest.promise)
    mocks.listMyPosts.mockReturnValueOnce(privateRequest.promise)

    const wrapper = createWrapper()
    await settle()
    expect(mocks.listMyPosts).toHaveBeenCalledTimes(1)
    expect(mocks.listMyPosts).toHaveBeenCalledWith({ q: '', sectionId: undefined, page: 1, size: 10, sort: 'createdAt' })

    privateRequest.resolve(result([post(2, '无需等待分区')]))
    await settle()
    expect(wrapper.text()).toContain('无需等待分区')

    sectionsRequest.resolve({ items: [], page: 1, size: 200, total: 0 })
    await settle()
  })
})

describe('MyPosts session ownership', () => {
  beforeEach(resetMocks)

  it('rejects a confirmation after the stored token changes before confirm', async () => {
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '确认前漂移')]))
    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')

    localStorage.setItem('accessToken', 'token-b')
    await (wrapper.vm as unknown as { confirmDelete: () => Promise<void> }).confirmDelete()
    await settle()

    expect(mocks.deletePost).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更，请刷新页面后重试')
    expect(wrapper.text()).not.toContain('确认前漂移')
  })

  it('invalidates a late list read on an explicit same-token auth event', async () => {
    const first = deferred<PageResult<Post>>()
    const second = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const wrapper = createWrapper()
    await settle()

    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await settle()
    expect(mocks.listMyPosts).toHaveBeenCalledTimes(2)

    first.resolve(result([post(1, '旧事件响应')]))
    await settle()
    expect(wrapper.text()).not.toContain('旧事件响应')
    second.resolve(result([post(2, '事件后新评论')]))
    await settle()
    expect(wrapper.text()).toContain('事件后新评论')
  })

  it('does not let a late old-account delete completion trigger a follow-up read', async () => {
    const deleteRequest = deferred<unknown>()
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '旧账号待删')])).mockResolvedValue(result([post(8, '新账号内容')]))
    mocks.deletePost.mockReturnValueOnce(deleteRequest.promise)
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    authState('token-b', 2, 'bob')
    await settle()
    const readsAfterSwitch = mocks.listMyPosts.mock.calls.length

    deleteRequest.resolve(undefined)
    await settle()

    expect(mocks.deletePost).toHaveBeenCalledTimes(1)
    expect(mocks.listMyPosts).toHaveBeenCalledTimes(readsAfterSwitch)
    expect(wrapper.text()).toContain('新账号内容')
    expect(wrapper.text()).not.toContain('删除评论失败，请检查网络后重试')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})

describe('MyPosts list and delete recovery', () => {
  beforeEach(resetMocks)

  it('removes a deleted row locally, returns from an empty last page, and keeps query filters', async () => {
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '末页评论')], 2, 11)).mockResolvedValueOnce(result([], 1, 10))
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { query: { page: number; q: string; sectionId: string; sort: string } }
    vm.query.page = 2
    vm.query.q = '保留搜索'
    vm.query.sectionId = '3'
    vm.query.sort = 'updatedAt'

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await settle()

    expect(mocks.deletePost).toHaveBeenCalledWith(7)
    expect(mocks.listMyPosts).toHaveBeenLastCalledWith({ q: '保留搜索', sectionId: '3', page: 1, size: 10, sort: 'updatedAt' })
    expect(vm.query.page).toBe(1)
    expect(wrapper.text()).not.toContain('末页评论')
  })

  it('does not clamp a newly selected query using totals from the deleted row query', async () => {
    const deletion = deferred<unknown>()
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '旧筛选目标')], 1, 11)).mockResolvedValueOnce(result([], 4, 60))
    mocks.deletePost.mockReturnValueOnce(deletion.promise)
    const wrapper = createWrapper()
    await settle()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')

    const vm = wrapper.vm as unknown as { query: { page: number; q: string; sectionId: string; sort: string } }
    vm.query.page = 4
    vm.query.q = '新筛选'
    vm.query.sectionId = '99'
    vm.query.sort = 'updatedAt'
    deletion.resolve(undefined)
    await settle()

    expect(mocks.listMyPosts).toHaveBeenLastCalledWith({ q: '新筛选', sectionId: '99', page: 4, size: 10, sort: 'updatedAt' })
    expect(vm.query.page).toBe(4)
  })

  it('invalidates an earlier list read after delete success', async () => {
    const staleRead = deferred<PageResult<Post>>()
    const freshRead = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '待删评论')])).mockReturnValueOnce(staleRead.promise).mockReturnValueOnce(freshRead.promise)
    const wrapper = createWrapper()
    await settle()
    void (wrapper.vm as unknown as { load: () => Promise<void> }).load()
    await settle()

    const deleteRequest = deferred<unknown>()
    mocks.deletePost.mockReturnValueOnce(deleteRequest.promise)
    const vm = wrapper.vm as unknown as { askRemove: (id: Id) => void; confirmDelete: () => Promise<void> }
    vm.askRemove(7)
    void vm.confirmDelete()
    deleteRequest.resolve(undefined)
    await settle()
    expect(mocks.listMyPosts).toHaveBeenCalledTimes(3)

    staleRead.resolve(result([post(7, '不应复活')]))
    await settle()
    expect(wrapper.text()).not.toContain('不应复活')
    freshRead.resolve(result([]))
    await settle()
    expect(wrapper.text()).not.toContain('待删评论')
  })

  it('does not permit duplicate confirms and cancel cannot cancel an issued delete', async () => {
    const deleteRequest = deferred<unknown>()
    mocks.listMyPosts.mockResolvedValueOnce(result([post(7, '双击评论')])).mockResolvedValue(result([]))
    mocks.deletePost.mockReturnValueOnce(deleteRequest.promise)
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    const confirm = wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!
    await confirm.trigger('click')
    await (wrapper.vm as unknown as { confirmDelete: () => Promise<void> }).confirmDelete()
    await (wrapper.vm as unknown as { cancelDelete: () => void }).cancelDelete()
    expect(mocks.deletePost).toHaveBeenCalledTimes(1)

    deleteRequest.resolve(undefined)
    await settle()
    expect(wrapper.text()).not.toContain('双击评论')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})

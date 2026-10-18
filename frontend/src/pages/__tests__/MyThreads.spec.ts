import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyThreads from '../MyThreads.vue'
import type { PageResult, Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  listMyThreads: vi.fn(),
  deleteThread: vi.fn(),
  listSections: vi.fn(),
}))

vi.mock('@/api/my', () => ({ listMyThreads: mocks.listMyThreads, deleteThread: mocks.deleteThread }))
vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))

function createWrapper() {
  return mount(MyThreads, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function result(id: number, title: string): PageResult<Thread> {
  return {
    items: [{ id, title, content: `${title} 内容`, sectionId: 2, createdAt: '2026-10-07T00:00:00Z' }],
    page: 1,
    size: 10,
    total: 1,
  }
}

describe('MyThreads deletion errors', () => {
  beforeEach(() => {
    mocks.listMyThreads.mockReset()
    mocks.deleteThread.mockReset()
    mocks.listSections.mockReset()
  })

  it('shows an actionable error when deleting a thread fails', async () => {
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.listMyThreads.mockResolvedValue({
      items: [{ id: 7, title: '帖子标题', content: '帖子内容', sectionId: 2, createdAt: '2026-10-07T00:00:00Z' }],
      page: 1,
      size: 10,
      total: 1,
    })
    mocks.deleteThread.mockRejectedValueOnce(new Error('network failure'))

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('删除帖子失败，请检查网络后重试')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})

describe('MyThreads request ordering', () => {
  beforeEach(() => {
    mocks.listMyThreads.mockReset()
    mocks.deleteThread.mockReset()
    mocks.listSections.mockReset()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
  })

  it('keeps the newest result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('input[aria-label="搜索我的帖子"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')
    expect(mocks.listMyThreads).toHaveBeenCalledTimes(2)

    newRequest.resolve(result(2, '最新帖子'))
    await flushPromises()
    oldRequest.resolve(result(1, '旧帖子'))
    await flushPromises()

    expect(wrapper.text()).toContain('最新帖子')
    expect(wrapper.text()).not.toContain('旧帖子')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps loading while an older request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('input[aria-label="搜索我的帖子"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')

    oldRequest.reject(new Error('stale failure'))
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result(2, '最新帖子'))
    await flushPromises()
    expect(wrapper.text()).toContain('最新帖子')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an in-flight response after unmount', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listMyThreads.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { list: PageResult<Thread>; loading: boolean }
    wrapper.unmount()

    request.resolve(result(1, '卸载后帖子'))
    await flushPromises()

    expect(vm.list.items).toEqual([])
    expect(vm.loading).toBe(true)
  })
})

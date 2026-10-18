import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyPosts from '../MyPosts.vue'
import type { PageResult, Post } from '@/types'

const mocks = vi.hoisted(() => ({
  listMyPosts: vi.fn(),
  deletePost: vi.fn(),
  listSections: vi.fn(),
}))

vi.mock('@/api/my', () => ({ listMyPosts: mocks.listMyPosts, deletePost: mocks.deletePost }))
vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))

function createWrapper() {
  return mount(MyPosts, {
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

function result(id: number, content: string): PageResult<Post> {
  return {
    items: [{ id, threadId: 3, content, createdAt: '2026-10-07T00:00:00Z' }],
    page: 1,
    size: 10,
    total: 1,
  }
}

describe('MyPosts deletion errors', () => {
  beforeEach(() => {
    mocks.listMyPosts.mockReset()
    mocks.deletePost.mockReset()
    mocks.listSections.mockReset()
  })

  it('shows an actionable error when deleting a comment fails', async () => {
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
    mocks.listMyPosts.mockResolvedValue({
      items: [{ id: 7, threadId: 3, content: '评论内容', createdAt: '2026-10-07T00:00:00Z' }],
      page: 1,
      size: 10,
      total: 1,
    })
    mocks.deletePost.mockRejectedValueOnce(new Error('network failure'))

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '删除')!.trigger('click')
    await wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === '确认')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('删除评论失败，请检查网络后重试')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
})

describe('MyPosts request ordering', () => {
  beforeEach(() => {
    mocks.listMyPosts.mockReset()
    mocks.deletePost.mockReset()
    mocks.listSections.mockReset()
    mocks.listSections.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
  })

  it('keeps the newest result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('input[aria-label="搜索我的评论"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')
    expect(mocks.listMyPosts).toHaveBeenCalledTimes(2)

    newRequest.resolve(result(2, '最新评论'))
    await flushPromises()
    oldRequest.resolve(result(1, '旧评论'))
    await flushPromises()

    expect(wrapper.text()).toContain('最新评论')
    expect(wrapper.text()).not.toContain('旧评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps loading while an older request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('input[aria-label="搜索我的评论"]').setValue('new')
    await wrapper.get('button.ui-button-primary').trigger('click')

    oldRequest.reject(new Error('stale failure'))
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result(2, '最新评论'))
    await flushPromises()
    expect(wrapper.text()).toContain('最新评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an in-flight response after unmount', async () => {
    const request = deferred<PageResult<Post>>()
    mocks.listMyPosts.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { list: PageResult<Post>; loading: boolean }
    wrapper.unmount()

    request.resolve(result(1, '卸载后评论'))
    await flushPromises()

    expect(vm.list.items).toEqual([])
    expect(vm.loading).toBe(true)
  })
})

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyPosts from '../MyPosts.vue'

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

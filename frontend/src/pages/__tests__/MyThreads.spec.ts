import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyThreads from '../MyThreads.vue'

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

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SectionsList from '../SectionsList.vue'

const mocks = vi.hoisted(() => ({
  listSections: vi.fn(),
  listThreads: vi.fn(),
}))

vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))
vi.mock('@/api/threads', () => ({ listThreads: mocks.listThreads }))
vi.mock('@/utils/image', () => ({ normalizeImageUrl: (url: string | undefined) => url || '' }))
vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: () => ({}),
  renderInlineMarkdown: (_renderer: unknown, value: string | undefined) => value || '',
}))

function createWrapper() {
  return mount(SectionsList, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
}

describe('SectionsList preview states', () => {
  beforeEach(() => {
    mocks.listSections.mockReset()
    mocks.listThreads.mockReset()
  })

  it('shows a retryable section error instead of the empty state', async () => {
    mocks.listSections.mockResolvedValueOnce({
      items: [{ id: 1, name: '阅读', slug: 'reading', description: '阅读讨论' }],
      page: 1,
      size: 20,
      total: 1,
    })
    mocks.listThreads
      .mockRejectedValueOnce(new Error('network failure'))
      .mockResolvedValueOnce({ items: [], page: 1, size: 4, total: 0 })

    const wrapper = createWrapper()
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('该分区帖子加载失败')
    expect(alert.text()).toContain('重试')
    expect(wrapper.text()).not.toContain('暂无帖子')

    await alert.get('button').trigger('click')
    await flushPromises()

    expect(mocks.listThreads).toHaveBeenLastCalledWith({ sectionId: 1, page: 1, size: 4 })
    expect(wrapper.text()).toContain('暂无帖子')
  })
})

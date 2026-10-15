import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MyBookmarks from '../MyBookmarks.vue'
const listBookmarksMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/bookmarks', () => ({
  listBookmarks: listBookmarksMock,
}))

function createWrapper() {
  return mount(MyBookmarks, {
    global: {
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="String(to)"><slot /></a>',
        },
      },
    },
  })
}

describe('MyBookmarks', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows loading then an explicit empty state', async () => {
    let resolveBookmarks!: (value: { items: never[]; page: number; size: number; total: number }) => void
    listBookmarksMock.mockReturnValueOnce(new Promise((resolve) => { resolveBookmarks = resolve }))
    const wrapper = createWrapper()

    expect(wrapper.get('[role="status"]').text()).toContain('正在加载收藏')
    resolveBookmarks({ items: [], page: 1, size: 10, total: 0 })
    await flushPromises()

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无收藏')
    expect(listBookmarksMock).toHaveBeenCalledWith({ page: 1, size: 10 })
  })

  it('shows a retryable failure and loads the page after retry', async () => {
    listBookmarksMock
      .mockRejectedValueOnce(new Error('network failure'))
      .mockResolvedValueOnce({
        items: [{ threadId: 42, title: '重试后显示', sectionName: '测试', bookmarkedAt: '2026-10-07T00:00:00Z' }],
        page: 1,
        size: 10,
        total: 1,
      })
    const wrapper = createWrapper()
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('收藏加载失败')
    await alert.get('button').trigger('click')
    await flushPromises()

    expect(listBookmarksMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('重试后显示')
  })

  it('links titles to the thread and requests the next page', async () => {
    listBookmarksMock
      .mockResolvedValueOnce({
        items: [{ threadId: 42, title: '第一页帖子', sectionName: '测试', bookmarkedAt: '2026-10-07T00:00:00Z' }],
        page: 1,
        size: 10,
        total: 11,
      })
      .mockResolvedValueOnce({
        items: [{ threadId: 43, title: '第二页帖子', sectionName: '测试', bookmarkedAt: '2026-10-06T00:00:00Z' }],
        page: 2,
        size: 10,
        total: 11,
      })
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('a[href="/threads/42"]').text()).toContain('第一页帖子')
    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await flushPromises()

    expect(listBookmarksMock).toHaveBeenLastCalledWith({ page: 2, size: 10 })
    expect(wrapper.get('a[href="/threads/43"]').text()).toContain('第二页帖子')
  })
})

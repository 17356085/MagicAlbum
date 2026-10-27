import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MyBookmarks from '../MyBookmarks.vue'
import { useAuthStore } from '@/stores/auth'

const transportGetMock = vi.hoisted(() => vi.fn())
const transportPostMock = vi.hoisted(() => vi.fn())
const transportDeleteMock = vi.hoisted(() => vi.fn())

// Keep the real bookmarks API and pagination decoder in this integration test.
// Only the transport boundary is replaced so malformed wire data crosses the
// same API code path as a browser request.
vi.mock('@/api/client', () => ({
  default: {
    get: transportGetMock,
    post: transportPostMock,
    delete: transportDeleteMock,
  },
}))

let pinia: ReturnType<typeof createPinia>
const wrappers: VueWrapper[] = []

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function createWrapper(vueErrors: unknown[]): VueWrapper {
  const wrapper = mount(MyBookmarks, {
    global: {
      plugins: [pinia],
      config: {
        errorHandler(error) {
          vueErrors.push(error)
        },
      },
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="String(to)"><slot /></a>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

function bookmarkRow(threadId: number, title: string): { threadId: number; title: string; sectionName: string; bookmarkedAt: string } {
  return { threadId, title, sectionName: '同频', bookmarkedAt: 'invalid-date' }
}

describe('MyBookmarks bookmark row contract', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore(pinia).applyAuthState({
      accessToken: 'token-a',
      user: { id: 1, username: 'alice' },
    })
    transportGetMock.mockReset()
    transportPostMock.mockReset()
    transportDeleteMock.mockReset()
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('shows a localized retry error for a null row without malformed links or Vue render errors', async () => {
    transportGetMock
      .mockResolvedValueOnce({
        data: {
          items: [null],
          page: 1,
          size: 10,
          total: 1,
        },
      })
      .mockResolvedValueOnce({
        data: {
          items: [{ threadId: 42, title: '重试后显示', sectionName: '同频', bookmarkedAt: 'invalid-date' }],
          page: 1,
          size: 10,
          total: 1,
        },
      })

    const vueErrors: unknown[] = []
    const wrapper = createWrapper(vueErrors)
    await settle()

    expect(vueErrors).toEqual([])
    expect(wrapper.get('[role="alert"]').text()).toContain('收藏加载失败，请稍后重试')
    expect(wrapper.find('a[href="/threads/null"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/threads/undefined"]').exists()).toBe(false)

    await wrapper.get('[role="alert"] button').trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('a[href="/threads/42"]').text()).toContain('重试后显示')
    expect(transportGetMock).toHaveBeenCalledTimes(2)
    expect(transportGetMock).toHaveBeenLastCalledWith('/users/me/bookmarks', { params: { page: 1, size: 10 } })
  })

  it('enables the next page from a full unknown first page without inventing a total', async () => {
    const firstPage = Array.from({ length: 10 }, (_, index) => bookmarkRow(index + 1, `第一页-${index + 1}`))
    transportGetMock
      .mockResolvedValueOnce({ data: firstPage })
      .mockResolvedValueOnce({ data: [bookmarkRow(11, '未知总数的第二页')] })

    const vueErrors: unknown[] = []
    const wrapper = createWrapper(vueErrors)
    await settle()

    const next = wrapper.findAll('button').find((button) => button.text().trim() === '下一页')
    expect(next).toBeDefined()
    await next!.trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(transportGetMock).toHaveBeenCalledTimes(2)
    const nav = wrapper.get('[aria-label="收藏翻页"]')
    expect(nav.text()).toContain('第 2 页')
    expect(nav.text()).toContain('暂未获取总数')
    expect(nav.find('input').exists()).toBe(false)
    expect(nav.text()).not.toContain('共')
    expect(wrapper.get('a[href="/threads/11"]').text()).toContain('未知总数的第二页')
    const nextAfterPage = nav.findAll('button').find((button) => button.text().trim() === '下一页')
    const prevAfterPage = nav.findAll('button').find((button) => button.text().trim() === '上一页')
    expect(nextAfterPage?.element.disabled).toBe(true)
    expect(prevAfterPage?.element.disabled).toBe(false)
  })

  it.each([
    ['legacy bare-array row', [bookmarkRow(11, '第二页数组')], '第二页数组'],
    ['object page without total', { items: [bookmarkRow(12, '第二页无总数')], page: 2, size: 10 }, '第二页无总数'],
    ['legacy bare-array empty page', [], null],
  ])('keeps page 2 local for %s and only reads page 1 on user navigation', async (_label, secondPage, expectedTitle) => {
    const firstPage = {
      items: [bookmarkRow(1, '第一页权威')],
      page: 1,
      size: 10,
      total: 21,
    }
    transportGetMock
      .mockResolvedValueOnce({ data: firstPage })
      .mockResolvedValueOnce({ data: secondPage })
      .mockResolvedValueOnce({ data: firstPage })

    const vueErrors: unknown[] = []
    const wrapper = createWrapper(vueErrors)
    await settle()

    const next = wrapper.findAll('button').find((button) => button.text().trim() === '下一页')
    expect(next).toBeDefined()
    await next!.trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(transportGetMock).toHaveBeenCalledTimes(2)
    const nav = wrapper.get('[aria-label="收藏翻页"]')
    expect(nav.text()).toContain('第 2 页')
    expect(nav.text()).toContain('暂未获取总数')
    expect(nav.find('input').exists()).toBe(false)
    expect(nav.text()).not.toContain('共')
    expect(nav.findAll('button').find((button) => button.text().trim() === '下一页')?.element.disabled).toBe(true)
    expect(nav.findAll('button').find((button) => button.text().trim() === '上一页')?.element.disabled).toBe(false)
    if (expectedTitle === null) {
      expect(wrapper.text()).toContain('本页暂无收藏')
    } else {
      expect(wrapper.text()).toContain(expectedTitle)
    }
    expect(wrapper.text()).not.toContain('第一页权威')

    await nav.findAll('button').find((button) => button.text().trim() === '上一页')!.trigger('click')
    await settle()

    expect(transportGetMock).toHaveBeenCalledTimes(3)
    expect(wrapper.get('a[href="/threads/1"]').text()).toContain('第一页权威')
    expect(wrapper.find('[aria-label="收藏翻页"]').exists()).toBe(false)
  })
})

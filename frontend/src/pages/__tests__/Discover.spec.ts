import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Discover from '../Discover.vue'
import type { PageResult, Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  listThreads: vi.fn(),
  getUserProfile: vi.fn(),
  route: { query: {} as Record<string, unknown> },
  router: { push: vi.fn(), replace: vi.fn() },
}))

vi.mock('@/api/threads', () => ({ listThreads: mocks.listThreads }))
vi.mock('@/api/users', () => ({ getUserProfile: mocks.getUserProfile }))
vi.mock('@/utils/image', () => ({
  normalizeImageUrl: (url: string | undefined) => url || '',
}))
vi.mock('@/utils/markdownBase', () => ({
  createBaseMarkdownRenderer: () => ({}),
  renderInlineMarkdown: (_renderer: unknown, text: string | undefined) => text || '',
  renderMarkdown: (_renderer: unknown, text: string | undefined) => text || '',
}))
vi.mock('@/utils/router', () => ({
  getSingleQueryValue: (value: unknown) => Array.isArray(value) ? String(value[0] || '') : String(value || ''),
}))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}))

describe('Discover', () => {
  beforeEach(() => {
    mocks.listThreads.mockReset()
    mocks.getUserProfile.mockReset()
    mocks.router.push.mockReset()
    mocks.router.replace.mockReset()
    mocks.route = reactive({ query: { q: 'search', tag: 'tag', page: '2' } })
  })

  it('ends loading when the thread list resolves without fetching author profiles', async () => {
    let resolveList!: (result: PageResult<Thread>) => void
    mocks.listThreads.mockReturnValueOnce(new Promise<PageResult<Thread>>((resolve) => {
      resolveList = resolve
    }))
    mocks.getUserProfile.mockReturnValue(new Promise(() => {}))

    const wrapper = mount(Discover, {
      global: {
        stubs: {
          RouterLink: { template: '<a><slot /></a>' },
        },
      },
    })

    await nextTick()
    expect(wrapper.text()).toContain('正在加载...')

    resolveList({
      items: [{
        id: 7,
        title: 'Thread title',
        content: 'Thread content',
        authorId: 42,
        authorUsername: 'fallback-user',
        authorNickname: 'DTO nickname',
        authorAvatarUrl: '/avatar.png',
        sectionId: 3,
        sectionName: 'Section',
        createdAt: '2026-10-07T00:00:00Z',
      }, {
        id: 8,
        title: 'Fallback thread',
        content: 'Fallback content',
        authorId: 43,
        authorUsername: 'fallback-user',
        sectionId: 3,
        sectionName: 'Section',
        createdAt: '2026-10-06T00:00:00Z',
      }],
      page: 2,
      size: 10,
      total: 21,
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('正在加载...')
    expect(wrapper.text()).toContain('DTO nickname')
    expect(wrapper.text()).toContain('fallback-user')
    expect(wrapper.text()).toContain('搜索关键字：search')
    expect(wrapper.text()).toContain('标签：#tag')
    expect(wrapper.findAll('img')[0].attributes('src')).toBe('/avatar.png')
    expect(wrapper.findAll('img')[1].attributes('src')).toBe('https://api.dicebear.com/7.x/initials/svg?seed=fallback-user')
    expect(mocks.listThreads).toHaveBeenCalledWith({
      q: 'search',
      tag: 'tag',
      page: 2,
      size: 10,
      sectionId: undefined,
    })
    expect(mocks.getUserProfile).not.toHaveBeenCalled()
  })

  it('keeps the newest filter result when an older request finishes later', async () => {
    let resolveOld!: (result: PageResult<Thread>) => void
    let resolveNew!: (result: PageResult<Thread>) => void
    mocks.listThreads
      .mockReturnValueOnce(new Promise<PageResult<Thread>>((resolve) => { resolveOld = resolve }))
      .mockReturnValueOnce(new Promise<PageResult<Thread>>((resolve) => { resolveNew = resolve }))
    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await nextTick()
    mocks.route.query.q = 'new-filter'
    await nextTick()
    expect(mocks.listThreads).toHaveBeenCalledTimes(2)

    resolveNew({ items: [{ id: 2, title: 'Newest result', createdAt: '2026-10-07T00:00:00Z' }], page: 1, size: 10, total: 1 })
    await flushPromises()
    resolveOld({ items: [{ id: 1, title: 'Stale result', createdAt: '2026-10-06T00:00:00Z' }], page: 1, size: 10, total: 1 })
    await flushPromises()

    expect(wrapper.text()).toContain('Newest result')
    expect(wrapper.text()).not.toContain('Stale result')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('offers a retry after a list request fails', async () => {
    mocks.listThreads
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({
        items: [{ id: 9, title: '恢复后的帖子', content: '正文', createdAt: '2026-10-08T00:00:00Z' }],
        page: 2,
        size: 10,
        total: 1,
      })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('加载帖子失败')
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(mocks.listThreads).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('恢复后的帖子')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it.each(['0', '-3', '1.5', 'Infinity', '9007199254740992'])('normalizes invalid route page %s to the first page', async (routePage) => {
    mocks.route.query = { q: '', tag: '', page: routePage }
    mocks.listThreads.mockResolvedValue({ items: [], page: 1, size: 10, total: 0 })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mocks.listThreads).toHaveBeenCalledWith({
      q: '',
      tag: '',
      page: 1,
      size: 10,
      sectionId: undefined,
    })
    wrapper.unmount()
  })

  it('ignores an unrelated query replacement when tracked route values stay the same', async () => {
    mocks.listThreads.mockResolvedValue({ items: [], page: 2, size: 10, total: 20 })
    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(mocks.listThreads).toHaveBeenCalledTimes(1)

    mocks.route.query = { q: 'search', tag: 'tag', page: '2', unrelated: 'ignored' }
    await nextTick()
    await flushPromises()

    expect(mocks.listThreads).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('coalesces simultaneous route query changes into one request', async () => {
    mocks.listThreads.mockResolvedValue({ items: [], page: 2, size: 10, total: 20 })
    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(mocks.listThreads).toHaveBeenCalledTimes(1)

    mocks.route.query.q = 'new-search'
    mocks.route.query.page = '3'
    mocks.route.query.tag = 'new-tag'
    mocks.route.query.sectionId = '9'
    await nextTick()
    await flushPromises()

    expect(mocks.listThreads).toHaveBeenCalledTimes(2)
    expect(mocks.listThreads).toHaveBeenLastCalledWith({
      q: 'new-search',
      tag: 'new-tag',
      page: 3,
      size: 10,
      sectionId: 9,
    })
    wrapper.unmount()
  })

  it('redirects an out-of-range empty page to the last valid route page', async () => {
    mocks.route.query = { q: 'search', tag: 'tag', page: '3', sectionId: '7', keep: 'yes' }
    mocks.listThreads
      .mockResolvedValueOnce({ items: [], page: 3, size: 10, total: 11 })
      .mockResolvedValueOnce({
        items: [{ id: 12, title: 'Last valid page', createdAt: '2026-10-08T00:00:00Z' }],
        page: 2,
        size: 10,
        total: 11,
      })
    mocks.router.replace.mockImplementation(async (location: { query: Record<string, unknown> }) => {
      mocks.route.query = location.query
    })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mocks.router.replace).toHaveBeenCalledTimes(1)
    expect(mocks.router.replace).toHaveBeenCalledWith({
      name: 'discover',
      query: { q: 'search', tag: 'tag', page: 2, sectionId: '7', keep: 'yes' },
    })
    expect(mocks.listThreads).toHaveBeenCalledTimes(2)
    expect(mocks.listThreads).toHaveBeenLastCalledWith({
      q: 'search',
      tag: 'tag',
      page: 2,
      size: 10,
      sectionId: 7,
    })
    expect(wrapper.text()).toContain('Last valid page')
    wrapper.unmount()
  })

  it('returns to page one when an out-of-range empty response has no items', async () => {
    mocks.route.query = { q: 'empty', tag: 'tag', page: '4', sectionId: '8' }
    mocks.listThreads
      .mockResolvedValueOnce({ items: [], page: 4, size: 10, total: 0 })
      .mockResolvedValueOnce({ items: [], page: 1, size: 10, total: 0 })
    mocks.router.replace.mockImplementation(async (location: { query: Record<string, unknown> }) => {
      mocks.route.query = location.query
    })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mocks.router.replace).toHaveBeenCalledWith({
      name: 'discover',
      query: { q: 'empty', tag: 'tag', page: 1, sectionId: '8' },
    })
    expect(mocks.listThreads).toHaveBeenCalledTimes(2)
    expect(mocks.listThreads).toHaveBeenLastCalledWith({
      q: 'empty',
      tag: 'tag',
      page: 1,
      size: 10,
      sectionId: 8,
    })
    expect(wrapper.text()).toContain('暂无帖子')
    wrapper.unmount()
  })

  it('keeps a legal empty last page without replacing the route', async () => {
    mocks.route.query = { q: 'search', tag: 'tag', page: '2' }
    mocks.listThreads.mockResolvedValueOnce({ items: [], page: 2, size: 10, total: 11 })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mocks.router.replace).not.toHaveBeenCalled()
    expect(mocks.listThreads).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('暂无帖子')
    wrapper.unmount()
  })

  it('does not redirect from a stale out-of-range response after the filter changes', async () => {
    mocks.route.query = { q: 'old', tag: '', page: '3' }
    let resolveOld!: (result: PageResult<Thread>) => void
    mocks.listThreads
      .mockReturnValueOnce(new Promise<PageResult<Thread>>((resolve) => { resolveOld = resolve }))
      .mockResolvedValueOnce({
        items: [{ id: 14, title: 'New filter result', createdAt: '2026-10-09T00:00:00Z' }],
        page: 1,
        size: 10,
        total: 1,
      })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await nextTick()
    mocks.route.query = { q: 'new', tag: '', page: '1' }
    await flushPromises()

    resolveOld({ items: [], page: 3, size: 10, total: 11 })
    await flushPromises()

    expect(mocks.router.replace).not.toHaveBeenCalled()
    expect(mocks.listThreads).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('New filter result')
    wrapper.unmount()
  })

  it('shows a retryable error when fallback navigation rejects without retrying the page', async () => {
    mocks.route.query = { q: 'search', tag: 'tag', page: '3' }
    mocks.listThreads.mockResolvedValueOnce({ items: [], page: 3, size: 10, total: 11 })
    mocks.router.replace.mockRejectedValueOnce(new Error('navigation cancelled'))

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mocks.router.replace).toHaveBeenCalledTimes(1)
    expect(mocks.listThreads).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('无法返回有效页，请重试')
    expect(wrapper.get('[role="alert"] button').text()).toContain('重试')
    wrapper.unmount()
  })

  it('treats a resolved navigation failure as a retryable fallback error', async () => {
    mocks.route.query = { q: 'search', tag: 'tag', page: '3' }
    mocks.listThreads.mockResolvedValueOnce({ items: [], page: 3, size: 10, total: 11 })
    mocks.router.replace.mockResolvedValueOnce({ type: 4 })

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()

    expect(mocks.router.replace).toHaveBeenCalledTimes(1)
    expect(mocks.listThreads).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('无法返回有效页，请重试')
    wrapper.unmount()
  })

  it('does not let a failed stale fallback navigation overwrite a newer route result', async () => {
    mocks.route.query = { q: 'old', tag: '', page: '3' }
    mocks.listThreads
      .mockResolvedValueOnce({ items: [], page: 3, size: 10, total: 11 })
      .mockResolvedValueOnce({
        items: [{ id: 15, title: 'Newest filter result', createdAt: '2026-10-10T00:00:00Z' }],
        page: 1,
        size: 10,
        total: 1,
      })
    let rejectReplace!: (reason?: unknown) => void
    mocks.router.replace.mockReturnValueOnce(new Promise((_resolve, reject) => {
      rejectReplace = reject
    }))

    const wrapper = mount(Discover, {
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    await flushPromises()
    expect(mocks.router.replace).toHaveBeenCalledTimes(1)

    mocks.route.query = { q: 'new', tag: '', page: '1' }
    await flushPromises()
    rejectReplace(new Error('navigation cancelled'))
    await flushPromises()

    expect(mocks.listThreads).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('Newest filter result')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

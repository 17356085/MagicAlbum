import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Discover from '../Discover.vue'
import type { PageResult, Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  listThreads: vi.fn(),
  getUserProfile: vi.fn(),
  route: { query: {} as Record<string, unknown> },
  router: { push: vi.fn() },
}))

vi.mock('@/api/threads', () => ({ listThreads: mocks.listThreads }))
vi.mock('@/api/users', () => ({ getUserProfile: mocks.getUserProfile }))
vi.mock('@/utils/image', () => ({
  normalizeImageUrl: (url: string | undefined) => url || '',
}))
vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: () => ({}),
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
    mocks.route.query = reactive({ q: 'search', tag: 'tag', page: '2' })
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
})

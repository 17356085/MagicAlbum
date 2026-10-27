import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Ranking from '../Ranking.vue'
import type { Id, PageResult, Section, Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  listSections: vi.fn(),
  listThreadRanking: vi.fn(),
  route: { query: {} as Record<string, unknown> },
  router: { push: vi.fn() },
}))

vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))
vi.mock('@/api/threads', () => ({ listThreadRanking: mocks.listThreadRanking }))
vi.mock('@/utils/image', () => ({ normalizeImageUrl: (value: string | undefined) => value || '' }))
vi.mock('@/utils/router', () => ({
  getSingleQueryValue: (value: unknown) => Array.isArray(value) ? String(value[0] || '') : String(value || ''),
}))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}))

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

function sectionPage(items: Section[] = [{ id: 7, name: '游戏' }]): PageResult<Section> {
  return { items, page: 1, size: 50, total: items.length }
}

function thread(id: Id, title: string): Thread {
  return {
    id,
    title,
    content: `${title} 正文`,
    sectionId: 7,
    sectionName: '游戏',
    authorId: 9,
    authorUsername: 'alice',
    createdAt: '2026-10-07T00:00:00Z',
  }
}

function rankingPage(
  items: Thread[] = [thread(1, '排行帖子')],
  page = 1,
  total = items.length,
  size = 10,
): PageResult<Thread> {
  return { items, page, size, total }
}

function createWrapper(): VueWrapper {
  return mount(Ranking, {
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function resetMocks(): void {
  mocks.listSections.mockReset().mockResolvedValue(sectionPage())
  mocks.listThreadRanking.mockReset().mockResolvedValue(rankingPage())
  mocks.router.push.mockReset()
  mocks.route = reactive({ query: {} })
}

describe('Ranking independent requests and recovery', () => {
  beforeEach(resetMocks)

  it('shows ranking results while sections are still pending and keeps one usable filter area', async () => {
    const sections = deferred<PageResult<Section>>()
    const ranking = deferred<PageResult<Thread>>()
    mocks.listSections.mockReturnValueOnce(sections.promise)
    mocks.listThreadRanking.mockReturnValueOnce(ranking.promise)

    const wrapper = createWrapper()
    await flushPromises()
    expect(mocks.listSections).toHaveBeenCalledTimes(1)
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(1)

    ranking.resolve(rankingPage([thread(1, '榜单先到')]))
    await settle()

    expect(wrapper.text()).toContain('榜单先到')
    const allSectionsButton = wrapper.findAll('button').find(button => button.text() === '全区')
    expect(allSectionsButton).toBeDefined()
    expect(allSectionsButton!.attributes('aria-pressed')).toBe('true')
    sections.resolve(sectionPage())
    await settle()
    wrapper.unmount()
  })

  it('keeps ranking usable when sections fail and retries only the section request', async () => {
    mocks.listSections.mockRejectedValueOnce(new Error('sections offline')).mockResolvedValueOnce(sectionPage())
    mocks.listThreadRanking.mockResolvedValueOnce(rankingPage([thread(2, '榜单仍可见')]))
    const wrapper = createWrapper()
    await settle()

    expect(wrapper.text()).toContain('榜单仍可见')
    expect(wrapper.get('[data-testid="sections-error"]').text()).toContain('加载分区失败')
    const retry = wrapper.get('[data-testid="sections-error"]').find('button')
    await retry.trigger('click')
    await settle()

    expect(mocks.listSections).toHaveBeenCalledTimes(2)
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('游戏')
    expect(wrapper.find('[data-testid="sections-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the filter area usable when ranking fails and retries only ranking', async () => {
    mocks.listSections.mockResolvedValueOnce(sectionPage())
    mocks.listThreadRanking.mockRejectedValueOnce(new Error('ranking offline'))
      .mockResolvedValueOnce(rankingPage([thread(3, '重试后的榜单')]))
    const wrapper = createWrapper()
    await settle()

    expect(wrapper.text()).toContain('全区')
    expect(wrapper.get('[data-testid="ranking-error"]').text()).toContain('加载排行榜失败')
    await wrapper.get('[data-testid="ranking-error"]').find('button').trigger('click')
    await settle()

    expect(mocks.listSections).toHaveBeenCalledTimes(1)
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('重试后的榜单')
    expect(wrapper.find('[data-testid="ranking-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('coalesces concurrent ranking retries into one in-flight request', async () => {
    const retry = deferred<PageResult<Thread>>()
    mocks.listThreadRanking.mockRejectedValueOnce(new Error('ranking offline')).mockReturnValueOnce(retry.promise)
    const wrapper = createWrapper()
    await settle()

    const error = wrapper.get('[data-testid="ranking-error"]')
    const button = error.find('button')
    const first = button.trigger('click')
    const second = button.trigger('click')
    await Promise.all([first, second])
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(2)

    retry.resolve(rankingPage([thread(4, '合并重试成功')]))
    await settle()
    expect(wrapper.text()).toContain('合并重试成功')
    wrapper.unmount()
  })

  it('coalesces concurrent section retries into one in-flight request', async () => {
    const retry = deferred<PageResult<Section>>()
    mocks.listSections.mockRejectedValueOnce(new Error('sections offline')).mockReturnValueOnce(retry.promise)
    const wrapper = createWrapper()
    await settle()

    const error = wrapper.get('[data-testid="sections-error"]')
    const button = error.find('button')
    const first = button.trigger('click')
    const second = button.trigger('click')
    await Promise.all([first, second])
    expect(mocks.listSections).toHaveBeenCalledTimes(2)

    retry.resolve(sectionPage())
    await settle()
    expect(wrapper.text()).toContain('游戏')
    wrapper.unmount()
  })
})

describe('Ranking request ordering and disposal', () => {
  beforeEach(resetMocks)

  it('lets a route change replace an older pending ranking request', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listThreadRanking.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
    const wrapper = createWrapper()
    await settle()

    mocks.route.query = { page: '2', sectionId: '7' }
    await nextTick()
    await flushPromises()
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(2)

    oldRequest.resolve(rankingPage([thread(5, '旧响应')]))
    await settle()
    expect(wrapper.text()).toContain('正在加载')
    expect(wrapper.text()).not.toContain('旧响应')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(rankingPage([thread(6, '新响应')], 2, 11))
    await settle()
    expect(wrapper.text()).toContain('新响应')
    expect(wrapper.text()).not.toContain('旧响应')
    expect(wrapper.text()).not.toContain('正在加载')
    wrapper.unmount()
  })

  it('does not let an older ranking failure or finally clear the newer request state', async () => {
    const oldRequest = deferred<PageResult<Thread>>()
    const newRequest = deferred<PageResult<Thread>>()
    mocks.listThreadRanking.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
    const wrapper = createWrapper()
    await settle()

    mocks.route.query = { page: '2' }
    await nextTick()
    await flushPromises()
    oldRequest.reject(new Error('stale failure'))
    await settle()

    expect(wrapper.text()).toContain('正在加载')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    newRequest.resolve(rankingPage([thread(7, '当前请求')], 2, 11))
    await settle()
    expect(wrapper.text()).toContain('当前请求')
    expect(wrapper.text()).not.toContain('正在加载')
    wrapper.unmount()
  })

  it('ignores ranking success after unmount', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listThreadRanking.mockReturnValueOnce(request.promise)
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { items: Thread[]; loading: boolean; error: string }
    wrapper.unmount()

    request.resolve(rankingPage([thread(8, '卸载后响应')]))
    await settle()
    expect(vm.items).toEqual([])
    expect(vm.loading).toBe(true)
    expect(vm.error).toBe('')
  })

  it('ignores ranking error and finally after unmount', async () => {
    const request = deferred<PageResult<Thread>>()
    mocks.listThreadRanking.mockReturnValueOnce(request.promise)
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { items: Thread[]; loading: boolean; error: string }
    wrapper.unmount()

    request.reject(new Error('卸载后排行榜失败'))
    await settle()
    expect(vm.items).toEqual([])
    expect(vm.loading).toBe(true)
    expect(vm.error).toBe('')
  })

  it('ignores a pending section response after unmount independently', async () => {
    const sections = deferred<PageResult<Section>>()
    mocks.listSections.mockReturnValueOnce(sections.promise)
    mocks.listThreadRanking.mockResolvedValueOnce(rankingPage([thread(9, '卸载前榜单')]))
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { sections: Section[]; sectionsLoading: boolean }
    wrapper.unmount()

    sections.resolve(sectionPage())
    await settle()
    expect(vm.sections).toEqual([])
    expect(vm.sectionsLoading).toBe(true)
  })

  it('ignores a pending section error and finally after unmount independently', async () => {
    const sections = deferred<PageResult<Section>>()
    mocks.listSections.mockReturnValueOnce(sections.promise)
    mocks.listThreadRanking.mockResolvedValueOnce(rankingPage([thread(13, '卸载前榜单')]))
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as {
      sections: Section[]
      sectionsLoading: boolean
      sectionsError: string
    }
    wrapper.unmount()

    sections.reject(new Error('卸载后分区失败'))
    await settle()
    expect(vm.sections).toEqual([])
    expect(vm.sectionsLoading).toBe(true)
    expect(vm.sectionsError).toBe('')
  })

  it('listens only to the normalized section and page query key', async () => {
    mocks.route.query = { page: '1', sectionId: '7' }
    const wrapper = createWrapper()
    await settle()
    mocks.listThreadRanking.mockClear()

    mocks.route.query = { page: '1', sectionId: '7', source: 'header' }
    await settle()
    expect(mocks.listThreadRanking).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('排行帖子')

    const nextRequest = deferred<PageResult<Thread>>()
    mocks.listThreadRanking.mockReturnValueOnce(nextRequest.promise)
    mocks.route.query = { page: '2', sectionId: '7', source: 'header' }
    await settle()
    expect(mocks.listThreadRanking).toHaveBeenCalledExactlyOnceWith({ sectionId: 7, page: 2, size: 10 })
    nextRequest.resolve(rankingPage([thread(10, '第二页榜单')], 2, 11))
    await settle()
    wrapper.unmount()
  })
})

describe('Ranking query normalization and pagination', () => {
  beforeEach(resetMocks)

  it.each(['0', '-1', '1.5', 'Infinity', 'bad', '9007199254740992'])('normalizes invalid page %s before requesting', async (rawPage) => {
    mocks.route.query = { page: rawPage }
    const wrapper = createWrapper()
    await settle()

    expect(mocks.listThreadRanking).toHaveBeenCalledExactlyOnceWith({ sectionId: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it.each([
    ['007', 7],
    ['9007199254740991', 9007199254740991],
    ['9007199254740993', '9007199254740993'],
    ['0', undefined],
    ['-1', undefined],
    ['1.5', undefined],
    ['1e3', undefined],
    ['bad', undefined],
  ] as const)('normalizes sectionId %s without lossy large-number conversion', async (rawSectionId, expectedSectionId) => {
    mocks.route.query = { sectionId: rawSectionId }
    const wrapper = createWrapper()
    await settle()

    expect(mocks.listThreadRanking).toHaveBeenCalledExactlyOnceWith({
      sectionId: expectedSectionId,
      page: 1,
      size: 10,
    })
    wrapper.unmount()
  })

  it('keeps a pager for a two-item last page even when the rest list is empty', async () => {
    mocks.route.query = { page: '2' }
    mocks.listThreadRanking.mockResolvedValueOnce(rankingPage([thread(11, '末页一'), thread(12, '末页二')], 2, 12))
    const wrapper = createWrapper()
    await settle()

    const pager = wrapper.get('.ranking-pagination')
    expect(pager.text()).toContain('2 / 2 页')
    expect(pager.findAll('button').find(button => button.text() === '上一页')?.attributes('disabled')).toBeUndefined()
    expect(pager.findAll('button').find(button => button.text() === '下一页')?.attributes('disabled')).toBeDefined()
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('keeps a pager on an empty page so the user can return without an automatic request', async () => {
    mocks.route.query = { page: '4' }
    mocks.listThreadRanking.mockResolvedValueOnce(rankingPage([], 4, 21))
    const wrapper = createWrapper()
    await settle()

    const pager = wrapper.get('.ranking-pagination')
    expect(pager.text()).toContain('4 / 3 页')
    await pager.findAll('button').find(button => button.text() === '上一页')!.trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({ name: 'ranking', query: { page: 3 } })
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('does not show a useless pager on the empty first page', async () => {
    mocks.listThreadRanking.mockResolvedValueOnce(rankingPage([], 1, 0))
    const wrapper = createWrapper()
    await settle()

    expect(wrapper.text()).toContain('暂无排行数据')
    expect(wrapper.find('.ranking-pagination').exists()).toBe(false)
    expect(mocks.listThreadRanking).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

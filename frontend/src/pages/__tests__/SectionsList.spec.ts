import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SectionsList from '../SectionsList.vue'
import type { Section } from '@/types'

const mocks = vi.hoisted(() => ({
  listSections: vi.fn(),
  listSectionPreviews: vi.fn(),
}))

vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))
vi.mock('@/api/sectionPreviews', () => ({ listSectionPreviews: mocks.listSectionPreviews }))
vi.mock('@/utils/image', () => ({ normalizeImageUrl: (url: string | undefined) => url || '' }))
vi.mock('@/utils/markdownBase', () => ({
  createBaseMarkdownRenderer: () => ({}),
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

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

function section(id: number | string, name = `分区${id}`): Section {
  return { id, name, slug: `section-${id}`, description: `${name}简介` }
}

function page<T>(items: T[], total = items.length, currentPage = 1, size = 20) {
  return { items, page: currentPage, size, total }
}

function item(id: number | string = '101', title = `帖子${id}`, overrides: Record<string, unknown> = {}) {
  return {
    id: String(id),
    title,
    authorId: '7',
    authorUsername: 'alice',
    authorNickname: 'Alice',
    createdAt: '2026-10-25T00:00:00.000Z',
    coverUrl: `/uploads/cover-${id}.jpg`,
    ...overrides,
  }
}

function preview(sectionId: number | string, items: unknown[] = [], total = items.length) {
  return { sectionId: String(sectionId), total, items }
}

function rows(wrapper: ReturnType<typeof createWrapper>) {
  return wrapper.findAll('li.ui-list-row')
}

describe('SectionsList preview states', () => {
  beforeEach(() => {
    mocks.listSections.mockReset()
    mocks.listSectionPreviews.mockReset()
  })

  it('shows section metadata while one batch preview request is pending', async () => {
    const sections = page([section(1, '阅读'), section(2, '科技')])
    const batch = deferred<ReturnType<typeof preview>[]>()
    mocks.listSections.mockResolvedValueOnce(sections)
    mocks.listSectionPreviews.mockReturnValueOnce(batch.promise)

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('阅读')
    expect(wrapper.text()).toContain('科技')
    expect(wrapper.text()).toContain('正在加载帖子...')
    expect(wrapper.text()).toContain('帖子数加载中...')
    expect(wrapper.text()).not.toContain('0 篇帖子')
    expect(mocks.listSections).toHaveBeenCalledTimes(1)
    expect(mocks.listSectionPreviews).toHaveBeenCalledTimes(1)
    expect(mocks.listSectionPreviews).toHaveBeenCalledWith([1, 2])

    batch.resolve([preview(1, [item(101)]), preview(2, [])])
    await flushPromises()
    expect(wrapper.text()).toContain('帖子101')
    expect(wrapper.text()).toContain('0 篇帖子')
    wrapper.unmount()
  })

  it('uses one batch request for 20 visible sections', async () => {
    const sections = Array.from({ length: 20 }, (_, index) => section(index + 1))
    mocks.listSections.mockResolvedValueOnce(page(sections))
    mocks.listSectionPreviews.mockResolvedValueOnce(sections.map((entry) => preview(entry.id)))

    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listSections).toHaveBeenCalledTimes(1)
    expect(mocks.listSectionPreviews).toHaveBeenCalledTimes(1)
    expect(mocks.listSectionPreviews).toHaveBeenCalledWith(Array.from({ length: 20 }, (_, index) => index + 1))
    wrapper.unmount()
  })

  it('loads the second section page and its previews when the total exceeds one page', async () => {
    const firstPage = Array.from({ length: 20 }, (_, index) => section(index + 1))
    const secondPage = [section(21)]
    mocks.listSections
      .mockResolvedValueOnce(page(firstPage, 21, 1))
      .mockResolvedValueOnce(page(secondPage, 21, 2))
    mocks.listSectionPreviews
      .mockResolvedValueOnce(firstPage.map((entry) => preview(entry.id)))
      .mockResolvedValueOnce([preview(21, [item(2101)])])

    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listSections).toHaveBeenNthCalledWith(1, { page: 1, size: 20 })
    expect(rows(wrapper)).toHaveLength(20)
    expect(wrapper.find('input[aria-label="页码"]').element).toHaveProperty('value', '1')
    expect(wrapper.text()).toContain('/ 2 页')
    expect(mocks.listSectionPreviews).toHaveBeenNthCalledWith(1, firstPage.map((entry) => entry.id))

    await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    await flushPromises()

    expect(mocks.listSections).toHaveBeenNthCalledWith(2, { page: 2, size: 20 })
    expect(rows(wrapper)).toHaveLength(1)
    expect(wrapper.text()).toContain('分区21')
    expect(wrapper.text()).toContain('帖子2101')
    expect(wrapper.find('input[aria-label="页码"]').element).toHaveProperty('value', '2')
    expect(mocks.listSectionPreviews).toHaveBeenNthCalledWith(2, [21])
    wrapper.unmount()
  })

  it('ignores a late preview response from the previous page after switching pages', async () => {
    const firstPage = Array.from({ length: 20 }, (_, index) => section(index + 1))
    const secondPage = [section(21)]
    const oldPreview = deferred<ReturnType<typeof preview>[]>()
    mocks.listSections
      .mockResolvedValueOnce(page(firstPage, 21, 1))
      .mockResolvedValueOnce(page(secondPage, 21, 2))
    mocks.listSectionPreviews
      .mockReturnValueOnce(oldPreview.promise)
      .mockResolvedValueOnce([preview(21, [item(2101, '第二页帖子')])])

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('分区21')
    expect(wrapper.text()).toContain('第二页帖子')
    oldPreview.resolve([preview(1, [item(1101, '迟到的第一页帖子')])])
    await flushPromises()

    expect(wrapper.text()).not.toContain('迟到的第一页帖子')
    expect(wrapper.text()).toContain('第二页帖子')
    wrapper.unmount()
  })

  it('retries a failed page load using the current page and then loads its previews', async () => {
    const firstPage = Array.from({ length: 20 }, (_, index) => section(index + 1))
    const secondPage = [section(21)]
    mocks.listSections
      .mockResolvedValueOnce(page(firstPage, 21, 1))
      .mockRejectedValueOnce(new Error('page two failed'))
      .mockResolvedValueOnce(page(secondPage, 21, 2))
    mocks.listSectionPreviews
      .mockResolvedValueOnce(firstPage.map((entry) => preview(entry.id)))
      .mockResolvedValueOnce([preview(21, [item(2101, '重试后的第二页帖子')])])

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('加载分区失败')
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(mocks.listSections).toHaveBeenNthCalledWith(2, { page: 2, size: 20 })
    expect(mocks.listSections).toHaveBeenNthCalledWith(3, { page: 2, size: 20 })
    expect(rows(wrapper)).toHaveLength(1)
    expect(wrapper.text()).toContain('重试后的第二页帖子')
    expect(mocks.listSectionPreviews).toHaveBeenLastCalledWith([21])
    wrapper.unmount()
  })

  it('renders cover, safe title markup, author, date, and count from the preview contract', async () => {
    mocks.listSections.mockResolvedValueOnce(page([section(1, '阅读')]))
    mocks.listSectionPreviews.mockResolvedValueOnce([
      preview(1, [
        item(101, '**加粗标题**', { authorUsername: null, authorNickname: '昵称' }),
        item(102),
        item(103),
      ], 3),
    ])

    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.text()).toContain('**加粗标题**')
    expect(wrapper.text()).toContain('昵称')
    expect(wrapper.text()).toContain('3 篇帖子')
    expect(wrapper.find('img[alt="封面"]').attributes('src')).toBe('/uploads/cover-101.jpg')
    expect(wrapper.html()).not.toContain('content')
    wrapper.unmount()
  })

  it('matches a leading-zero section ID to the normalized preview response', async () => {
    mocks.listSections.mockResolvedValueOnce(page([section('007', '前导零分区')]))
    mocks.listSectionPreviews.mockResolvedValueOnce([preview('7', [item(701)])])

    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listSectionPreviews).toHaveBeenCalledWith(['007'])
    expect(wrapper.text()).toContain('帖子701')
    expect(wrapper.text()).not.toContain('正在加载帖子...')
    wrapper.unmount()
  })

  it('marks every affected row on batch failure without fanning out old requests', async () => {
    mocks.listSections.mockResolvedValueOnce(page([section(1, '阅读'), section(2, '科技')]))
    mocks.listSectionPreviews.mockRejectedValueOnce(new Error('network failure'))

    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listSectionPreviews).toHaveBeenCalledTimes(1)
    expect(rows(wrapper)).toHaveLength(2)
    expect(rows(wrapper).every((row) => row.find('[role="alert"]').exists())).toBe(true)
    expect(wrapper.text()).not.toContain('暂无帖子')
    wrapper.unmount()
  })

  it('retries one failed row through the same batch API and merges duplicate pending clicks', async () => {
    const retry = deferred<ReturnType<typeof preview>[]>()
    mocks.listSections.mockResolvedValueOnce(page([section(1, '阅读'), section(2, '科技')]))
    mocks.listSectionPreviews
      .mockRejectedValueOnce(new Error('batch failed'))
      .mockReturnValueOnce(retry.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const button = rows(wrapper)[0].get('button')
    const firstClick = button.trigger('click')
    const secondClick = button.trigger('click')
    await Promise.all([firstClick, secondClick])

    expect(mocks.listSectionPreviews).toHaveBeenCalledTimes(2)
    expect(mocks.listSectionPreviews).toHaveBeenLastCalledWith([1])
    expect(rows(wrapper)[0].text()).toContain('正在加载帖子...')
    expect(rows(wrapper)[1].text()).toContain('帖子数暂不可用')

    retry.resolve([preview(1, [item(201)])])
    await flushPromises()
    expect(rows(wrapper)[0].text()).toContain('帖子201')
    expect(rows(wrapper)[1].text()).toContain('帖子数暂不可用')
    wrapper.unmount()
  })

  it('keeps independent row retries isolated when they finish out of order', async () => {
    const first = deferred<ReturnType<typeof preview>[]>()
    const second = deferred<ReturnType<typeof preview>[]>()
    mocks.listSections.mockResolvedValueOnce(page([section(1, '阅读'), section(2, '科技')]))
    mocks.listSectionPreviews
      .mockRejectedValueOnce(new Error('batch failed'))
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const rowList = rows(wrapper)
    await Promise.all([rowList[0].get('button').trigger('click'), rowList[1].get('button').trigger('click')])
    expect(mocks.listSectionPreviews).toHaveBeenNthCalledWith(2, [1])
    expect(mocks.listSectionPreviews).toHaveBeenNthCalledWith(3, [2])

    second.resolve([preview(2, [item(202)])])
    await flushPromises()
    expect(rows(wrapper)[1].text()).toContain('帖子202')
    expect(rows(wrapper)[0].text()).toContain('正在加载帖子...')

    first.resolve([preview(1, [item(201)])])
    await flushPromises()
    expect(rows(wrapper)[0].text()).toContain('帖子201')
    wrapper.unmount()
  })

  it('does not let an old batch overwrite a newer full-page load', async () => {
    const oldBatch = deferred<ReturnType<typeof preview>[]>()
    const newBatch = deferred<ReturnType<typeof preview>[]>()
    mocks.listSections
      .mockResolvedValueOnce(page([section(1, '旧分区')]))
      .mockResolvedValueOnce(page([section(1, '新分区')]))
    mocks.listSectionPreviews.mockReturnValueOnce(oldBatch.promise).mockReturnValueOnce(newBatch.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { load: () => Promise<void> }
    void vm.load()
    await flushPromises()

    oldBatch.resolve([preview(1, [item(101, '旧批次帖子')])])
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧批次帖子')

    newBatch.resolve([preview(1, [item(102, '新批次帖子')])])
    await flushPromises()
    expect(wrapper.text()).toContain('新批次帖子')
    expect(wrapper.text()).not.toContain('旧批次帖子')
    wrapper.unmount()
  })

  it('ignores a pending preview response after unmount', async () => {
    const batch = deferred<ReturnType<typeof preview>[]>()
    mocks.listSections.mockResolvedValueOnce(page([section(1, '阅读')]))
    mocks.listSectionPreviews.mockReturnValueOnce(batch.promise)

    const wrapper = createWrapper()
    await flushPromises()
    wrapper.unmount()

    batch.resolve([preview(1, [item(101, '卸载后不应显示')])])
    await flushPromises()
    expect(wrapper.text()).not.toContain('卸载后不应显示')
  })

  it('retries a failed section list request and then starts one preview batch', async () => {
    mocks.listSections
      .mockRejectedValueOnce(new Error('network failure'))
      .mockResolvedValueOnce(page([section(1, '阅读')]))
    mocks.listSectionPreviews.mockResolvedValueOnce([preview(1)])

    const wrapper = createWrapper()
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('加载分区失败')
    await alert.get('button').trigger('click')
    await flushPromises()

    expect(mocks.listSections).toHaveBeenCalledTimes(2)
    expect(mocks.listSectionPreviews).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('阅读')
    wrapper.unmount()
  })

  it('does not request previews for an empty section list', async () => {
    mocks.listSections.mockResolvedValueOnce(page([]))

    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.listSectionPreviews).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('暂无分区')
    wrapper.unmount()
  })

  it('ignores a pending section list response after unmount', async () => {
    const sections = deferred<ReturnType<typeof page>>()
    mocks.listSections.mockReturnValueOnce(sections.promise)

    const wrapper = createWrapper()
    await flushPromises()
    wrapper.unmount()

    sections.resolve(page([section(1, '不应显示')]))
    await flushPromises()

    expect(mocks.listSectionPreviews).not.toHaveBeenCalled()
  })
})

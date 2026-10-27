import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import { bookmarkThread, getThreadBookmarkState, listBookmarks, unbookmarkThread } from '../bookmarks'
import { PageResponseError } from '../pagination'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('bookmarks API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses the bookmark toggle endpoints and DTO', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { bookmarked: false } })
    vi.mocked(api.post).mockResolvedValueOnce({ data: { bookmarked: true } })
    vi.mocked(api.delete).mockResolvedValueOnce({ data: { bookmarked: false } })

    await expect(getThreadBookmarkState(7)).resolves.toEqual({ bookmarked: false })
    await expect(bookmarkThread(7)).resolves.toEqual({ bookmarked: true })
    await expect(unbookmarkThread(7)).resolves.toEqual({ bookmarked: false })

    expect(api.get).toHaveBeenCalledWith('/threads/7/bookmark')
    expect(api.post).toHaveBeenCalledWith('/threads/7/bookmark')
    expect(api.delete).toHaveBeenCalledWith('/threads/7/bookmark')
  })

  it('requests the authenticated bookmark page with the contract pagination params', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        items: [{ threadId: 7, title: '收藏的帖子', sectionName: '编程', bookmarkedAt: '2026-10-07T00:00:00Z' }],
        page: 2,
        size: 10,
        total: 11,
      },
    })

    await expect(listBookmarks({ page: 2, size: 10 })).resolves.toEqual({
      items: [{ threadId: 7, title: '收藏的帖子', sectionName: '编程', bookmarkedAt: '2026-10-07T00:00:00Z' }],
      page: 2,
      size: 10,
      total: 11,
      totalKnown: true,
    })
    expect(api.get).toHaveBeenCalledWith('/users/me/bookmarks', { params: { page: 2, size: 10 } })
  })

  it.each([
    ['object total wins over a forged false flag', { items: [{ threadId: 7, title: '有总数' }], total: 1, totalKnown: false }, true],
    ['omitted total wins over a forged true flag', { items: [{ threadId: 7, title: '无总数' }], totalKnown: true }, false],
  ])('derives totalKnown from the wire total: %s', async (_label, data, totalKnown) => {
    vi.mocked(api.get).mockResolvedValueOnce({ data })

    await expect(listBookmarks()).resolves.toMatchObject({ totalKnown })
  })

  it.each([
    ['null row', null],
    ['array row', []],
    ['missing thread id', { title: '缺少帖子编号' }],
    ['non-positive numeric thread id', { threadId: 0, title: '无效编号' }],
    ['non-canonical string thread id', { threadId: '01', title: '无效编号' }],
    ['unsafe numeric thread id', { threadId: Number.MAX_SAFE_INTEGER + 1, title: '超出安全范围' }],
    ['object title', { threadId: 7, title: { text: '不是字符串' } }],
    ['object presentation field', { threadId: 7, title: '标题', sectionName: { name: '不是字符串' } }],
  ])('rejects a malformed bookmark %s row at the API boundary', async (_label, row) => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { items: [row], page: 1, size: 10, total: 1 },
    })

    await expect(listBookmarks()).rejects.toBeInstanceOf(PageResponseError)
  })

  it('rejects duplicate thread targets after canonicalizing numeric and decimal-string ids', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        items: [
          { threadId: 7, title: '数字编号' },
          { threadId: '7', title: '字符串编号' },
        ],
        page: 1,
        size: 10,
        total: 2,
      },
    })

    await expect(listBookmarks()).rejects.toBeInstanceOf(PageResponseError)
  })

  it('accepts safe numeric and large canonical string ids while normalizing nullable or missing presentation fields', async () => {
    const data = {
      items: [
        { threadId: 7, title: '数字编号', sectionName: null, bookmarkedAt: null },
        { threadId: '9007199254740993', title: '大编号' },
      ],
      page: 1,
      size: 10,
      total: 2,
    }
    vi.mocked(api.get).mockResolvedValueOnce({ data })

    await expect(listBookmarks()).resolves.toEqual({
      items: [
        { threadId: 7, title: '数字编号', sectionName: '', bookmarkedAt: '' },
        { threadId: '9007199254740993', title: '大编号', sectionName: '', bookmarkedAt: '' },
      ],
      page: 1,
      size: 10,
      total: 2,
      totalKnown: true,
    })
  })

  it('retains page and row extra properties without mutating the transport payload', async () => {
    const row = {
      threadId: 7,
      title: '',
      sectionName: null,
      bookmarkedAt: null,
      rowExtra: { source: 'fixture' },
    }
    const data = {
      items: [row],
      page: 1,
      size: 10,
      total: 1,
      pageExtra: 'preserve-me',
    }
    const before = structuredClone(data)
    vi.mocked(api.get).mockResolvedValueOnce({ data })

    const result = await listBookmarks()

    expect(result).toMatchObject({
      pageExtra: 'preserve-me',
      totalKnown: true,
      items: [{ threadId: 7, title: '', sectionName: '', bookmarkedAt: '', rowExtra: { source: 'fixture' } }],
    })
    expect(data).toEqual(before)
  })

  it('fills omitted pagination metadata from the request while retaining a legacy bare-array response', async () => {
    vi.mocked(api.get)
      .mockResolvedValueOnce({ data: { items: [{ threadId: 7, title: '省略元数据' }] } })
      .mockResolvedValueOnce({ data: [{ threadId: 8, title: '旧数组格式' }] })

    await expect(listBookmarks({ page: 3, size: 4 })).resolves.toMatchObject({
      items: [{ threadId: 7, title: '省略元数据', sectionName: '', bookmarkedAt: '' }],
      page: 3,
      size: 4,
      total: 1,
      totalKnown: false,
    })
    await expect(listBookmarks()).resolves.toMatchObject({
      items: [{ threadId: 8, title: '旧数组格式', sectionName: '', bookmarkedAt: '' }],
      page: 1,
      size: 10,
      total: 1,
      totalKnown: false,
    })
  })
})

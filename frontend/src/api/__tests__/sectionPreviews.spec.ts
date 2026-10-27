import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  listSectionPreviews,
  SectionPreviewRequestError,
  SectionPreviewResponseError,
} from '../sectionPreviews'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('../client', () => ({ default: { get: mocks.get } }))

function item(id: string | number = '101', overrides: Record<string, unknown> = {}) {
  return {
    id,
    title: '帖子标题',
    authorId: '7',
    authorUsername: 'alice',
    authorNickname: 'Alice',
    createdAt: '2026-10-25T00:00:00.000Z',
    coverUrl: '/uploads/cover.jpg',
    ...overrides,
  }
}

function response(sectionId: string | number = '1', overrides: Record<string, unknown> = {}) {
  return { sectionId, total: 1, items: [item()], ...overrides }
}

describe('listSectionPreviews', () => {
  beforeEach(() => {
    mocks.get.mockReset()
  })

  it('canonicalizes, deduplicates, and preserves large decimal IDs in one request', async () => {
    mocks.get.mockResolvedValueOnce({
      data: [response('1'), response('9007199254740993', { total: 0, items: [] })],
    })

    await listSectionPreviews(['001', 1, '0009007199254740993'])

    expect(mocks.get).toHaveBeenCalledTimes(1)
    expect(mocks.get).toHaveBeenCalledWith('/threads/section-previews', {
      params: { sectionIds: '1,9007199254740993' },
    })
  })

  it('returns an empty result without requesting when there are no IDs', async () => {
    await expect(listSectionPreviews([])).resolves.toEqual([])
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('rejects invalid IDs, unsafe numbers, and more than 50 original inputs', async () => {
    await expect(listSectionPreviews(['0'])).rejects.toBeInstanceOf(SectionPreviewRequestError)
    await expect(listSectionPreviews(['1.0'])).rejects.toBeInstanceOf(SectionPreviewRequestError)
    await expect(listSectionPreviews([Number.MAX_SAFE_INTEGER + 1])).rejects.toBeInstanceOf(SectionPreviewRequestError)
    await expect(listSectionPreviews(Array.from({ length: 51 }, () => 1))).rejects.toBeInstanceOf(SectionPreviewRequestError)
    expect(mocks.get).not.toHaveBeenCalled()
  })

  it('returns sections in request order and normalizes nullable authors', async () => {
    mocks.get.mockResolvedValueOnce({
      data: [
        response('2', { total: 0, items: [] }),
        response('1', { items: [item(101, { authorUsername: null, authorNickname: null })] }),
      ],
    })

    await expect(listSectionPreviews([1, 2])).resolves.toEqual([
      {
        sectionId: '1',
        total: 1,
        items: [{
          id: '101',
          title: '帖子标题',
          authorId: '7',
          authorUsername: undefined,
          authorNickname: undefined,
          createdAt: '2026-10-25T00:00:00.000Z',
          coverUrl: '/uploads/cover.jpg',
        }],
      },
      { sectionId: '2', total: 0, items: [] },
    ])
  })

  it('accepts safe numeric fixture IDs while keeping string IDs in the result', async () => {
    mocks.get.mockResolvedValueOnce({ data: [response(1, { items: [item(101)] })] })

    const previews = await listSectionPreviews([1])

    expect(previews[0].sectionId).toBe('1')
    expect(previews[0].items[0].id).toBe('101')
  })

  it.each([
    ['a non-array response', { data: {} }],
    ['a non-object section', { data: [null] }],
    ['a missing section', { data: [] }],
    ['an unknown section', { data: [response('9')] }],
    ['a duplicate section', { data: [response('1'), response('1')] }],
    ['a malformed total', { data: [response('1', { total: '1' })] }],
    ['an item with an invalid ID', { data: [response('1', { items: [item('0')] })] }],
    ['an item with a duplicate ID', { data: [response('1', { total: 2, items: [item('101'), item('101')] })] }],
    ['too many items', { data: [response('1', { total: 5, items: [item('1'), item('2'), item('3'), item('4'), item('5')] })] }],
    ['items beyond total', { data: [response('1', { total: 0 })] }],
    ['missing fixed preview items', { data: [response('1', { total: 2, items: [] })] }],
    ['partial fixed preview items', { data: [response('1', { total: 2, items: [item('101')] })] }],
    ['an invalid date', { data: [response('1', { items: [item('101', { createdAt: 'not-a-date' })] })] }],
    ['a date without a full timestamp', { data: [response('1', { items: [item('101', { createdAt: '2026-10-25' })] })] }],
    ['a date without a timezone', { data: [response('1', { items: [item('101', { createdAt: '2026-10-25T00:00:00' })] })] }],
    ['a date with an invalid calendar day', { data: [response('1', { items: [item('101', { createdAt: '2026-02-30T00:00:00Z' })] })] }],
    ['a missing cover URL', { data: [response('1', { items: [item('101', { coverUrl: undefined })] })] }],
  ])('throws a format error for %s', async (_label, payload) => {
    mocks.get.mockResolvedValueOnce(payload)
    await expect(listSectionPreviews([1])).rejects.toBeInstanceOf(SectionPreviewResponseError)
  })
})

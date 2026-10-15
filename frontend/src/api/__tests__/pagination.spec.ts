import { describe, expect, it } from 'vitest'
import { decodePageResponse, PageResponseError } from '@/api/pagination'

describe('decodePageResponse', () => {
  const options = { page: 2, size: 10, endpoint: '/threads' }

  it('accepts the page envelope and preserves endpoint metadata', () => {
    const result = decodePageResponse<{ id: number }, { source?: string; sectionId?: number }>(
      {
        items: [{ id: 1 }],
        page: 3,
        size: 5,
        total: 12,
        source: 'notification-service',
        sectionId: 7,
      },
      options,
    )

    expect(result).toEqual({
      items: [{ id: 1 }],
      page: 3,
      size: 5,
      total: 12,
      source: 'notification-service',
      sectionId: 7,
    })
  })

  it('fills only missing metadata from the request and item count', () => {
    expect(decodePageResponse<number>({ items: [1, 2] }, options)).toEqual({
      items: [1, 2],
      page: 2,
      size: 10,
      total: 2,
    })
  })

  it('keeps a legacy array at the API boundary with page-local total semantics', () => {
    expect(decodePageResponse<number>([1, 2], options)).toEqual({
      items: [1, 2],
      page: 2,
      size: 10,
      total: 2,
    })
  })

  it.each([
    ['null payload', null, 'expected an object with an items array'],
    ['missing items', {}, 'items must be an array'],
    ['non-array items', { items: {} }, 'items must be an array'],
    ['invalid page', { items: [], page: 0 }, 'page must be a positive safe integer'],
    ['invalid size', { items: [], size: 1.5 }, 'size must be a positive safe integer'],
    ['invalid total', { items: [], total: -1 }, 'total must be a non-negative safe integer'],
    ['numeric string metadata', { items: [], page: '2' }, 'page must be a positive safe integer'],
  ])('rejects %s instead of treating it as an empty page', (_name, payload, message) => {
    expect(() => decodePageResponse<unknown>(payload, options)).toThrowError(new PageResponseError(`Invalid paginated response for /threads: ${message}`))
  })

  it('rejects invalid request fallback metadata', () => {
    expect(() => decodePageResponse([], { page: 0, size: 10, endpoint: '/threads' })).toThrowError(
      'Invalid pagination request for /threads: page must be a positive safe integer',
    )
  })
})


import type { PageResult } from '@/types'

export interface PageDecodeOptions {
  page: number
  size: number
  endpoint?: string
}

export class PageResponseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PageResponseError'
  }
}

type PageResponseRecord = Record<string, unknown>

function label(options: PageDecodeOptions): string {
  return options.endpoint ? ` for ${options.endpoint}` : ''
}

function assertRequestedPage(value: number, field: 'page' | 'size', options: PageDecodeOptions): void {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new PageResponseError(`Invalid pagination request${label(options)}: ${field} must be a positive safe integer`)
  }
}

function assertMetadata(
  value: unknown,
  field: 'page' | 'size' | 'total',
  options: PageDecodeOptions,
): number {
  const valid = field === 'total'
    ? Number.isSafeInteger(value) && Number(value) >= 0
    : Number.isSafeInteger(value) && Number(value) > 0
  if (!valid) {
    const requirement = field === 'total' ? 'a non-negative safe integer' : 'a positive safe integer'
    throw new PageResponseError(`Invalid paginated response${label(options)}: ${field} must be ${requirement}`)
  }
  return value as number
}

function hasOwn(record: PageResponseRecord, field: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, field)
}

/**
 * Decode the HTTP page contract at the API boundary.
 *
 * Production responses are objects with an items array and optional page,
 * size, and total metadata. A bare array is retained only for legacy callers;
 * its total is the number of items in that response page and does not describe
 * the complete result set.
 */
export function decodePageResponse<T, E extends object = object>(
  data: unknown,
  options: PageDecodeOptions,
): PageResult<T> & E {
  assertRequestedPage(options.page, 'page', options)
  assertRequestedPage(options.size, 'size', options)

  if (Array.isArray(data)) {
    return {
      items: data as T[],
      page: options.page,
      size: options.size,
      total: data.length,
    } as PageResult<T> & E
  }

  if (typeof data !== 'object' || data === null) {
    throw new PageResponseError(`Invalid paginated response${label(options)}: expected an object with an items array`)
  }

  const record = data as PageResponseRecord
  if (!hasOwn(record, 'items') || !Array.isArray(record.items)) {
    throw new PageResponseError(`Invalid paginated response${label(options)}: items must be an array`)
  }

  const page = hasOwn(record, 'page') ? assertMetadata(record.page, 'page', options) : options.page
  const size = hasOwn(record, 'size') ? assertMetadata(record.size, 'size', options) : options.size
  const total = hasOwn(record, 'total') ? assertMetadata(record.total, 'total', options) : record.items.length

  return {
    ...record,
    items: record.items as T[],
    page,
    size,
    total,
  } as PageResult<T> & E
}


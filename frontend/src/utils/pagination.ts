import type { PageResult } from '@/types'

export function getEmptyPageFallback(
  requestedPage: number,
  result: Pick<PageResult<unknown>, 'items' | 'size' | 'total'>,
): number | null {
  const { items, size, total } = result
  if (!Array.isArray(items) || items.length !== 0) return null

  if (!Number.isSafeInteger(size) || size <= 0) return null
  if (!Number.isSafeInteger(total) || total < 0) return null

  const lastPage = Math.max(1, Math.ceil(total / size))
  return lastPage < requestedPage ? lastPage : null
}

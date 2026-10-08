import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import { getAdminStatus, listAdminReports, resolveAdminReport } from '@/api/admin'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const apiMock = api as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
}

describe('admin API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses server admin verification, report pagination, and exact resolve payloads', async () => {
    apiMock.get
      .mockResolvedValueOnce({ data: { admin: true } })
      .mockResolvedValueOnce({ data: { items: [], page: 2, size: 20, total: 21 } })
    apiMock.post.mockResolvedValueOnce({ data: null })

    await expect(getAdminStatus()).resolves.toBe(true)
    await expect(listAdminReports({ page: 2, size: 20 })).resolves.toEqual({
      items: [],
      page: 2,
      size: 20,
      total: 21,
    })
    await resolveAdminReport(17, 'HIDE')

    expect(apiMock.get).toHaveBeenNthCalledWith(1, '/admin/me')
    expect(apiMock.get).toHaveBeenNthCalledWith(2, '/admin/reports', {
      params: { status: 'PENDING', page: 2, size: 20 },
    })
    expect(apiMock.post).toHaveBeenCalledWith('/admin/reports/17/resolve', { action: 'HIDE' })
  })
})

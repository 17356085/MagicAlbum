import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import {
  createGovernanceSection,
  getGovernanceMe,
  listGovernanceAudit,
  listGovernanceSections,
  listGovernanceUsers,
  requireGovernanceReason,
  updateGovernanceUserAdmin,
} from '@/api/governance'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  },
}))

const apiMock = api as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
  put: ReturnType<typeof vi.fn>
}

const user = {
  id: 7,
  username: 'reviewer',
  admin: false,
  moderatedSectionIds: [3],
  postingRestricted: false,
  restrictedUntil: null,
}

const section = {
  id: 3,
  name: '编程',
  slug: 'coding',
  description: '技术交流',
  rules: '请友善讨论',
  visible: true,
  archived: false,
}

describe('governance API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('decodes current access, paginated users, sections, and audit records', async () => {
    apiMock.get
      .mockResolvedValueOnce({ data: { admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null } })
      .mockResolvedValueOnce({ data: { items: [user], page: 2, size: 20, total: 21 } })
      .mockResolvedValueOnce({ data: [section] })
      .mockResolvedValueOnce({ data: { items: [{ id: 1, actorId: 9, action: 'ADMIN_ROLE_CHANGED', targetType: 'USER', targetId: 7, reason: '交接', occurredAt: '2026-10-20T00:00:00Z', requestId: 'req-1' }], page: 1, size: 20, total: 1 } })

    await expect(getGovernanceMe()).resolves.toEqual({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
    await expect(listGovernanceUsers({ q: 'review', page: 2, size: 20 })).resolves.toEqual({ items: [user], page: 2, size: 20, total: 21 })
    await expect(listGovernanceSections()).resolves.toEqual([section])
    await expect(listGovernanceAudit()).resolves.toMatchObject({ page: 1, size: 20, total: 1 })

    expect(apiMock.get).toHaveBeenNthCalledWith(2, '/governance/users', { params: { page: 2, size: 20, q: 'review' } })
    expect(apiMock.get).toHaveBeenNthCalledWith(4, '/governance/audit', { params: { page: 1, size: 20 } })
  })

  it('trims and validates reasons before sending governance writes', async () => {
    apiMock.put.mockResolvedValue({ data: user })
    apiMock.post.mockResolvedValue({ data: section })

    await updateGovernanceUserAdmin(7, { enabled: true, reason: '  授予审核权限  ' })
    await createGovernanceSection({ name: '新分区', slug: 'new', description: '', rules: '', visible: true, archived: false, reason: '  建立分区  ' })

    expect(apiMock.put).toHaveBeenCalledWith('/governance/users/7/admin', { enabled: true, reason: '授予审核权限' })
    expect(apiMock.post).toHaveBeenCalledWith('/governance/sections', expect.objectContaining({ reason: '建立分区' }))
    expect(requireGovernanceReason('  理由  ')).toBe('理由')
    expect(() => requireGovernanceReason('   ')).toThrow()
    expect(() => requireGovernanceReason('x'.repeat(501))).toThrow()
  })

  it('rejects malformed responses instead of guessing missing governance fields', async () => {
    apiMock.get.mockResolvedValueOnce({ data: { admin: true, moderatedSectionIds: [], postingRestricted: false } })
    await expect(getGovernanceMe()).rejects.toThrow(/missing restrictedUntil/)

    apiMock.get.mockResolvedValueOnce({ data: { items: [{ ...user, admin: 'yes' }], page: 1, size: 20, total: 1 } })
    await expect(listGovernanceUsers()).rejects.toThrow(/admin must be a boolean/)
  })
})

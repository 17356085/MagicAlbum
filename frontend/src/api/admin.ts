import api from './client'
import { decodePageResponse } from './pagination'
import type { Id, PageResult } from '@/types'

export type AdminReportAction = 'DISMISS' | 'HIDE' | 'RESTORE'
export type AdminReportStatus = 'PENDING' | 'HIDDEN' | 'DISMISSED' | 'RESTORED'

export interface AdminReport {
  id: Id
  targetType: string
  targetId: Id
  reason: string
  detail?: string | null
  status: string
  createdAt: string
  reporterId: Id
  /** Optional context returned for POST reports. It is never inferred from targetId. */
  threadId?: Id | null
  targetThreadId?: Id | null
  resolutionReason?: string | null
  reviewerId?: Id | null
  resolvedAt?: string | null
}

export type AdminReportsPage = PageResult<AdminReport>

export interface AdminStatusResponse {
  admin?: boolean
}

export async function getAdminStatus(): Promise<boolean> {
  const { data } = await api.get<AdminStatusResponse>('/admin/me')
  return data?.admin === true
}

export async function listAdminReports({
  status = 'PENDING',
  page = 1,
  size = 20,
}: {
  status?: AdminReportStatus | string
  page?: number
  size?: number
} = {}): Promise<AdminReportsPage> {
  const { data } = await api.get<AdminReportsPage>('/admin/reports', {
    params: { status, page, size },
  })
  return decodePageResponse<AdminReport>(data, { page, size, endpoint: '/admin/reports' })
}

export function validateAdminReportReason(reason: string): string {
  const normalized = String(reason ?? '').trim()
  if (normalized.length < 1 || normalized.length > 500) {
    throw new Error('处理理由必须填写，长度为 1–500 个字符。')
  }
  return normalized
}

export async function resolveAdminReport(id: Id, action: AdminReportAction, reason: string): Promise<void> {
  await api.post(`/admin/reports/${id}/resolve`, { action, reason: validateAdminReportReason(reason) })
}

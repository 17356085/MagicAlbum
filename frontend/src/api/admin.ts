import api from './client'
import { decodePageResponse } from './pagination'
import type { Id, PageResult } from '@/types'

export type AdminReportAction = 'DISMISS' | 'HIDE'

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
  status?: string
  page?: number
  size?: number
} = {}): Promise<AdminReportsPage> {
  const { data } = await api.get<AdminReportsPage>('/admin/reports', {
    params: { status, page, size },
  })
  return decodePageResponse<AdminReport>(data, { page, size, endpoint: '/admin/reports' })
}

export async function resolveAdminReport(id: Id, action: AdminReportAction): Promise<void> {
  await api.post(`/admin/reports/${id}/resolve`, { action })
}

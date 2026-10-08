import api from './client'
import type { Id } from '@/types'

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

export interface AdminReportsPage {
  items: AdminReport[]
  page: number
  size: number
  total: number
}

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

  return {
    items: Array.isArray(data?.items) ? data.items : [],
    page: Number.isFinite(Number(data?.page)) && Number(data.page) > 0 ? Number(data.page) : page,
    size: Number.isFinite(Number(data?.size)) && Number(data.size) > 0 ? Number(data.size) : size,
    total: Number.isFinite(Number(data?.total)) && Number(data.total) >= 0 ? Number(data.total) : 0,
  }
}

export async function resolveAdminReport(id: Id, action: AdminReportAction): Promise<void> {
  await api.post(`/admin/reports/${id}/resolve`, { action })
}

import api from './client'

export type ReportTargetType = 'THREAD' | 'POST'
export type ReportReason = 'SPAM' | 'HARASSMENT' | 'OTHER'

export interface CreateReportPayload {
  targetType: ReportTargetType
  targetId: number
  reason: ReportReason
  detail?: string
}

export interface ReportResponse extends CreateReportPayload {
  id?: number
  reporterId?: number
  status?: string
  reviewerId?: number | null
  resolvedAt?: string | null
  createdAt?: string
}

export async function createReport(payload: CreateReportPayload): Promise<ReportResponse> {
  const { data } = await api.post('/reports', payload)
  return data as ReportResponse
}

// Keep the action-oriented name available to callers that do not need to
// distinguish report creation from the idempotent server-side replay.
export const submitReport = createReport

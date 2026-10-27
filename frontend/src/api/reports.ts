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

export interface ReportAcknowledgement {
  id: number
  targetType: ReportTargetType
  targetId: number
}

export const REPORT_ACK_UNCONFIRMED = 'REPORT_ACK_UNCONFIRMED' as const

export class ReportAcknowledgementError extends Error {
  readonly code = REPORT_ACK_UNCONFIRMED

  constructor(message = '举报提交结果暂未确认，请重试') {
    super(message)
    this.name = 'ReportAcknowledgementError'
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

function hasOwn(value: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(value, key)
}

function isReportTargetType(value: unknown): value is ReportTargetType {
  return value === 'THREAD' || value === 'POST'
}

function decodeReportAcknowledgement(
  value: unknown,
  payload: CreateReportPayload,
): ReportAcknowledgement {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new ReportAcknowledgementError()
  }

  if (!hasOwn(value, 'id') || !hasOwn(value, 'targetType') || !hasOwn(value, 'targetId')) {
    throw new ReportAcknowledgementError()
  }

  const body = value as { id?: unknown; targetType?: unknown; targetId?: unknown }
  const id = body.id
  if (typeof id !== 'number' || !Number.isSafeInteger(id) || id <= 0) {
    throw new ReportAcknowledgementError()
  }
  if (!isReportTargetType(body.targetType) || body.targetType !== payload.targetType) {
    throw new ReportAcknowledgementError()
  }
  const targetId = body.targetId
  if (typeof targetId !== 'number' || !Number.isSafeInteger(targetId) || targetId <= 0 || targetId !== payload.targetId) {
    throw new ReportAcknowledgementError()
  }

  return {
    id,
    targetType: body.targetType,
    targetId,
  }
}

export async function createReport(payload: CreateReportPayload): Promise<ReportAcknowledgement> {
  const { data } = await api.post<unknown>('/reports', payload)
  return decodeReportAcknowledgement(data, payload)
}

// Keep the action-oriented name available to callers that do not need to
// distinguish report creation from the idempotent server-side replay.
export const submitReport = createReport

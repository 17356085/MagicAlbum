import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createReport, ReportAcknowledgementError, submitReport } from '../reports'

const mockApi = vi.hoisted(() => ({
  post: vi.fn(),
}))

vi.mock('../client', () => ({ default: mockApi }))

const payload = {
  targetType: 'THREAD' as const,
  targetId: 11,
  reason: 'SPAM' as const,
  detail: '说明',
}

function expectAckFailure(promise: Promise<unknown>): Promise<void> {
  return expect(promise).rejects.toSatisfy((cause: unknown) => {
    return cause instanceof ReportAcknowledgementError
      && cause.code === 'REPORT_ACK_UNCONFIRMED'
  })
}

beforeEach(() => {
  mockApi.post.mockReset()
})

describe('createReport acknowledgement contract', () => {
  it.each([
    { targetType: 'THREAD' as const, targetId: 11, id: 31 },
    { targetType: 'POST' as const, targetId: 22, id: 32 },
  ])('accepts a minimal $targetType acknowledgement and returns only its three fields', async ({ targetType, targetId, id }) => {
    mockApi.post.mockResolvedValueOnce({
      data: {
        id,
        targetType,
        targetId,
        reporterId: 7,
        reason: 'HARASSMENT',
        detail: 'old persisted detail',
        status: 'HIDDEN',
        createdAt: '2026-10-25T00:00:00Z',
      },
      status: 201,
    })

    const result = await createReport({ targetType, targetId, reason: 'OTHER' })

    expect(result).toEqual({ id, targetType, targetId })
    expect(Object.keys(result).sort()).toEqual(['id', 'targetId', 'targetType'])
  })

  it.each([
    { status: 'DISMISSED', reason: 'SPAM' as const, detail: 'old dismissed detail' },
    { status: 'HIDDEN', reason: 'HARASSMENT' as const, detail: null },
    { status: 'RESTORED', reason: 'OTHER' as const, detail: 'old restored detail' },
  ])('does not require PENDING or request metadata for a legal replay ($status)', async ({ status, reason, detail }) => {
    mockApi.post.mockResolvedValueOnce({
      data: {
        id: 41,
        targetType: 'THREAD',
        targetId: 11,
        reporterId: 7,
        reason,
        detail,
        status,
      },
      status: 201,
    })

    await expect(createReport({
      ...payload,
      reason: 'OTHER',
      detail: 'new request detail',
    })).resolves.toEqual({ id: 41, targetType: 'THREAD', targetId: 11 })
  })

  it('keeps submitReport as the exact createReport function', () => {
    expect(submitReport).toBe(createReport)
  })

  it.each([
    { name: 'null', data: null },
    { name: 'undefined', data: undefined },
    { name: 'HTML string', data: '<html>gateway error</html>' },
    { name: 'array', data: [{ id: 31, targetType: 'THREAD', targetId: 11 }] },
    { name: 'number', data: 31 },
    { name: 'boolean', data: false },
    { name: 'missing id', data: { targetType: 'THREAD', targetId: 11 } },
    { name: 'missing targetType', data: { id: 31, targetId: 11 } },
    { name: 'missing targetId', data: { id: 31, targetType: 'THREAD' } },
    { name: 'inherited id', data: Object.assign(Object.create({ id: 31 }), { targetType: 'THREAD', targetId: 11 }) },
    { name: 'id zero', data: { id: 0, targetType: 'THREAD', targetId: 11 } },
    { name: 'id negative', data: { id: -1, targetType: 'THREAD', targetId: 11 } },
    { name: 'id fractional', data: { id: 31.5, targetType: 'THREAD', targetId: 11 } },
    { name: 'id unsafe', data: { id: Number.MAX_SAFE_INTEGER + 1, targetType: 'THREAD', targetId: 11 } },
    { name: 'id string', data: { id: '31', targetType: 'THREAD', targetId: 11 } },
    { name: 'targetType invalid', data: { id: 31, targetType: 'COMMENT', targetId: 11 } },
    { name: 'targetType mismatch', data: { id: 31, targetType: 'POST', targetId: 11 } },
    { name: 'targetId zero', data: { id: 31, targetType: 'THREAD', targetId: 0 } },
    { name: 'targetId negative', data: { id: 31, targetType: 'THREAD', targetId: -11 } },
    { name: 'targetId fractional', data: { id: 31, targetType: 'THREAD', targetId: 11.5 } },
    { name: 'targetId unsafe', data: { id: 31, targetType: 'THREAD', targetId: Number.MAX_SAFE_INTEGER + 1 } },
    { name: 'targetId string', data: { id: 31, targetType: 'THREAD', targetId: '11' } },
    { name: 'targetId mismatch', data: { id: 31, targetType: 'THREAD', targetId: 12 } },
  ])('rejects an unconfirmed response body ($name)', async ({ data }) => {
    mockApi.post.mockResolvedValueOnce({ data, status: 201 })

    await expectAckFailure(createReport(payload))
  })

  it('does not wrap an ordinary HTTP failure in the acknowledgement error', async () => {
    const httpFailure = { response: { status: 503, data: { message: 'temporarily unavailable' } } }
    mockApi.post.mockRejectedValueOnce(httpFailure)

    await expect(createReport(payload)).rejects.toBe(httpFailure)
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import {
  cancelSummary,
  createSummaryRequestKey,
  decodeAiSummary,
  getSummaryTask,
  triggerSummary,
} from '@/api/ai'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
  handleCurrentTokenUnauthorized: vi.fn(),
}))

const completeSummary = {
  threadId: 7,
  taskId: null,
  contentVersion: 0,
  taskContentVersion: null,
  status: 'COMPLETED',
  summary: '一段摘要',
  canManage: true,
  attempts: 1,
  errorCode: null,
  provider: 'mock',
  model: 'mock-summary',
  inputTokens: 12,
  outputTokens: 8,
  usageEstimated: true,
  chargedTokens: 20,
  requestId: 'request-1',
  updatedAt: '2026-10-20T00:00:00Z',
} as const

const key = '11111111-1111-4111-8111-111111111111'

describe('AI 摘要 API', () => {
  beforeEach(() => vi.clearAllMocks())

  afterEach(() => vi.unstubAllGlobals())

  it('严格解码直接响应并保留 contentVersion=0', () => {
    expect(decodeAiSummary(completeSummary)).toEqual(completeSummary)
    expect(() => decodeAiSummary({ data: completeSummary })).toThrow('threadId')
    expect(() => decodeAiSummary({ ...completeSummary, attempts: '1' })).toThrow('attempts')
  })

  it('发送空 body、UUID 幂等键和可取消信号', async () => {
    const signal = new AbortController().signal
    vi.mocked(api.post).mockResolvedValueOnce({ data: completeSummary })

    await expect(triggerSummary(7, true, key, signal)).resolves.toEqual(completeSummary)

    expect(api.post).toHaveBeenCalledWith(
      '/ai/summary/7?force=true',
      undefined,
      { headers: { 'Idempotency-Key': key }, signal },
    )
  })

  it('暴露任务查询和取消端点', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: completeSummary })
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...completeSummary, status: 'CANCELLED', taskId: 'task-1' } })

    await expect(getSummaryTask('task-1')).resolves.toEqual(completeSummary)
    await expect(cancelSummary(7, 'task-1')).resolves.toMatchObject({ status: 'CANCELLED', taskId: 'task-1' })
    expect(api.get).toHaveBeenCalledWith('/ai/summary/tasks/task-1')
    expect(api.post).toHaveBeenCalledWith('/ai/summary/7/tasks/task-1/cancel')
  })

  it('在 randomUUID 不可用时生成 UUID v4 fallback', () => {
    vi.stubGlobal('crypto', {
      randomUUID: vi.fn(() => { throw new Error('secure context required') }),
      getRandomValues: vi.fn((bytes: Uint8Array) => bytes.fill(0)),
    })

    expect(createSummaryRequestKey()).toBe('00000000-0000-4000-8000-000000000000')
  })
})

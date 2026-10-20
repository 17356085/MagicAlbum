import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import type { AiSummary } from '@/api/ai'
import ThreadDetailSummary from '../ThreadDetailSummary.vue'

const apiMocks = vi.hoisted(() => ({
  cancelSummary: vi.fn(),
  createSummaryRequestKey: vi.fn(),
  getSummary: vi.fn(),
  getSummaryTask: vi.fn(),
  triggerSummary: vi.fn(),
}))

vi.mock('@/api/ai', () => apiMocks)

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}

function summary(overrides: Partial<AiSummary> = {}): AiSummary {
  return {
    threadId: 1,
    taskId: null,
    contentVersion: 0,
    taskContentVersion: null,
    status: 'IDLE',
    summary: '',
    canManage: true,
    attempts: 0,
    errorCode: null,
    provider: null,
    model: null,
    inputTokens: null,
    outputTokens: null,
    usageEstimated: false,
    chargedTokens: 0,
    requestId: null,
    updatedAt: null,
    ...overrides,
  }
}

function axiosError(status: number): Error & { isAxiosError: boolean; response: { status: number } } {
  return Object.assign(new Error(`HTTP ${status}`), {
    isAxiosError: true,
    response: { status },
  })
}

const keyA = '11111111-1111-4111-8111-111111111111'
const keyB = '22222222-2222-4222-8222-222222222222'

let wrapper: ReturnType<typeof mount> | null = null

beforeEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
  apiMocks.createSummaryRequestKey.mockReturnValue(keyA)
  apiMocks.getSummary.mockResolvedValue(summary())
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
})

async function mountSummary(threadId = 1) {
  wrapper = mount(ThreadDetailSummary, { props: { threadId } })
  await flushPromises()
  return wrapper
}

describe('ThreadDetailSummary task ownership', () => {
  it('ignores a late response after the thread changes', async () => {
    const first = deferred<AiSummary>()
    const second = deferred<AiSummary>()
    apiMocks.getSummary.mockReset()
    apiMocks.getSummary.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const view = await mountSummary(1)

    await view.setProps({ threadId: 2 })
    second.resolve(summary({ threadId: 2, summary: '新帖子摘要', status: 'COMPLETED' }))
    await flushPromises()
    first.resolve(summary({ threadId: 1, summary: '旧帖子摘要', status: 'COMPLETED' }))
    await flushPromises()

    expect(view.text()).toContain('新帖子摘要')
    expect(view.text()).not.toContain('旧帖子摘要')
  })

  it('ignores a late response after the account generation changes with the same token', async () => {
    const first = deferred<AiSummary>()
    const second = deferred<AiSummary>()
    apiMocks.getSummary.mockReset()
    apiMocks.getSummary.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const view = await mountSummary()
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 2, username: 'account-b' } })

    second.resolve(summary({ summary: '账号二摘要', status: 'COMPLETED' }))
    await flushPromises()
    first.resolve(summary({ summary: '账号一摘要', status: 'COMPLETED' }))
    await flushPromises()

    expect(view.text()).toContain('账号二摘要')
    expect(view.text()).not.toContain('账号一摘要')
  })

  it('coalesces duplicate generate clicks', async () => {
    const pending = deferred<AiSummary>()
    apiMocks.triggerSummary.mockReturnValue(pending.promise)
    const view = await mountSummary()
    const button = view.get('button')

    await Promise.all([button.trigger('click'), button.trigger('click')])
    expect(apiMocks.triggerSummary).toHaveBeenCalledTimes(1)
    pending.resolve(summary({ status: 'COMPLETED', summary: '生成完成' }))
    await flushPromises()
  })

  it('retries a network failure with the same idempotency key', async () => {
    apiMocks.triggerSummary.mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(summary({ status: 'COMPLETED', summary: '重试成功' }))
    const view = await mountSummary()

    await view.get('button').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('重试生成')
    await view.get('button').trigger('click')
    await flushPromises()

    expect(apiMocks.triggerSummary).toHaveBeenNthCalledWith(1, 1, false, keyA, expect.any(AbortSignal))
    expect(apiMocks.triggerSummary).toHaveBeenNthCalledWith(2, 1, false, keyA, expect.any(AbortSignal))
  })

  it('uses a new forced request after a real FAILED response', async () => {
    apiMocks.createSummaryRequestKey.mockReturnValueOnce(keyA).mockReturnValueOnce(keyB)
    apiMocks.triggerSummary.mockResolvedValueOnce(summary({ status: 'FAILED', errorCode: 'LEASE_EXPIRED' }))
      .mockResolvedValueOnce(summary({ status: 'COMPLETED', summary: '重试完成' }))
    const view = await mountSummary()

    await view.get('button').trigger('click')
    await flushPromises()
    expect(view.text()).toContain('重试生成')
    await view.get('button').trigger('click')
    await flushPromises()

    expect(apiMocks.triggerSummary).toHaveBeenNthCalledWith(1, 1, false, keyA, expect.any(AbortSignal))
    expect(apiMocks.triggerSummary).toHaveBeenNthCalledWith(2, 1, true, keyB, expect.any(AbortSignal))
  })

  it('shows refresh only after a version conflict and ignores an older refresh response', async () => {
    const staleRefresh = deferred<AiSummary>()
    const latestRefresh = deferred<AiSummary>()
    apiMocks.getSummary.mockReset()
    apiMocks.getSummary
      .mockResolvedValueOnce(summary({ contentVersion: 1, status: 'COMPLETED', summary: '原摘要' }))
      .mockReturnValueOnce(staleRefresh.promise)
      .mockReturnValueOnce(latestRefresh.promise)
    apiMocks.triggerSummary.mockResolvedValueOnce(summary({ contentVersion: 2, status: 'COMPLETED', summary: '不应显示' }))
    const view = await mountSummary()

    await view.get('button').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('刷新状态')
    expect(view.findAll('button').map((button) => button.text())).toEqual(['刷新状态'])

    const refreshButton = view.get('button')
    await refreshButton.trigger('click')
    await refreshButton.trigger('click')
    expect(apiMocks.getSummary).toHaveBeenCalledTimes(3)

    latestRefresh.resolve(summary({ contentVersion: 3, status: 'COMPLETED', summary: '最新刷新' }))
    await flushPromises()
    staleRefresh.resolve(summary({ contentVersion: 2, status: 'COMPLETED', summary: '旧刷新' }))
    await flushPromises()

    expect(view.text()).toContain('最新刷新')
    expect(view.text()).not.toContain('旧刷新')
  })

  it('polls serially and does not declare completion after a fixed count', async () => {
    vi.useFakeTimers()
    const firstPoll = deferred<AiSummary>()
    const secondPoll = deferred<AiSummary>()
    apiMocks.triggerSummary.mockResolvedValue(summary({ status: 'PENDING', taskId: 'task-1' }))
    apiMocks.getSummaryTask.mockReturnValueOnce(firstPoll.promise).mockReturnValueOnce(secondPoll.promise)
    const view = await mountSummary()
    await view.get('button').trigger('click')
    await flushPromises()

    expect(apiMocks.getSummaryTask).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(apiMocks.getSummaryTask).toHaveBeenCalledTimes(1)
    firstPoll.resolve(summary({ status: 'PENDING', taskId: 'task-1' }))
    await flushPromises()
    vi.advanceTimersByTime(1999)
    await flushPromises()
    expect(apiMocks.getSummaryTask).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1)
    await flushPromises()
    expect(apiMocks.getSummaryTask).toHaveBeenCalledTimes(2)
    secondPoll.resolve(summary({ status: 'COMPLETED', taskId: 'task-1', summary: '最终摘要' }))
    await flushPromises()
    expect(view.text()).toContain('最终摘要')
  })

  it('does not render a late task result after cancellation', async () => {
    const latePoll = deferred<AiSummary>()
    const cancelled = deferred<AiSummary>()
    apiMocks.getSummary.mockResolvedValue(summary({ status: 'PENDING', taskId: 'task-1' }))
    apiMocks.getSummaryTask.mockReturnValue(latePoll.promise)
    apiMocks.cancelSummary.mockReturnValue(cancelled.promise)
    const view = await mountSummary()
    await flushPromises()
    await view.get('button').trigger('click')
    cancelled.resolve(summary({ status: 'CANCELLED', taskId: 'task-1' }))
    await flushPromises()
    latePoll.resolve(summary({ status: 'COMPLETED', taskId: 'task-1', summary: '迟到旧摘要' }))
    await flushPromises()

    expect(view.text()).toContain('已取消摘要生成')
    expect(view.text()).not.toContain('迟到旧摘要')
  })

  it('aborts an in-flight cancel request when the thread changes', async () => {
    const cancelled = deferred<AiSummary>()
    let cancelSignal: AbortSignal | undefined
    apiMocks.getSummary.mockResolvedValue(summary({ status: 'PENDING', taskId: 'task-1' }))
    apiMocks.getSummaryTask.mockReturnValue(new Promise<AiSummary>(() => {}))
    apiMocks.cancelSummary.mockImplementation((_threadId: number, _taskId: string, signal: AbortSignal) => {
      cancelSignal = signal
      return cancelled.promise
    })
    const view = await mountSummary()
    await flushPromises()
    await view.get('button').trigger('click')
    await flushPromises()

    await view.setProps({ threadId: 2 })
    expect(cancelSignal?.aborted).toBe(true)
    cancelled.resolve(summary({ status: 'CANCELLED', taskId: 'task-1' }))
    await flushPromises()
  })

  it('refreshes from a new content-version baseline after polling pauses', async () => {
    vi.useFakeTimers()
    apiMocks.getSummary.mockReset()
    apiMocks.getSummary.mockResolvedValueOnce(summary({
      contentVersion: 1,
      taskContentVersion: 1,
      status: 'PENDING',
      taskId: 'task-1',
    })).mockResolvedValueOnce(summary({
      contentVersion: 2,
      status: 'COMPLETED',
      summary: '新版本摘要',
    }))
    apiMocks.getSummaryTask.mockResolvedValue(summary({
      contentVersion: 1,
      taskContentVersion: 1,
      status: 'PENDING',
      taskId: 'task-1',
    }))
    const view = await mountSummary()
    await flushPromises()

    vi.advanceTimersByTime(120000)
    await flushPromises()
    expect(view.text()).toContain('刷新状态')

    const refreshButton = view.findAll('button').find((button) => button.text().includes('刷新状态'))
    expect(refreshButton).toBeTruthy()
    await refreshButton!.trigger('click')
    await flushPromises()
    expect(view.text()).toContain('新版本摘要')
  })

  it('shows a permission error for 403 without logging out', async () => {
    apiMocks.triggerSummary.mockRejectedValueOnce(axiosError(403))
    const view = await mountSummary()
    await view.get('button').trigger('click')
    await flushPromises()

    expect(view.text()).toContain('没有管理此摘要的权限')
    expect(useAuthStore().isLoggedIn).toBe(true)
  })

  it('labels mock summaries and hides management controls for readers', async () => {
    apiMocks.getSummary.mockResolvedValueOnce(summary({ status: 'COMPLETED', summary: '公开摘要', provider: 'mock', canManage: false }))
    const view = await mountSummary()

    expect(view.text()).toContain('测试模式')
    expect(view.text()).toContain('公开摘要')
    expect(view.findAll('button')).toHaveLength(0)
  })
})

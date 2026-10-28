import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '@/stores/auth'
import { dismissMediaAsset, trackMediaUpload, updateMediaAsset, mediaAssets, hydrateMediaAssets, type MediaAssetState } from '../mediaProcessingState'
import { useMediaProcessing } from '../useMediaProcessing'

const mocks = vi.hoisted(() => ({ getMediaAsset: vi.fn() }))
vi.mock('@/api/uploads', () => ({ getMediaAsset: mocks.getMediaAsset }))

const POLL_DELAY = 2500
const MEASURE_MARKER = 'MEDIA_POLL_MEASURE'
const uploadAsset = {
  assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39',
  url: '/uploads/original-upload.png',
  status: 'READY',
  processingStatus: 'PENDING' as const,
}
const terminalAsset = {
  assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39',
  url: '/uploads/original-terminal.png',
  status: 'READY',
  processingStatus: 'SUCCEEDED' as const,
  previewUrl: '/uploads/preview-terminal.png',
}
const retryAsset = {
  assetId: 'f2a1665b-60b1-40f9-85a5-3632148fda39',
  url: '/uploads/original-retry.png',
  status: 'READY',
  processingStatus: 'FAILED' as const,
}
const fairnessAssets = ['a2a1665b-60b1-40f9-85a5-3632148fda39', 'a3a1665b-60b1-40f9-85a5-3632148fda39', 'a4a1665b-60b1-40f9-85a5-3632148fda39', 'a5a1665b-60b1-40f9-85a5-3632148fda39', 'a6a1665b-60b1-40f9-85a5-3632148fda39'].map(assetId => ({
  assetId,
  url: `/uploads/${assetId}.png`,
  status: 'READY',
  processingStatus: 'PENDING' as const,
}))

const observedControllers: AbortController[] = []
const wrappers: VueWrapper[] = []
let timerSpy: MockInstance<typeof setTimeout>

function activate(id: number, token: string): void {
  useAuthStore().applyAuthState({ accessToken: token, user: { id, username: `owner-${id}` } })
}

function mountProcessing(): VueWrapper {
  const wrapper = mount(defineComponent({
    setup() {
      useMediaProcessing()
      return () => null
    },
  }))
  wrappers.push(wrapper)
  return wrapper
}

async function settle(): Promise<void> {
  await nextTick()
  await flushPromises()
  await nextTick()
}

function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T | PromiseLike<T>) => void
  reject: (reason?: unknown) => void
} {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

function scheduledPolls(): number {
  return timerSpy.mock.calls.filter(call => Number(call[1]) === POLL_DELAY).length
}

function measure(scenario: string, phase: string, extra: Record<string, unknown> = {}): void {
  const record = {
    scenario,
    phase,
    controllersCreated: observedControllers.length,
    abortedControllers: observedControllers.filter(controller => controller.signal.aborted).length,
    timer2500Schedules: scheduledPolls(),
    getMediaAssetCalls: mocks.getMediaAsset.mock.calls.length,
    requestedAssetIds: mocks.getMediaAsset.mock.calls.map(call => call[0]),
    mediaAssetIds: mediaAssets.value.map(item => item.assetId),
    mediaAssetStatuses: mediaAssets.value.map(item => item.processingStatus),
    ...extra,
  }
  console.log(`${MEASURE_MARKER} ${JSON.stringify(record)}`)
}

describe('useMediaProcessing idle-stop and event-wakeup contract', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    mediaAssets.value = []
    mocks.getMediaAsset.mockReset()
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    observedControllers.length = 0
    timerSpy = vi.spyOn(globalThis, 'setTimeout')

    const RealAbortController = globalThis.AbortController
    class ObservedAbortController extends RealAbortController {
      constructor() {
        super()
        observedControllers.push(this)
      }
    }
    vi.stubGlobal('AbortController', ObservedAbortController)
  })

  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.useRealTimers()
    localStorage.clear()
    sessionStorage.clear()
    mediaAssets.value = []
    hydrateMediaAssets('')
  })

  it('stops empty polling without creating controllers or timers', async () => {
    activate(1, 'token-empty')
    mountProcessing()
    await settle()
    measure('empty-cache', 'initial')

    expect(mocks.getMediaAsset).not.toHaveBeenCalled()
    expect(observedControllers).toHaveLength(0)
    expect(scheduledPolls()).toBe(0)

    for (const windowIndex of [1, 2, 3]) {
      await vi.advanceTimersByTimeAsync(POLL_DELAY)
      await settle()
      measure('empty-cache', `after-${windowIndex}-window`)
    }

    expect(mocks.getMediaAsset).not.toHaveBeenCalled()
    expect(observedControllers).toHaveLength(0)
    expect(scheduledPolls()).toBe(0)
  })

  it('confirms a terminal initial cache once, then stops the loop', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify([terminalAsset]))
    mocks.getMediaAsset.mockResolvedValue({ ...terminalAsset })
    activate(1, 'token-terminal')
    mountProcessing()
    await settle()
    measure('terminal-cache', 'initial-confirmation')

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(mocks.getMediaAsset.mock.calls[0]?.[0]).toBe(terminalAsset.assetId)
    expect(mediaAssets.value[0]?.processingStatus).toBe('SUCCEEDED')
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(0)

    for (const windowIndex of [1, 2, 3]) {
      await vi.advanceTimersByTimeAsync(POLL_DELAY)
      await settle()
      measure('terminal-cache', `after-${windowIndex}-window`)
    }

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(0)
  })

  it('confirms the first object when the initial cache repeats an asset id', async () => {
    const duplicateTerminalAsset = { ...terminalAsset, url: '/uploads/original-terminal-duplicate.png' }
    localStorage.setItem('bluealbum:media:1', JSON.stringify([terminalAsset, duplicateTerminalAsset]))
    mocks.getMediaAsset.mockResolvedValue({ ...terminalAsset })
    activate(1, 'token-duplicate-terminal')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(mocks.getMediaAsset.mock.calls[0]?.[0]).toBe(terminalAsset.assetId)
    expect(scheduledPolls()).toBe(0)
  })

  it('wakes immediately for a new upload and a retry without waiting for 2500ms', async () => {
    mocks.getMediaAsset.mockImplementation(async (assetId: string) => {
      if (assetId === uploadAsset.assetId) return { ...uploadAsset, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/upload-preview.png' }
      return { ...retryAsset, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/retry-preview.png' }
    })
    activate(1, 'token-discovery')
    mountProcessing()
    await settle()
    measure('new-upload-and-retry', 'empty-before-upload')

    trackMediaUpload(uploadAsset, 'token-discovery')
    await settle()
    measure('new-upload-and-retry', 'after-upload-wakeup')
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(mocks.getMediaAsset.mock.calls[0]?.[0]).toBe(uploadAsset.assetId)
    expect(mediaAssets.value[0]?.processingStatus).toBe('SUCCEEDED')
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(0)

    trackMediaUpload(retryAsset, 'token-discovery')
    updateMediaAsset({ ...retryAsset, processingStatus: 'PENDING' }, 'token-discovery')
    await settle()
    measure('new-upload-and-retry', 'after-retry-wakeup')
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(2)
    expect(mocks.getMediaAsset.mock.calls[1]?.[0]).toBe(retryAsset.assetId)
    expect(mediaAssets.value.find(item => item.assetId === retryAsset.assetId)?.processingStatus).toBe('SUCCEEDED')
    expect(observedControllers).toHaveLength(2)
    expect(scheduledPolls()).toBe(0)
  })

  it('does not overlap an active request or add reads while a timer is queued', async () => {
    let resolveFirst: ((value: typeof uploadAsset) => void) | undefined
    mocks.getMediaAsset.mockImplementation(() => new Promise(resolve => { resolveFirst = resolve }))
    activate(1, 'token-no-overlap')
    mountProcessing()
    await settle()

    trackMediaUpload(uploadAsset, 'token-no-overlap')
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(1)

    trackMediaUpload({ ...retryAsset, processingStatus: 'PENDING' }, 'token-no-overlap')
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(0)

    resolveFirst?.({ ...uploadAsset, processingStatus: 'PENDING' })
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(scheduledPolls()).toBe(1)

    trackMediaUpload({ ...terminalAsset, assetId: 'b2a1665b-60b1-40f9-85a5-3632148fda39', processingStatus: 'PENDING' }, 'token-no-overlap')
    trackMediaUpload({ ...terminalAsset, assetId: 'b3a1665b-60b1-40f9-85a5-3632148fda39', processingStatus: 'PENDING' }, 'token-no-overlap')
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset.mock.calls.length).toBeGreaterThan(1)
    expect(mocks.getMediaAsset.mock.calls.length).toBeLessThanOrEqual(4)
  })

  it('keeps the four-item batch cap and advances offset fairly across remaining cache items', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify(fairnessAssets))
    mocks.getMediaAsset.mockImplementation(async (assetId: string) => ({
      ...fairnessAssets.find(item => item.assetId === assetId),
      processingStatus: 'PENDING',
    }))
    activate(1, 'token-fairness')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(4)
    expect(mocks.getMediaAsset.mock.calls.slice(0, 4).map(call => call[0])).toEqual(fairnessAssets.slice(0, 4).map(item => item.assetId))
    expect(scheduledPolls()).toBe(1)

    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(8)
    expect(mocks.getMediaAsset.mock.calls.slice(4).map(call => call[0])).toEqual([
      fairnessAssets[4]?.assetId,
      fairnessAssets[0]?.assetId,
      fairnessAssets[1]?.assetId,
      fairnessAssets[2]?.assetId,
    ])
  })

  it('keeps an initial id eligible after an API failure until a later success', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify([terminalAsset]))
    mocks.getMediaAsset.mockRejectedValueOnce(new Error('temporary failure')).mockResolvedValueOnce({ ...terminalAsset })
    activate(1, 'token-initial-retry')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(scheduledPolls()).toBe(1)
    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(2)
    expect(scheduledPolls()).toBe(1)
    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(2)
    expect(scheduledPolls()).toBe(1)
  })

  it('does not read a cache record again after it is removed during an active batch', async () => {
    const secondAsset = { ...terminalAsset, assetId: 'c2a1665b-60b1-40f9-85a5-3632148fda39' }
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset, secondAsset]))
    let resolveFirst: ((value: typeof uploadAsset) => void) | undefined
    mocks.getMediaAsset.mockImplementation((assetId: string) => {
      if (assetId === uploadAsset.assetId) return new Promise(resolve => { resolveFirst = resolve })
      return Promise.resolve({ ...secondAsset, processingStatus: 'SUCCEEDED' })
    })
    activate(1, 'token-remove-during-poll')
    mountProcessing()
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)

    dismissMediaAsset(secondAsset.assetId)
    resolveFirst?.({ ...uploadAsset, processingStatus: 'PENDING' })
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(mocks.getMediaAsset.mock.calls[0]?.[0]).toBe(uploadAsset.assetId)
  })

  it('does not apply a stale pending response after a successful cancel update', async () => {
    const pendingResponse = deferred<MediaAssetState>()
    mocks.getMediaAsset.mockReturnValue(pendingResponse.promise)
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset]))
    activate(1, 'token-stale-cancel')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    updateMediaAsset({ ...uploadAsset, processingStatus: 'CANCELLED' }, 'token-stale-cancel')
    await settle()
    expect(mediaAssets.value[0]?.processingStatus).toBe('CANCELLED')

    pendingResponse.resolve({ ...uploadAsset, processingStatus: 'PENDING' })
    await settle()
    expect(mediaAssets.value[0]?.processingStatus).toBe('CANCELLED')
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(scheduledPolls()).toBe(0)
  })

  it('keeps a retried pending object active when a stale failed response arrives', async () => {
    const failedResponse = deferred<MediaAssetState>()
    mocks.getMediaAsset.mockReturnValueOnce(failedResponse.promise).mockResolvedValueOnce({ ...retryAsset, processingStatus: 'PENDING' })
    localStorage.setItem('bluealbum:media:1', JSON.stringify([retryAsset]))
    activate(1, 'token-stale-retry')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    updateMediaAsset({ ...retryAsset, processingStatus: 'PENDING' }, 'token-stale-retry')
    await settle()
    failedResponse.resolve({ ...retryAsset, processingStatus: 'FAILED' })
    await settle()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(scheduledPolls()).toBe(1)
    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(2)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
  })

  it('does not let a stale success replace an asset reinserted after removal', async () => {
    const oldResponse = deferred<MediaAssetState>()
    mocks.getMediaAsset.mockReturnValue(oldResponse.promise)
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset]))
    activate(1, 'token-stale-reinsert')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    dismissMediaAsset(uploadAsset.assetId)
    trackMediaUpload({ ...uploadAsset, processingStatus: 'PENDING' }, 'token-stale-reinsert')
    await settle()
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')

    oldResponse.resolve({ ...uploadAsset, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/stale-preview.png' })
    await settle()
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(mediaAssets.value[0]?.previewUrl).toBeNull()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
  })

  it('does not mark a newly updated object unavailable when an old request rejects', async () => {
    const oldResponse = deferred<MediaAssetState>()
    mocks.getMediaAsset.mockReturnValue(oldResponse.promise)
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset]))
    activate(1, 'token-stale-reject')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    updateMediaAsset({ ...uploadAsset, processingStatus: 'PENDING' }, 'token-stale-reject')
    await settle()
    oldResponse.reject(new Error('stale failure'))
    await settle()

    expect(mediaAssets.value[0]?.unavailable).not.toBe(true)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(scheduledPolls()).toBe(1)
  })

  it('clears a queued timer when the last pending record is removed', async () => {
    mocks.getMediaAsset.mockResolvedValue({ ...uploadAsset, processingStatus: 'PENDING' })
    activate(1, 'token-remove-last')
    mountProcessing()
    await settle()
    trackMediaUpload(uploadAsset, 'token-remove-last')
    await settle()

    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(scheduledPolls()).toBe(1)
    dismissMediaAsset(uploadAsset.assetId)
    await settle()
    expect(mediaAssets.value).toEqual([])

    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(1)
  })

  it('does not read while hidden and resumes on the next active timer window', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset]))
    mocks.getMediaAsset.mockResolvedValue({ ...uploadAsset, processingStatus: 'SUCCEEDED' })
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    activate(1, 'token-hidden')
    mountProcessing()
    await settle()

    expect(mocks.getMediaAsset).not.toHaveBeenCalled()
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(1)

    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).not.toHaveBeenCalled()
    expect(observedControllers).toHaveLength(2)
    expect(scheduledPolls()).toBe(2)

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(3)
    expect(scheduledPolls()).toBe(2)
  })

  it('aborts the active request and suppresses old polling after an account switch', async () => {
    const firstAsset = { ...uploadAsset }
    localStorage.setItem('bluealbum:media:1', JSON.stringify([firstAsset]))
    mocks.getMediaAsset.mockImplementation((_assetId: string, _token: string, signal?: AbortSignal) => new Promise((_resolve, reject) => {
      if (signal?.aborted) {
        reject(new DOMException('Aborted', 'AbortError'))
        return
      }
      signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }))
    activate(1, 'token-first')
    mountProcessing()
    await settle()
    measure('account-switch', 'first-request')
    const firstController = observedControllers[0]
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(firstController?.signal.aborted).toBe(false)

    activate(2, 'token-second')
    await settle()
    measure('account-switch', 'after-switch')
    expect(firstController?.signal.aborted).toBe(true)
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(0)
    expect(mediaAssets.value).toEqual([])

    await vi.advanceTimersByTimeAsync(POLL_DELAY)
    await settle()
    measure('account-switch', 'after-second-empty-window')
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(observedControllers).toHaveLength(1)
    expect(scheduledPolls()).toBe(0)
  })

  it('keeps the new session timer when an old session finally settles late', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset]))
    let finishOld: ((value: MediaAssetState) => void) | undefined
    mocks.getMediaAsset.mockImplementation((_assetId: string, token: string) => {
      if (token === 'token-first-late') return new Promise(resolve => { finishOld = resolve })
      return Promise.resolve({ ...retryAsset, processingStatus: 'PENDING' })
    })
    activate(1, 'token-first-late')
    mountProcessing()
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)

    activate(2, 'token-second-late')
    await settle()
    trackMediaUpload({ ...retryAsset, processingStatus: 'PENDING' }, 'token-second-late')
    await settle()
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(2)
    expect(scheduledPolls()).toBe(1)

    finishOld?.({ ...uploadAsset, processingStatus: 'SUCCEEDED' })
    await settle()
    expect(mediaAssets.value.map(item => item.assetId)).toEqual([retryAsset.assetId])
    expect(scheduledPolls()).toBe(1)
  })

  it('aborts a request and schedules no future poll when unmounted', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify([uploadAsset]))
    mocks.getMediaAsset.mockImplementation((_assetId: string, _token: string, signal?: AbortSignal) => new Promise((_resolve, reject) => {
      signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }))
    activate(1, 'token-unmount')
    const wrapper = mountProcessing()
    await settle()
    measure('unmount', 'before-unmount')
    const requestController = observedControllers[0]
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(scheduledPolls()).toBe(0)

    wrapper.unmount()
    await settle()
    measure('unmount', 'after-unmount')
    expect(requestController?.signal.aborted).toBe(true)

    await vi.advanceTimersByTimeAsync(POLL_DELAY * 2)
    await settle()
    measure('unmount', 'after-future-windows')
    expect(mocks.getMediaAsset).toHaveBeenCalledTimes(1)
    expect(scheduledPolls()).toBe(0)
  })
})

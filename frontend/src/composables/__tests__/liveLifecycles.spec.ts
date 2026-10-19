import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, nextTick } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '@/stores/auth'
import { useLiveNotifications } from '../useLiveNotifications'
import { useMediaProcessing } from '../useMediaProcessing'
import { mediaAssets } from '../mediaProcessingState'
import { subscribeNotificationChanges } from '../notificationSignals'
import type { NotificationChange } from '@/api/notificationStream'

const mocks = vi.hoisted(() => ({ stream: vi.fn(), asset: vi.fn() }))
vi.mock('@/api/notificationStream', () => ({
  readNotificationStream: mocks.stream,
  NotificationStreamError: class extends Error { constructor(readonly status: number) { super() } },
}))
vi.mock('@/api/uploads', () => ({ getMediaAsset: mocks.asset }))

const asset = { assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39', url: '/original.png', processingStatus: 'PENDING' as const }
let wrappers: VueWrapper[] = []
function activate(id: number, token: string) {
  useAuthStore().applyAuthState({ accessToken: token, user: { id, username: `owner-${id}` } })
}
function start(hook: () => void) {
  const wrapper = mount(defineComponent({ setup() { hook(); return () => null } }))
  wrappers.push(wrapper)
  return wrapper
}

describe('live connection session lifecycles', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    mocks.stream.mockReset(); mocks.asset.mockReset()
    mocks.stream.mockImplementation((_token, _cursor, signal: AbortSignal) => new Promise<void>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
    }))
  })
  afterEach(() => {
    wrappers.forEach(wrapper => wrapper.unmount()); wrappers = []
    vi.useRealTimers(); localStorage.clear(); mediaAssets.value = []
  })

  it('starts after login persistence and aborts on account changes, hiding and unmount', async () => {
    const wrapper = start(useLiveNotifications)
    expect(mocks.stream).not.toHaveBeenCalled()
    activate(1, 'first'); await nextTick()
    expect(mocks.stream.mock.calls[0]?.[0]).toBe('first')
    const firstSignal = mocks.stream.mock.calls[0]?.[2] as AbortSignal
    activate(2, 'second'); await nextTick()
    expect(firstSignal.aborted).toBe(true)
    expect(mocks.stream.mock.calls[1]?.[0]).toBe('second')
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect((mocks.stream.mock.calls[1]?.[2] as AbortSignal).aborted).toBe(true)
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    wrapper.unmount(); wrappers = []
    expect((mocks.stream.mock.calls[2]?.[2] as AbortSignal).aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(20000)
    expect(mocks.stream).toHaveBeenCalledTimes(3)
  })

  it('coalesces events and does not let old callbacks refresh the next account', async () => {
    activate(1, 'first')
    const signals: string[] = []
    const unsubscribe = subscribeNotificationChanges(signal => signals.push(signal))
    try {
      start(useLiveNotifications)
      const callback = mocks.stream.mock.calls[0]?.[3] as (event: NotificationChange) => void
      callback({ cursor: '1', kind: 'created' }); callback({ cursor: '2', kind: 'read' })
      await vi.advanceTimersByTimeAsync(100)
      expect(signals.filter(signal => signal === 'changed')).toHaveLength(1)
      activate(2, 'second'); await nextTick()
      callback({ cursor: '3', kind: 'created' })
      await vi.advanceTimersByTimeAsync(100)
      expect(signals.filter(signal => signal === 'changed')).toHaveLength(1)
    } finally { unsubscribe() }
  })

  it('abandons the old polling batch when the first request finishes after an account switch', async () => {
    localStorage.setItem('bluealbum:media:1', JSON.stringify([asset, { ...asset, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39' }]))
    activate(1, 'first')
    let finish!: (value: unknown) => void
    mocks.asset.mockReturnValue(new Promise(resolve => { finish = resolve }))
    start(useMediaProcessing)
    expect(mocks.asset).toHaveBeenCalledTimes(1)
    const signal = mocks.asset.mock.calls[0]?.[2] as AbortSignal
    activate(2, 'second'); await nextTick()
    expect(signal.aborted).toBe(true)
    finish({ ...asset, processingStatus: 'SUCCEEDED', previewUrl: '/preview.png' })
    await flushPromises()
    expect(mediaAssets.value).toEqual([])
    expect(mocks.asset).toHaveBeenCalledTimes(1)
  })
})

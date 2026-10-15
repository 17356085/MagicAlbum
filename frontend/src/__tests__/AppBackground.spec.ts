import { shallowMount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, type MockInstance, vi } from 'vitest'
import App from '../App.vue'
import { useUIStore } from '../stores/ui'

describe('App background lifecycle', () => {
  let wrapper: VueWrapper | undefined
  let idleCallbacks: Array<() => void>
  let media: Map<string, MediaQueryList>
  let frame: ReturnType<typeof vi.fn>
  let cancelFrame: ReturnType<typeof vi.fn>
  let context: MockInstance<HTMLCanvasElement['getContext']>

  beforeEach(() => {
    localStorage.clear()
    document.documentElement.classList.remove('dark')
    idleCallbacks = []
    media = new Map()
    vi.stubGlobal('matchMedia', vi.fn((query: string) => {
      if (!media.has(query)) {
        const target = new EventTarget()
        media.set(query, Object.assign(target, {
          matches: false, media: query, onchange: null,
          addListener: vi.fn(), removeListener: vi.fn(),
          addEventListener: vi.fn(target.addEventListener.bind(target)),
          removeEventListener: vi.fn(target.removeEventListener.bind(target)),
        }) as unknown as MediaQueryList)
      }
      return media.get(query)
    }))
    vi.stubGlobal('requestIdleCallback', vi.fn((callback: () => void) => {
      idleCallbacks.push(callback)
      return idleCallbacks.length - 1
    }))
    vi.stubGlobal('cancelIdleCallback', vi.fn())
    frame = vi.fn(() => 0)
    cancelFrame = vi.fn()
    vi.stubGlobal('requestAnimationFrame', frame)
    vi.stubGlobal('cancelAnimationFrame', cancelFrame)
    context = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ setTransform: vi.fn() } as unknown as CanvasRenderingContext2D)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    document.documentElement.classList.remove('dark')
    localStorage.clear()
  })

  function mountApp(enabled = true) {
    const pinia = createPinia()
    const ui = useUIStore(pinia)
    ui.setDynamicBackgroundEnabled(enabled)
    wrapper = shallowMount(App, {
      global: { plugins: [pinia], mocks: { $route: { fullPath: '/discover' } }, stubs: { RouterView: true } },
    })
    return ui
  }

  it('cancels queued startup and ignores stale idle callbacks across rapid toggles', async () => {
    const ui = mountApp()
    ui.setDynamicBackgroundEnabled(false)
    await nextTick()
    expect(window.cancelIdleCallback).toHaveBeenCalledWith(0)
    ui.setDynamicBackgroundEnabled(true)
    await nextTick()
    idleCallbacks[0]()
    expect(context).not.toHaveBeenCalled()
    idleCallbacks[1]()
    expect(frame).toHaveBeenCalledTimes(1)
    ui.setDynamicBackgroundEnabled(false)
    await nextTick()
    expect(cancelFrame).toHaveBeenCalledWith(0)
  })

  it('cancels fallback timers when disabled or unmounted', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('requestIdleCallback', undefined)
    const ui = mountApp()
    ui.setDynamicBackgroundEnabled(false)
    await nextTick()
    vi.advanceTimersByTime(200)
    expect(context).not.toHaveBeenCalled()
    ui.setDynamicBackgroundEnabled(true)
    await nextTick()
    wrapper!.unmount()
    wrapper = undefined
    vi.advanceTimersByTime(200)
    expect(context).not.toHaveBeenCalled()
  })

  it('releases modern media listeners and prevents idle startup after unmount', () => {
    mountApp()
    wrapper!.unmount()
    wrapper = undefined
    idleCallbacks[0]()
    expect(context).not.toHaveBeenCalled()
    for (const query of media.values()) {
      expect(query.removeEventListener).toHaveBeenCalledWith('change', expect.any(Function))
    }
  })

  it('stops active night animation when reduced motion is enabled and cleans legacy listeners', async () => {
    document.documentElement.classList.add('dark')
    for (const query of ['(prefers-reduced-motion: reduce)', '(prefers-color-scheme: dark)']) {
      window.matchMedia(query)
      Object.assign(media.get(query)!, { addEventListener: undefined, removeEventListener: undefined })
    }
    mountApp()
    await nextTick()
    expect(frame).toHaveBeenCalledTimes(1)
    const reduced = media.get('(prefers-reduced-motion: reduce)')!
    Object.assign(reduced, { matches: true })
    const listener = vi.mocked(reduced.addListener).mock.calls[0]?.[0]
    expect(listener).toEqual(expect.any(Function))
    if (typeof listener !== 'function') throw new Error('legacy reduced-motion listener was not registered')
    listener.call(reduced, new Event('change') as MediaQueryListEvent)
    await nextTick()
    expect(cancelFrame).toHaveBeenCalledWith(0)
    wrapper!.unmount()
    wrapper = undefined
    for (const query of media.values()) {
      const handler = vi.mocked(query.addListener).mock.calls[0]?.[0]
      expect(handler).toEqual(expect.any(Function))
      if (typeof handler !== 'function') throw new Error('legacy media listener was not registered')
      expect(query.removeListener).toHaveBeenCalledWith(handler)
    }
  })
})

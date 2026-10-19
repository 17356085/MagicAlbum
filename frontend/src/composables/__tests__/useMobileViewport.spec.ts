import { mount } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useMobileViewport } from '../useMobileViewport'

describe('mobile input and visible viewport', () => {
  afterEach(() => {
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('tracks text entry without treating a checkbox as a keyboard input', async () => {
    let state!: ReturnType<typeof useMobileViewport>
    const wrapper = mount(defineComponent({
      setup() { state = useMobileViewport(); return {} },
      template: '<div><input type="text"><input type="checkbox"><textarea /></div>',
    }), { attachTo: document.body })
    ;(wrapper.get('input[type=text]').element as HTMLInputElement).focus()
    await nextTick()
    expect(state.inputActive.value).toBe(true)
    ;(wrapper.get('input[type=checkbox]').element as HTMLInputElement).focus()
    await nextTick()
    expect(state.inputActive.value).toBe(false)
    ;(wrapper.get('textarea').element as HTMLTextAreaElement).focus()
    await nextTick()
    expect(state.inputActive.value).toBe(true)
    wrapper.unmount()
  })

  it('updates viewport dimensions and detaches browser listeners on disposal', () => {
    const viewport = Object.assign(new EventTarget(), { height: 400, offsetTop: 20 })
    const remove = vi.spyOn(viewport, 'removeEventListener')
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    let state!: ReturnType<typeof useMobileViewport>
    const wrapper = mount(defineComponent({ setup() { state = useMobileViewport(); return () => null } }))
    expect(state.viewportStyle.value['--mobile-viewport-height']).toBe('400px')
    expect(state.viewportStyle.value['--mobile-viewport-bottom']).toBe(`${Math.max(0, window.innerHeight - 420)}px`)
    viewport.height = 600
    viewport.dispatchEvent(new Event('resize'))
    expect(state.viewportStyle.value['--mobile-viewport-height']).toBe('600px')
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('resize', expect.any(Function))
    expect(remove).toHaveBeenCalledWith('scroll', expect.any(Function))
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: undefined })
  })
})

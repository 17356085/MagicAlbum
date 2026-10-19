import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import SearchBox from '../SearchBox.vue'

const wrappers: ReturnType<typeof mount>[] = []
function createWrapper() {
  const wrapper = mount(SearchBox, {
    props: {
      searchType: 'threads', searchQuery: '', suggestOpen: false,
      suggestLoading: false, suggestError: '', visibleSuggestions: [],
      suggestProfiles: {}, activeIndex: -1,
    },
    attachTo: document.body,
  })
  wrappers.push(wrapper)
  return wrapper
}

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('SearchBox keyboard dismissal', () => {
  it.each(['input', '#header-search-panel button'])('returns focus from %s to the opener on Escape', async (selector) => {
    const wrapper = createWrapper()
    const trigger = wrapper.get('button[aria-controls="header-search-panel"]')
    await trigger.trigger('click')
    await flushPromises()
    const control = wrapper.get(selector)
    ;(control.element as HTMLElement).focus()
    await control.trigger('keydown', { key: 'Escape' })
    await flushPromises()

    expect(trigger.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(trigger.element)
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  it('does not steal focus after outside pointer dismissal', async () => {
    const wrapper = createWrapper()
    await wrapper.get('button[aria-controls="header-search-panel"]').trigger('click')
    await flushPromises()
    const outside = document.createElement('button')
    document.body.appendChild(outside)
    outside.focus()
    outside.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await flushPromises()
    expect(document.activeElement).toBe(outside)
    expect(wrapper.get('button[aria-controls="header-search-panel"]').attributes('aria-expanded')).toBe('false')
  })

  it('preserves the desktop input focus when the mobile panel is closed', async () => {
    const wrapper = createWrapper()
    const input = wrapper.get('input')
    ;(input.element as HTMLInputElement).focus()
    await input.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(document.activeElement).toBe(input.element)
    expect(wrapper.emitted('input-keydown')).toHaveLength(1)
  })
})

import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import ConfirmDialog from '../ConfirmDialog.vue'

describe('ConfirmDialog keyboard access', () => {
  it('keeps Tab inside the dialog, supports Escape and restores focus', async () => {
    const opener = document.createElement('button')
    document.body.append(opener)
    opener.focus()
    const wrapper = mount(ConfirmDialog, { attachTo: document.body })
    await nextTick()
    const panel = wrapper.get('[role="dialog"]')
    expect(document.activeElement).toBe(panel.element)
    await panel.trigger('keydown', { key: 'Tab', shiftKey: true })
    const buttons = wrapper.findAll('button')
    expect(document.activeElement).toBe(buttons[buttons.length - 1].element)
    await buttons[buttons.length - 1].trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(buttons[0].element)
    await panel.trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    wrapper.unmount()
    expect(document.activeElement).toBe(opener)
    opener.remove()
  })

  it('cannot cancel or confirm while the action is in progress', async () => {
    const wrapper = mount(ConfirmDialog, { props: { loading: true }, attachTo: document.body })
    await nextTick()
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
    expect(wrapper.emitted('cancel')).toBeUndefined()
    wrapper.unmount()
  })
})

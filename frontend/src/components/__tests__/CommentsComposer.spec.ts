import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CommentsComposer from '../comments/CommentsComposer.vue'

function createWrapper(overrides: Partial<{
  isLoggedIn: boolean
  isSubmitting: boolean
  isUploading: boolean
  modelValue: string
  previewMode: boolean
}> = {}) {
  return mount(CommentsComposer, {
    props: {
      isLoggedIn: true,
      isSubmitting: false,
      isUploading: false,
      modelValue: '',
      previewMode: false,
      ...overrides,
    },
  })
}

describe('CommentsComposer submit controls', () => {
  it('disables sending for empty or whitespace-only content while keeping other controls available', async () => {
    const wrapper = createWrapper()
    const sendButton = wrapper.get('button.ui-button-primary')

    expect(sendButton.attributes('disabled')).toBeDefined()
    expect(wrapper.get('button[aria-pressed]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('input[type="file"]').attributes('disabled')).toBeUndefined()

    await wrapper.setProps({ modelValue: '  \n\t' })
    expect(sendButton.attributes('disabled')).toBeDefined()

    await wrapper.setProps({ modelValue: '有效评论' })
    expect(sendButton.attributes('disabled')).toBeUndefined()
    await sendButton.trigger('click')
    expect(wrapper.emitted('submit')).toHaveLength(1)
  })

  it('keeps sending disabled while submitting or uploading', async () => {
    const wrapper = createWrapper({ modelValue: '有效评论' })
    const sendButton = wrapper.get('button.ui-button-primary')

    await wrapper.setProps({ isSubmitting: true })
    expect(sendButton.attributes('disabled')).toBeDefined()

    await wrapper.setProps({ isSubmitting: false, isUploading: true })
    expect(sendButton.attributes('disabled')).toBeDefined()
  })
})

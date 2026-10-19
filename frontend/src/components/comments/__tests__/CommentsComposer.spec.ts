import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CommentsComposer from '../CommentsComposer.vue'

vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: () => ({}),
  renderMarkdown: (_renderer: unknown, value: string) => value || '',
}))

describe('CommentsComposer login entry', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('offers a login button and identifies the composer as the source', async () => {
    const received: { event?: CustomEvent } = {}
    const onLogin = (event: Event) => { received.event = event as CustomEvent }
    window.addEventListener('open-login-modal', onLogin)
    const wrapper = mount(CommentsComposer, {
      props: {
        isLoggedIn: false,
        modelValue: '',
        previewMode: false,
      },
    })

    expect(wrapper.text()).toContain('登录后可发表评论')
    await wrapper.get('button').trigger('click')

    expect(received.event?.detail).toEqual({ source: 'comment-composer' })
    window.removeEventListener('open-login-modal', onLogin)
    wrapper.unmount()
  })
})

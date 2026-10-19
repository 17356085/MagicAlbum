import { nextTick, reactive } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AiChat from '../AiChat.vue'
import { useRoute } from 'vue-router'

vi.mock('@/api/ai', () => ({
  createChatStream: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: vi.fn(),
}))

const route = reactive({ path: '/settings' })
const useRouteMock = vi.mocked(useRoute)

describe('AiChat settings placement', () => {
  beforeEach(() => {
    route.path = '/settings'
    useRouteMock.mockReturnValue(route as never)
  })

  it('adds a mobile-only settings hide class while retaining the launcher elsewhere', async () => {
    const wrapper = mount(AiChat)

    expect(wrapper.classes()).toContain('settings-mobile-hidden')

    route.path = '/discover'
    await nextTick()

    expect(wrapper.classes()).not.toContain('settings-mobile-hidden')
    wrapper.unmount()
  })

  it('opens from the account entry and restores focus when closed', async () => {
    route.path = '/me'
    const opener = document.createElement('button')
    document.body.appendChild(opener)
    opener.focus()
    const wrapper = mount(AiChat, { attachTo: document.body })
    window.dispatchEvent(new CustomEvent('open-ai-chat'))
    await nextTick()
    expect(wrapper.get('input[aria-label="聊天消息"]').element).toBe(document.activeElement)
    await wrapper.get('button[aria-label="关闭聊天"]').trigger('click')
    expect(document.activeElement).toBe(opener)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
    opener.remove()
  })
})

import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationsMenu from '../NotificationsMenu.vue'

const mocks = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/notifications', () => ({
  listNotifications: mocks.listNotifications,
  markNotificationRead: mocks.markNotificationRead,
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

async function createWrapper() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/settings', name: 'settings', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover')
  await router.isReady()
  const wrapper = mount(NotificationsMenu, {
    props: { isLoggedIn: true },
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: { RouterLink: RouterLinkStub },
    },
  })
  return { wrapper, router }
}

describe('NotificationsMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => (
      unread
        ? Promise.resolve({ items: [{ id: 2, title: '未读', content: '提醒', read: false }], page: 1, size: 1, total: 1 })
        : Promise.resolve({ items: [{ id: 1, title: '通知标题', content: '通知内容', read: false, link: '/threads/1' }], page: 1, size: 8, total: 1 })
    ))
    mocks.markNotificationRead.mockResolvedValue({ id: 1, read: true })
  })

  it('loads notifications when expanded and restores focus after Escape', async () => {
    const { wrapper } = await createWrapper()
    await flushPromises()
    const button = wrapper.get('button[aria-label="通知"]')
    ;(button.element as HTMLButtonElement).focus()

    await button.trigger('click')
    await flushPromises()
    ;(button.element as HTMLButtonElement).focus()

    expect(wrapper.get('#header-notifications-panel').text()).toContain('通知标题')
    expect(mocks.listNotifications).toHaveBeenCalledWith({ page: 1, size: 8 })

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()

    expect(wrapper.find('#header-notifications-panel').exists()).toBe(false)
    expect(document.activeElement).toBe(button.element)
    wrapper.unmount()
  })

  it('removes document listeners when unmounted', async () => {
    const { wrapper } = await createWrapper()
    await wrapper.get('button[aria-label="通知"]').trigger('click')
    await flushPromises()
    wrapper.unmount()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(document.querySelector('#header-notifications-panel')).toBeNull()
  })

  it('keeps the newest notification response when an older request resolves later', async () => {
    let recentRequest = 0
    let resolveFirst: ((value: unknown) => void) | undefined
    let resolveSecond: ((value: unknown) => void) | undefined
    const page = (title: string) => ({
      items: [{ id: title, title, content: title, read: true }],
      page: 1,
      size: 8,
      total: 1,
    })
    mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => {
      if (unread) return Promise.resolve({ items: [], page: 1, size: 1, total: 0 })
      recentRequest += 1
      return new Promise((resolve) => {
        if (recentRequest === 1) resolveFirst = resolve
        else resolveSecond = resolve
      })
    })

    const { wrapper } = await createWrapper()
    const button = wrapper.get('button[aria-label="通知"]')
    await button.trigger('click')
    await button.trigger('click')
    await button.trigger('click')

    expect(resolveFirst).toBeDefined()
    expect(resolveSecond).toBeDefined()
    resolveSecond?.(page('最新通知'))
    await flushPromises()
    expect(wrapper.get('#header-notifications-panel').text()).toContain('最新通知')

    resolveFirst?.(page('过期通知'))
    await flushPromises()
    expect(wrapper.get('#header-notifications-panel').text()).toContain('最新通知')
    expect(wrapper.get('#header-notifications-panel').text()).not.toContain('过期通知')
    wrapper.unmount()
  })
})

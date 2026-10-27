import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import { useLiveNotifications } from '@/composables/useLiveNotifications'
import NotificationsMenu from '../NotificationsMenu.vue'

const mocks = vi.hoisted(() => ({ list: vi.fn(), stream: vi.fn() }))
vi.mock('@/api/notifications', () => ({ listNotifications: mocks.list, markNotificationRead: vi.fn() }))
vi.mock('@/api/notificationStream', () => ({
  readNotificationStream: mocks.stream,
  NotificationStreamError: class extends Error { constructor(readonly status: number) { super() } },
}))

let wrapper: VueWrapper | undefined
const Host = defineComponent({
  setup() {
    const auth = useAuthStore()
    useLiveNotifications()
    return () => h(NotificationsMenu, { isLoggedIn: auth.isLoggedIn })
  },
})

function activate(id: number) {
  useAuthStore().applyAuthState({ accessToken: `account-${id}`, user: { id, username: `owner-${id}` } })
}

async function showHeader() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', component: { template: '<div />' } },
      { path: '/settings', name: 'settings', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover')
  await router.isReady()
  wrapper = mount(Host, { global: { plugins: [router] } })
  await flushPromises()
}

describe('notification connection and header integration', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    mocks.list.mockReset().mockResolvedValue({ items: [], page: 1, size: 1, total: 3 })
    mocks.stream.mockReset().mockImplementation((_token, _cursor, signal: AbortSignal) => (
      new Promise<void>((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
    ))
  })
  afterEach(async () => {
    wrapper?.unmount()
    wrapper = undefined
    await flushPromises()
    localStorage.clear()
  })

  it('makes one badge read per login, account change and visible resume', async () => {
    await showHeader()
    expect(mocks.list).not.toHaveBeenCalled()
    activate(1)
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith({ unread: true, page: 1, size: 1 })

    activate(2)
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect((mocks.stream.mock.calls[1][2] as AbortSignal).aborted).toBe(true)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(3)
    expect(mocks.stream).toHaveBeenCalledTimes(3)
  })

  it('initializes a header mounted after the initial connection resync', async () => {
    activate(1)
    await showHeader()
    expect(mocks.list).toHaveBeenCalledExactlyOnceWith({ unread: true, page: 1, size: 1 })
    expect(wrapper?.get('button[aria-label="通知"]').text()).toContain('3')
  })
})

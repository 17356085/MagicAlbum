import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import NotificationsMenu from '../NotificationsMenu.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  patch: vi.fn(),
}))

vi.mock('@/api/client', () => ({
  default: {
    get: mocks.get,
    patch: mocks.patch,
  },
}))

const apiMock = api as unknown as {
  get: ReturnType<typeof vi.fn>
  patch: ReturnType<typeof vi.fn>
}

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

const goodRow = {
  id: 42,
  type: 'reply',
  title: '可显示通知',
  content: '列表请求成功',
  read: false,
  createdAt: '2026-10-27T00:00:00Z',
  link: '/threads/42',
}

const badgeRow = {
  id: 99,
  type: 'system',
  title: '仅用于未读计数',
  content: '不应进入 recent 列表',
  read: false,
  createdAt: '2026-10-27T00:00:00Z',
  link: null,
}

function page(items: unknown[], size: number, total: number): Record<string, unknown> {
  return { items, page: 1, size, total, source: 'notification-service' }
}

function assertNoVueErrors(caseName: string, errors: unknown[]): void {
  const normalized = errors.map(error => error instanceof Error
    ? `${error.name}: ${error.message}`
    : String(error))
  console.log(`NOTIFICATION_VUE_ERROR_ASSERT ${JSON.stringify({ caseName, errorCount: errors.length, errors: normalized })}`)
  expect(errors).toEqual([])
}

async function mountMenu(vueErrors: unknown[]): Promise<VueWrapper> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/settings', name: 'settings', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover')
  await router.isReady()
  return mount(NotificationsMenu, {
    props: { isLoggedIn: true },
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: { RouterLink: RouterLinkStub },
      config: {
        errorHandler(error) {
          vueErrors.push(error)
        },
      },
    },
  })
}

describe('NotificationsMenu transport baseline', () => {
  const wrappers: VueWrapper[] = []

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('accessToken', 'account-a')
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
  })

  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
  })

  it('keeps initial badge, unread, and recent GET responses separate', async () => {
    const vueErrors: unknown[] = []
    apiMock.get.mockImplementation((_path: string, config: { params?: Record<string, unknown> }) => {
      if (config.params?.unread === true) {
        return Promise.resolve({ data: page([badgeRow], 1, 3) })
      }
      return Promise.resolve({ data: page([goodRow], 8, 1) })
    })

    const wrapper = await mountMenu(vueErrors)
    wrappers.push(wrapper)
    await flushPromises()

    expect(apiMock.get).toHaveBeenNthCalledWith(1, '/notifications', {
      params: { unread: true, page: 1, size: 1 },
    })
    expect(wrapper.get('button[aria-label="通知"]').text()).toContain('3')

    await wrapper.get('button[aria-label="通知"]').trigger('click')
    await flushPromises()

    expect(apiMock.get).toHaveBeenCalledTimes(3)
    expect(apiMock.get).toHaveBeenCalledWith('/notifications', { params: { page: 1, size: 8 } })
    expect(apiMock.get).toHaveBeenCalledWith('/notifications', { params: { unread: true, page: 1, size: 1 } })
    expect(wrapper.get('#header-notifications-panel').text()).toContain('可显示通知')
    expect(wrapper.get('#header-notifications-panel').text()).not.toContain('仅用于未读计数')
    assertNoVueErrors('separate-recent-and-unread', vueErrors)
  })

  it('turns a malformed null-row response into local error state without a Vue render error', async () => {
    const vueErrors: unknown[] = []
    apiMock.get.mockImplementation((_path: string, config: { params?: Record<string, unknown> }) => (
      config.params?.unread === true
        ? Promise.resolve({ data: page([badgeRow], 1, 1) })
        : Promise.resolve({ data: page([null], 8, 1) })
    ))

    const wrapper = await mountMenu(vueErrors)
    wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('button[aria-label="通知"]').trigger('click')
    await flushPromises()

    assertNoVueErrors('malformed-null-row', vueErrors)
    expect(wrapper.get('#header-notifications-panel').text()).toContain('通知加载失败')
  })

  it('recovers after closing a malformed-row error and reopening the menu', async () => {
    const vueErrors: unknown[] = []
    let recentAttempt = 0
    apiMock.get.mockImplementation((_path: string, config: { params?: Record<string, unknown> }) => {
      if (config.params?.unread === true) return Promise.resolve({ data: page([badgeRow], 1, 1) })
      recentAttempt += 1
      return recentAttempt === 1
        ? Promise.resolve({ data: page([null], 8, 1) })
        : Promise.resolve({ data: page([goodRow], 8, 1) })
    })

    const wrapper = await mountMenu(vueErrors)
    wrappers.push(wrapper)
    await flushPromises()

    const button = wrapper.get('button[aria-label="通知"]')
    await button.trigger('click')
    await flushPromises()

    expect(wrapper.get('#header-notifications-panel').text()).toContain('通知加载失败')
    expect(wrapper.get('#header-notifications-panel').find('button').exists()).toBe(false)

    await button.trigger('click')
    await button.trigger('click')
    await flushPromises()

    expect(wrapper.get('#header-notifications-panel').text()).toContain('可显示通知')
    expect(wrapper.get('#header-notifications-panel').text()).not.toContain('通知加载失败')
    expect(recentAttempt).toBe(2)
    assertNoVueErrors('malformed-row-close-reopen-recovery', vueErrors)
  })

  it('recovers after closing a failed transport state and reopening the menu', async () => {
    const vueErrors: unknown[] = []
    let recentAttempt = 0
    apiMock.get.mockImplementation((_path: string, config: { params?: Record<string, unknown> }) => {
      if (config.params?.unread === true) return Promise.resolve({ data: page([badgeRow], 1, 2) })
      recentAttempt += 1
      if (recentAttempt === 1) return Promise.reject(new Error('recent notification transport failed'))
      return Promise.resolve({ data: page([goodRow], 8, 1) })
    })

    const wrapper = await mountMenu(vueErrors)
    wrappers.push(wrapper)
    await flushPromises()

    const button = wrapper.get('button[aria-label="通知"]')
    await button.trigger('click')
    await flushPromises()
    expect(wrapper.get('#header-notifications-panel').text()).toContain('通知加载失败')

    await button.trigger('click')
    await button.trigger('click')
    await flushPromises()

    expect(wrapper.get('#header-notifications-panel').text()).toContain('可显示通知')
    expect(wrapper.get('#header-notifications-panel').text()).not.toContain('通知加载失败')
    expect(recentAttempt).toBe(2)
    assertNoVueErrors('failed-close-reopen-recovery', vueErrors)
  })
})

import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import NotificationsMenu from '../NotificationsMenu.vue'
import { NotificationReadAcknowledgementError } from '@/api/notifications'
import { signalNotificationChanges } from '@/composables/notificationSignals'

const mocks = vi.hoisted(() => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notifications')>()),
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

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('NotificationsMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => (
      unread
        ? Promise.resolve({ items: [{ id: 2, title: '未读', content: '提醒', read: false }], page: 1, size: 1, total: 1 })
        : Promise.resolve({ items: [{ id: 1, title: '通知标题', content: '通知内容', read: false, link: '/threads/1' }], page: 1, size: 8, total: 1 })
    ))
    mocks.markNotificationRead.mockResolvedValue({ id: 1, read: true })
  })

  afterEach(() => {
    localStorage.clear()
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

  it('coalesces resync and changed signals into one badge read', async () => {
    localStorage.setItem('accessToken', 'account-a')
    const { wrapper } = await createWrapper()
    await flushPromises()
    vi.clearAllMocks()

    signalNotificationChanges('resync')
    signalNotificationChanges('changed')
    await flushPromises()

    expect(mocks.listNotifications).toHaveBeenCalledTimes(1)
    expect(mocks.listNotifications).toHaveBeenCalledWith({ unread: true, page: 1, size: 1 })
    wrapper.unmount()
  })

  it('cancels a queued badge read when the menu unmounts', async () => {
    localStorage.setItem('accessToken', 'account-a')
    const { wrapper } = await createWrapper()
    await flushPromises()
    vi.clearAllMocks()

    signalNotificationChanges('changed')
    wrapper.unmount()
    await flushPromises()

    expect(mocks.listNotifications).not.toHaveBeenCalled()
  })

  it('does not let an older badge response overwrite the unread total from a full load', async () => {
    localStorage.setItem('accessToken', 'account-a')
    const { wrapper } = await createWrapper()
    await flushPromises()
    vi.clearAllMocks()

    const oldBadge = deferred<{ items: never[]; page: number; size: number; total: number }>()
    const fullBadge = deferred<{ items: never[]; page: number; size: number; total: number }>()
    let unreadCalls = 0
    mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => {
      if (unread) {
        unreadCalls += 1
        return unreadCalls === 1 ? oldBadge.promise : fullBadge.promise
      }
      return Promise.resolve({ items: [], page: 1, size: 8, total: 0 })
    })

    signalNotificationChanges('changed')
    await Promise.resolve()
    expect(unreadCalls).toBe(1)

    await wrapper.get('button[aria-label="通知"]').trigger('click')
    expect(unreadCalls).toBe(2)
    fullBadge.resolve({ items: [], page: 1, size: 1, total: 4 })
    await flushPromises()
    oldBadge.resolve({ items: [], page: 1, size: 1, total: 1 })
    await flushPromises()

    expect(wrapper.get('button[aria-label="通知"]').text()).toContain('4')
    wrapper.unmount()
  })

  it('does not let an older full-load unread response overwrite a newer badge', async () => {
    localStorage.setItem('accessToken', 'account-a')
    const { wrapper } = await createWrapper()
    await flushPromises()
    vi.clearAllMocks()

    const recent = deferred<{ items: never[]; page: number; size: number; total: number }>()
    const oldFullBadge = deferred<{ items: never[]; page: number; size: number; total: number }>()
    const newBadge = deferred<{ items: never[]; page: number; size: number; total: number }>()
    let unreadCalls = 0
    mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => {
      if (unread) {
        unreadCalls += 1
        return unreadCalls === 1 ? oldFullBadge.promise : newBadge.promise
      }
      return recent.promise
    })

    const button = wrapper.get('button[aria-label="通知"]')
    await button.trigger('click')
    expect(unreadCalls).toBe(1)
    await button.trigger('click')
    signalNotificationChanges('changed')
    await Promise.resolve()
    expect(unreadCalls).toBe(2)

    newBadge.resolve({ items: [], page: 1, size: 1, total: 7 })
    await flushPromises()
    recent.resolve({ items: [], page: 1, size: 8, total: 0 })
    oldFullBadge.resolve({ items: [], page: 1, size: 1, total: 2 })
    await flushPromises()

    expect(button.text()).toContain('7')
    wrapper.unmount()
  })

  it('applies successful reads for different notifications in reverse response order', async () => {
    const first = deferred<{ id: number; read: boolean }>()
    const second = deferred<{ id: number; read: boolean }>()
    mocks.markNotificationRead.mockImplementation((id: number) => id === 1 ? first.promise : second.promise)
    mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => Promise.resolve({
      items: unread ? [] : [1, 2].map(id => ({ id, title: `通知${id}`, read: false })),
      total: 2, page: 1, size: unread ? 1 : 8,
    }))
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      const rows = wrapper.findAll('li')
      await rows[0].get('button').trigger('click')
      await rows[1].get('button').trigger('click')
      second.resolve({ id: 2, read: true })
      await flushPromises()
      first.resolve({ id: 1, read: true })
      await flushPromises()
      expect(wrapper.findAll('li button')).toHaveLength(0)
    } finally { wrapper.unmount() }
  })

  it('coalesces same-notification read clicks in the same tick', async () => {
    const write = deferred<{ id: number; read: boolean }>()
    mocks.markNotificationRead.mockReturnValue(write.promise)
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      const readButton = wrapper.findAll('li button').find(button => button.text() === '已读')!

      const firstClick = readButton.trigger('click')
      const secondClick = readButton.trigger('click')
      await Promise.all([firstClick, secondClick])

      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(1)
      write.resolve({ id: 1, read: true })
      await flushPromises()
    } finally { wrapper.unmount() }
  })

  it('refreshes a list snapshot that predates a successful read without restoring unread state', async () => {
    const write = deferred<{ id: number; read: boolean }>()
    const stale = deferred<{ items: { id: number; title: string; read: boolean }[]; total: number }>()
    const fresh = deferred<{ items: { id: number; title: string; read: boolean }[]; total: number }>()
    const item = { id: 1, title: '待处理通知', read: false }
    mocks.markNotificationRead.mockReturnValue(write.promise)
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      let recentCalls = 0
      mocks.listNotifications.mockImplementation(({ unread }: { unread?: boolean } = {}) => {
        if (unread) return Promise.resolve({ items: [], total: 0 })
        recentCalls += 1
        return recentCalls === 1 ? stale.promise : fresh.promise
      })
      signalNotificationChanges('changed')
      await flushPromises()
      write.resolve({ id: 1, read: true })
      await flushPromises()
      stale.resolve({ items: [item], total: 1 })
      await flushPromises()
      expect(recentCalls).toBe(2)
      fresh.resolve({ items: [{ ...item, read: true }], total: 1 })
      await flushPromises()
      expect(wrapper.findAll('li button')).toHaveLength(0)
      expect(wrapper.text()).toContain('待处理通知')
    } finally { wrapper.unmount() }
  })

  it('navigates immediately while marking a notification read is still pending', async () => {
    const write = deferred<{ id: number; read: boolean }>()
    mocks.markNotificationRead.mockReturnValue(write.promise)
    const { wrapper, router } = await createWrapper()
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      await wrapper.get('li').trigger('click')
      expect(push).toHaveBeenCalledExactlyOnceWith('/threads/1')
      expect(wrapper.find('#header-notifications-panel').exists()).toBe(false)
      write.reject(new Error('write failed'))
      await flushPromises()
      expect(push).toHaveBeenCalledTimes(1)
    } finally { wrapper.unmount() }
  })

  it('keeps a successful read when its badge refresh fails', async () => {
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      mocks.listNotifications.mockRejectedValue(new Error('badge unavailable'))
      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      await flushPromises()
      expect(wrapper.text()).not.toContain('标记已读失败')
      expect(wrapper.text()).toContain('通知标题')
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('keeps an item unread and allows retry after an unconfirmed acknowledgement', async () => {
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      mocks.markNotificationRead.mockRejectedValueOnce(new NotificationReadAcknowledgementError())

      const readButton = wrapper.findAll('li button').find(button => button.text() === '已读')!
      await readButton.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('已读结果暂未确认，请重试')
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(true)
      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(1)

      mocks.markNotificationRead.mockResolvedValueOnce({ id: 1, read: true })
      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      await flushPromises()

      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(2)
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('shows the ordinary read failure separately while retaining the unread item', async () => {
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      mocks.markNotificationRead.mockRejectedValueOnce(new Error('gateway unavailable'))

      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('标记已读失败')
      expect(wrapper.text()).not.toContain('已读结果暂未确认，请重试')
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('changes only the existing item read state after a successful acknowledgement', async () => {
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      mocks.markNotificationRead.mockResolvedValueOnce({ id: 999, read: true, title: '不受信标题', link: '/evil' })

      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('通知标题')
      expect(wrapper.text()).not.toContain('不受信标题')
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('ignores a read response from an earlier session', async () => {
    localStorage.setItem('accessToken', 'account-a')
    const oldWrite = deferred<{ id: number; read: boolean }>()
    const currentWrite = deferred<{ id: number; read: boolean }>()
    mocks.markNotificationRead.mockReturnValueOnce(oldWrite.promise).mockReturnValueOnce(currentWrite.promise)
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      localStorage.setItem('accessToken', 'account-b')
      signalNotificationChanges('session')
      signalNotificationChanges('resync')
      await flushPromises()
      const calls = mocks.listNotifications.mock.calls.length
      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(2)

      oldWrite.resolve({ id: 1, read: true })
      await flushPromises()
      expect(mocks.listNotifications).toHaveBeenCalledTimes(calls)
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(true)

      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(2)
      currentWrite.resolve({ id: 1, read: true })
      await flushPromises()
    } finally { wrapper.unmount() }
  })

  it('allows retry after a same-notification read failure', async () => {
    const first = deferred<{ id: number; read: boolean }>()
    const second = deferred<{ id: number; read: boolean }>()
    mocks.markNotificationRead.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { wrapper } = await createWrapper()
    try {
      await flushPromises()
      await wrapper.get('button[aria-label="通知"]').trigger('click')
      await flushPromises()
      const button = wrapper.findAll('li button').find(button => button.text() === '已读')!
      await button.trigger('click')
      first.reject(new Error('earlier request failed'))
      await flushPromises()

      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(1)
      expect(wrapper.text()).toContain('标记已读失败')
      await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
      expect(mocks.markNotificationRead).toHaveBeenCalledTimes(2)
      second.resolve({ id: 1, read: true })
      await flushPromises()
      expect(wrapper.text()).toContain('通知标题')
      expect(wrapper.findAll('li button').some(button => button.text() === '已读')).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('does not refresh or navigate after an in-flight read outlives the menu', async () => {
    const write = deferred<{ id: number; read: boolean }>()
    mocks.markNotificationRead.mockReturnValue(write.promise)
    const { wrapper, router } = await createWrapper()
    const push = vi.spyOn(router, 'push')
    await flushPromises()
    await wrapper.get('button[aria-label="通知"]').trigger('click')
    await flushPromises()
    await wrapper.findAll('li button').find(button => button.text() === '已读')!.trigger('click')
    wrapper.unmount()
    const calls = mocks.listNotifications.mock.calls.length
    write.resolve({ id: 1, read: true })
    await flushPromises()
    expect(mocks.listNotifications).toHaveBeenCalledTimes(calls)
    expect(push).not.toHaveBeenCalled()
  })
})

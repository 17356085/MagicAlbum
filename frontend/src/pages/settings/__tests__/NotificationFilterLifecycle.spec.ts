import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsNotificationsTab from '../SettingsNotificationsTab.vue'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings } from '@/api/notificationSettings'
import { signalNotificationChanges } from '@/composables/notificationSignals'
import type { NotificationItem, PageResult } from '@/types'

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => routerMock }))

vi.mock('@/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notifications')>()),
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/notificationSettings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notificationSettings')>()),
  getMyNotificationSettings: vi.fn(),
}))

const listNotificationsMock = vi.mocked(listNotifications)
const markNotificationReadMock = vi.mocked(markNotificationRead)
const getMyNotificationSettingsMock = vi.mocked(getMyNotificationSettings)
const mountedWrappers: VueWrapper[] = []

const defaultSettings = {
  inApp: { reply: true, mention: true, like: true, follow: true, system: true as const },
  email: { enabled: false as const, frequency: 'instant' },
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

function page(title: string, read = false, id = 7): PageResult<NotificationItem> {
  return {
    items: [{
      id,
      type: 'reply',
      title,
      content: `${title} 内容`,
      read,
      createdAt: '2026-10-01T00:00:00Z',
      link: `/threads/${id}`,
    }],
    page: 1,
    size: 20,
    total: 1,
  }
}

function emptyPage(): PageResult<NotificationItem> {
  return { items: [], page: 1, size: 20, total: 0 }
}

function mountNotificationsTab(): VueWrapper {
  const wrapper = mount(SettingsNotificationsTab)
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('SettingsNotificationsTab notification filter lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('accessToken', 'account-a')
    listNotificationsMock.mockResolvedValue(emptyPage())
    markNotificationReadMock.mockResolvedValue({ id: 7, read: true })
    getMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
  })

  afterEach(() => {
    mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
  })

  it('automatically reads the current type and unread controls without a refresh click', async () => {
    const wrapper = mountNotificationsTab()
    await flushPromises()

    await wrapper.get('#notification-type-filter').setValue('reply')
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenLastCalledWith({ type: 'reply', unread: false, page: 1, size: 20 })

    await wrapper.get('label.ui-button input[type="checkbox"]').setValue(true)
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenLastCalledWith({ type: 'reply', unread: true, page: 1, size: 20 })
  })

  it('accepts only the latest successful filter response and ignores an older failure/finally', async () => {
    const oldRequest = deferred<PageResult<NotificationItem>>()
    const latestRequest = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock
      .mockResolvedValueOnce(emptyPage())
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(latestRequest.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()

    const typeFilter = wrapper.get('#notification-type-filter')
    await typeFilter.setValue('reply')
    await typeFilter.setValue('mention')
    expect(listNotificationsMock).toHaveBeenCalledTimes(3)

    oldRequest.reject(new Error('旧筛选失败'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧筛选结果')
    expect(wrapper.text()).not.toContain('加载通知失败')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    latestRequest.resolve(page('最新筛选结果'))
    await flushPromises()
    expect(wrapper.text()).toContain('最新筛选结果')
    expect(wrapper.text()).not.toContain('加载通知失败')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(false)
  })

  it('accepts only the latest failed filter response and ignores an older success/finally', async () => {
    const oldRequest = deferred<PageResult<NotificationItem>>()
    const latestRequest = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock
      .mockResolvedValueOnce(emptyPage())
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(latestRequest.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()

    const typeFilter = wrapper.get('#notification-type-filter')
    await typeFilter.setValue('reply')
    await typeFilter.setValue('mention')
    expect(listNotificationsMock).toHaveBeenCalledTimes(3)

    oldRequest.resolve(page('旧筛选结果'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧筛选结果')
    expect(wrapper.text()).not.toContain('加载通知失败')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    latestRequest.reject(new Error('最新筛选失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('加载通知失败')
    expect(wrapper.text()).not.toContain('旧筛选结果')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(false)
  })

  it('starts a new read after an acknowledgement and prevents an old list response from undoing it', async () => {
    const oldListRequest = deferred<PageResult<NotificationItem>>()
    const acknowledgedListRequest = deferred<PageResult<NotificationItem>>()
    const acknowledgement = deferred<{ id: number; read: true }>()
    listNotificationsMock.mockReset()
    listNotificationsMock
      .mockResolvedValueOnce(page('待处理通知'))
      .mockReturnValueOnce(oldListRequest.promise)
      .mockReturnValueOnce(acknowledgedListRequest.promise)
    markNotificationReadMock.mockReturnValueOnce(acknowledgement.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()

    const readButton = wrapper.findAll('li button').find(button => button.text() === '标记为已读')!
    await readButton.trigger('click')
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
    signalNotificationChanges('changed')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    acknowledgement.resolve({ id: 7, read: true })
    await flushPromises()

    expect(listNotificationsMock).toHaveBeenCalledTimes(3)
    oldListRequest.resolve(page('旧列表仍未读'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧列表仍未读')

    acknowledgedListRequest.resolve(page('确认后的列表', true))
    await flushPromises()
    expect(wrapper.text()).toContain('确认后的列表')
    expect(wrapper.text()).toContain('已读')
    expect(wrapper.findAll('li button').some(button => button.text() === '标记为已读')).toBe(false)
  })

  it('does not start a read after account switch or destroy, and ignores the old response', async () => {
    const oldAccountList = deferred<PageResult<NotificationItem>>()
    const currentAccountList = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock.mockReturnValueOnce(oldAccountList.promise).mockReturnValueOnce(currentAccountList.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)

    localStorage.setItem('accessToken', 'account-b')
    signalNotificationChanges('session')
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
    signalNotificationChanges('resync')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    oldAccountList.resolve(page('旧账号迟到列表'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧账号迟到列表')

    const vm = wrapper.vm as unknown as { notifList: PageResult<NotificationItem>; loadNotifications: () => Promise<void> }
    wrapper.unmount()
    await vm.loadNotifications()
    signalNotificationChanges('changed')
    currentAccountList.resolve(page('销毁后迟到列表'))
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    expect(vm.notifList.items).toEqual([])
  })

  it('rejects an old response when controls change before the watcher flushes', async () => {
    const oldRequest = deferred<PageResult<NotificationItem>>()
    const nextRequest = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(nextRequest.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)

    const typeFilter = wrapper.get('#notification-type-filter').element as HTMLSelectElement
    const unreadFilter = wrapper.get('label.ui-button input[type="checkbox"]').element as HTMLInputElement
    oldRequest.resolve(page('旧查询响应'))
    typeFilter.value = 'reply'
    unreadFilter.checked = true
    typeFilter.dispatchEvent(new Event('change'))
    unreadFilter.dispatchEvent(new Event('change'))
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
    await flushPromises()

    expect(wrapper.text()).not.toContain('旧查询响应')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    expect(listNotificationsMock).toHaveBeenLastCalledWith({ type: 'reply', unread: true, page: 1, size: 20 })
    expect((wrapper.vm as unknown as { notifList: PageResult<NotificationItem> }).notifList.items).toEqual([])
    nextRequest.resolve(emptyPage())
    await flushPromises()
  })
})

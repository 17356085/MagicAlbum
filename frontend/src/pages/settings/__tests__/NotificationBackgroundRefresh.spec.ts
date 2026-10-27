import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsNotificationsTab from '../SettingsNotificationsTab.vue'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings } from '@/api/notificationSettings'
import { signalNotificationChanges } from '@/composables/notificationSignals'
import type { NotificationItem, PageResult } from '@/types'

vi.mock('@/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notifications')>()),
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/notificationSettings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notificationSettings')>()),
  getMyNotificationSettings: vi.fn(),
}))

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))

const listNotificationsMock = vi.mocked(listNotifications)
const markNotificationReadMock = vi.mocked(markNotificationRead)
const getMyNotificationSettingsMock = vi.mocked(getMyNotificationSettings)

const defaultSettings = {
  inApp: { reply: true, mention: true, like: true, follow: true, system: true as const },
  email: { enabled: false as const, frequency: 'instant' },
}

type NotificationPage = Awaited<ReturnType<typeof listNotifications>>

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
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

function setVisibility(value: 'visible' | 'hidden'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value })
}

function mountNotificationsTab(): VueWrapper {
  return mount(SettingsNotificationsTab)
}

describe('SettingsNotificationsTab background notification refresh', () => {
  const wrappers: VueWrapper[] = []

  beforeEach(() => {
    vi.clearAllMocks()
    listNotificationsMock.mockReset()
    markNotificationReadMock.mockReset()
    getMyNotificationSettingsMock.mockReset()
    localStorage.setItem('accessToken', 'account-a')
    setVisibility('visible')
    getMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
    markNotificationReadMock.mockImplementation(async (id) => ({ id, read: true }))
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    localStorage.clear()
    setVisibility('visible')
  })

  it('coalesces same-query changed signals and ignores the queued old success', async () => {
    const initial = deferred<NotificationPage>()
    const followUp = deferred<NotificationPage>()
    listNotificationsMock.mockReturnValueOnce(initial.promise).mockReturnValueOnce(followUp.promise)
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)

    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
    signalNotificationChanges('changed')
    signalNotificationChanges('changed')
    signalNotificationChanges('changed')
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    initial.resolve(page('队列中的旧成功'))
    await flushPromises()

    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('队列中的旧成功')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    followUp.resolve(page('后续最新数据'))
    await flushPromises()
    expect(wrapper.text()).toContain('后续最新数据')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(false)
  })

  it('ignores the queued old error and keeps the follow-up request busy', async () => {
    const initial = deferred<NotificationPage>()
    const followUp = deferred<NotificationPage>()
    listNotificationsMock.mockReturnValueOnce(initial.promise).mockReturnValueOnce(followUp.promise)
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)

    signalNotificationChanges('changed')
    initial.reject(new Error('队列中的旧错误'))
    await flushPromises()

    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('加载通知失败')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    followUp.resolve(page('错误后的最新数据'))
    await flushPromises()
    expect(wrapper.text()).toContain('错误后的最新数据')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(false)
  })

  it('lets a manual refresh and a query change absorb a queued background refresh', async () => {
    const initial = deferred<NotificationPage>()
    const manual = deferred<NotificationPage>()
    const filtered = deferred<NotificationPage>()
    listNotificationsMock
      .mockReturnValueOnce(initial.promise)
      .mockReturnValueOnce(manual.promise)
      .mockReturnValueOnce(filtered.promise)
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)

    signalNotificationChanges('changed')
    await wrapper.findAll('button').find((button) => button.text() === '刷新')!.trigger('click')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)

    initial.resolve(page('旧手动前数据'))
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    await wrapper.get('#notification-type-filter').setValue('reply')
    expect(listNotificationsMock).toHaveBeenCalledTimes(3)
    expect(listNotificationsMock).toHaveBeenLastCalledWith({ type: 'reply', unread: false, page: 1, size: 20 })

    manual.resolve(page('旧手动请求结果'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧手动请求结果')
    expect(listNotificationsMock).toHaveBeenCalledTimes(3)

    filtered.resolve(page('筛选后的最新数据'))
    await flushPromises()
    expect(wrapper.text()).toContain('筛选后的最新数据')
  })

  it('starts the acknowledgement reload while an old background read is still in flight', async () => {
    const oldBackground = deferred<NotificationPage>()
    const acknowledgement = deferred<{ id: number; read: true }>()
    const afterAcknowledgement = deferred<NotificationPage>()
    listNotificationsMock
      .mockResolvedValueOnce(page('待处理通知'))
      .mockReturnValueOnce(oldBackground.promise)
      .mockReturnValueOnce(afterAcknowledgement.promise)
    markNotificationReadMock.mockReturnValueOnce(acknowledgement.promise)
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)
    await flushPromises()

    await wrapper.findAll('li button').find((button) => button.text() === '标记为已读')!.trigger('click')
    expect(markNotificationReadMock).toHaveBeenCalledTimes(1)
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)

    signalNotificationChanges('changed')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)

    acknowledgement.resolve({ id: 7, read: true })
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(3)

    oldBackground.resolve(page('迟到的旧未读数据'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('迟到的旧未读数据')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(true)

    afterAcknowledgement.resolve(page('已确认的最新数据', true))
    await flushPromises()
    expect(wrapper.text()).toContain('已确认的最新数据')
    expect(wrapper.findAll('li button').some((button) => button.text() === '标记为已读')).toBe(false)
  })

  it('does not tail an old queued read after token drift and session invalidation', async () => {
    const oldAccount = deferred<NotificationPage>()
    const currentAccount = deferred<NotificationPage>()
    listNotificationsMock.mockReturnValueOnce(oldAccount.promise).mockReturnValueOnce(currentAccount.promise)
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)

    signalNotificationChanges('changed')
    localStorage.setItem('accessToken', 'account-b')
    signalNotificationChanges('changed')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)

    oldAccount.resolve(page('旧账号队列数据'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧账号队列数据')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)

    signalNotificationChanges('session')
    currentAccount.resolve(page('会话失效后的迟到数据'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('会话失效后的迟到数据')
    expect((wrapper.vm as unknown as { notifList: PageResult<NotificationItem> }).notifList.items).toEqual([])
  })

  it('does not tail while hidden and resumes with a visible resync', async () => {
    const initial = deferred<NotificationPage>()
    const visibleResync = deferred<NotificationPage>()
    listNotificationsMock.mockReturnValueOnce(initial.promise).mockReturnValueOnce(visibleResync.promise)
    const wrapper = mountNotificationsTab()
    wrappers.push(wrapper)

    signalNotificationChanges('changed')
    setVisibility('hidden')
    initial.resolve(page('隐藏期间旧数据'))
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('隐藏期间旧数据')
    expect((wrapper.vm as unknown as { notifLoading: boolean }).notifLoading).toBe(false)

    setVisibility('visible')
    signalNotificationChanges('resync')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)
    visibleResync.resolve(page('恢复后的最新数据'))
    await flushPromises()
    expect(wrapper.text()).toContain('恢复后的最新数据')
  })

  it('invalidates a queued read on unmount', async () => {
    const initial = deferred<NotificationPage>()
    listNotificationsMock.mockReturnValueOnce(initial.promise)
    const wrapper = mountNotificationsTab()
    signalNotificationChanges('changed')
    wrapper.unmount()

    initial.resolve(page('销毁后的迟到数据'))
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(1)
  })
})

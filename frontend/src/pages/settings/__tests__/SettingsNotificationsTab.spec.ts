import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsConnectedTab from '../SettingsConnectedTab.vue'
import SettingsNotificationsTab from '../SettingsNotificationsTab.vue'
import { NotificationReadAcknowledgementError } from '@/api/notifications'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings, updateMyNotificationSettings } from '@/api/notificationSettings'
import { listConnectedAccounts } from '@/api/connected'
import type { NotificationItem, PageResult } from '@/types'
import { signalNotificationChanges } from '@/composables/notificationSignals'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/notifications', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notifications')>()),
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/notificationSettings', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/notificationSettings')>()),
  getMyNotificationSettings: vi.fn(),
  updateMyNotificationSettings: vi.fn(),
}))

vi.mock('@/api/connected', () => ({
  listConnectedAccounts: vi.fn(),
}))

const routerMock = vi.hoisted(() => ({ push: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => routerMock }))

const listNotificationsMock = vi.mocked(listNotifications)
const markNotificationReadMock = vi.mocked(markNotificationRead)
const getMyNotificationSettingsMock = vi.mocked(getMyNotificationSettings)
const updateMyNotificationSettingsMock = vi.mocked(updateMyNotificationSettings)
const listConnectedAccountsMock = vi.mocked(listConnectedAccounts)
const mountedNotificationWrappers: VueWrapper[] = []

function mountNotificationsTab(): VueWrapper {
  const wrapper = mount(SettingsNotificationsTab)
  mountedNotificationWrappers.push(wrapper)
  return wrapper
}

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

function result(id: number, title: string): PageResult<NotificationItem> {
  return {
    items: [{
      id,
      type: 'reply',
      title,
      content: `${title} 内容`,
      read: false,
      createdAt: '2026-10-01T00:00:00Z',
      link: `/threads/${id}`,
    }],
    page: 1,
    size: 20,
    total: 1,
  }
}

describe('SettingsNotificationsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.setItem('accessToken', 'account-a')
    listNotificationsMock.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    markNotificationReadMock.mockImplementation(async (id) => ({ id, read: true }))
    getMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
    updateMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
  })

  afterEach(() => {
    mountedNotificationWrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
  })

  it('exposes only supported preferences and sends fixed system/email contract values', async () => {
    const wrapper = mountNotificationsTab()
    await flushPromises()

    expect(wrapper.find('#notification-preference-reply').exists()).toBe(true)
    expect(wrapper.find('#notification-preference-mention').exists()).toBe(true)
    expect(wrapper.find('#notification-preference-like').exists()).toBe(true)
    expect(wrapper.find('#notification-preference-follow').exists()).toBe(true)
    expect(wrapper.find('#notification-preference-system').exists()).toBe(false)
    expect(wrapper.find('input[type="email"]').exists()).toBe(false)
    expect(wrapper.find('select option[value="daily"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('系统通知由平台统一发送，当前不可关闭。')
    expect(wrapper.text()).toContain('邮件投递尚未实现，当前仅支持站内通知。')

    await wrapper.find('#notification-preference-reply').setValue(false)
    const dirtyEvents = wrapper.emitted('dirty-change') || []
    expect(dirtyEvents[dirtyEvents.length - 1]).toEqual([true])

    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(updateMyNotificationSettingsMock).toHaveBeenCalledWith({
      inApp: { reply: false, mention: true, like: true, follow: true, system: true },
      email: { enabled: false, frequency: 'instant' },
    })
    const savedEvents = wrapper.emitted('dirty-change') || []
    expect(savedEvents[savedEvents.length - 1]).toEqual([false])
  })

  it('preserves edits made while saving and sends them on the next save', async () => {
    const firstSave = deferred<typeof defaultSettings>()
    const secondSave = deferred<typeof defaultSettings>()
    updateMyNotificationSettingsMock.mockReset()
    updateMyNotificationSettingsMock
      .mockReturnValueOnce(firstSave.promise)
      .mockReturnValueOnce(secondSave.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()

    await wrapper.get('#notification-preference-reply').setValue(false)
    await wrapper.get('button').trigger('click')
    expect(updateMyNotificationSettingsMock).toHaveBeenCalledWith({
      inApp: { reply: false, mention: true, like: true, follow: true, system: true },
      email: { enabled: false, frequency: 'instant' },
    })

    await wrapper.get('#notification-preference-reply').setValue(true)
    await wrapper.get('#notification-preference-mention').setValue(false)
    firstSave.resolve({
      ...defaultSettings,
      inApp: { ...defaultSettings.inApp, reply: false, mention: true },
    })
    await flushPromises()

    expect(wrapper.get('#notification-preference-reply').element).toHaveProperty('checked', true)
    expect(wrapper.get('#notification-preference-mention').element).toHaveProperty('checked', false)
    const dirtyEvents = wrapper.emitted('dirty-change') || []
    expect(dirtyEvents[dirtyEvents.length - 1]).toEqual([true])

    await wrapper.get('button').trigger('click')
    expect(updateMyNotificationSettingsMock).toHaveBeenLastCalledWith({
      inApp: { reply: true, mention: false, like: true, follow: true, system: true },
      email: { enabled: false, frequency: 'instant' },
    })
    secondSave.resolve({
      ...defaultSettings,
      inApp: { ...defaultSettings.inApp, reply: true, mention: false },
    })
    await flushPromises()

    const finalDirtyEvents = wrapper.emitted('dirty-change') || []
    expect(finalDirtyEvents[finalDirtyEvents.length - 1]).toEqual([false])
  })

  it('keeps the latest edits after a preference save fails', async () => {
    const failedSave = deferred<typeof defaultSettings>()
    updateMyNotificationSettingsMock.mockReset()
    updateMyNotificationSettingsMock.mockReturnValueOnce(failedSave.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()

    await wrapper.get('#notification-preference-reply').setValue(false)
    await wrapper.get('button').trigger('click')
    await wrapper.get('#notification-preference-reply').setValue(true)
    await wrapper.get('#notification-preference-like').setValue(false)
    failedSave.reject(new Error('save unavailable'))
    await flushPromises()

    expect(wrapper.get('#notification-preference-reply').element).toHaveProperty('checked', true)
    expect(wrapper.get('#notification-preference-like').element).toHaveProperty('checked', false)
    expect(wrapper.text()).toContain('保存失败，请稍后重试。')
    const dirtyEvents = wrapper.emitted('dirty-change') || []
    expect(dirtyEvents[dirtyEvents.length - 1]).toEqual([true])
  })

  it('does not let an old-account save response overwrite current preferences', async () => {
    const oldSave = deferred<typeof defaultSettings>()
    const currentSettings = deferred<typeof defaultSettings>()
    updateMyNotificationSettingsMock.mockReset()
    updateMyNotificationSettingsMock.mockReturnValueOnce(oldSave.promise)
    getMyNotificationSettingsMock.mockReset()
    getMyNotificationSettingsMock
      .mockResolvedValueOnce(defaultSettings)
      .mockReturnValueOnce(currentSettings.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()

    await wrapper.get('#notification-preference-reply').setValue(false)
    await wrapper.get('button').trigger('click')
    localStorage.setItem('accessToken', 'account-b')
    signalNotificationChanges('session')
    signalNotificationChanges('resync')
    currentSettings.resolve({
      ...defaultSettings,
      inApp: { ...defaultSettings.inApp, reply: true, mention: false },
    })
    await flushPromises()

    oldSave.resolve({
      ...defaultSettings,
      inApp: { ...defaultSettings.inApp, reply: false, mention: true },
    })
    await flushPromises()

    expect(wrapper.get('#notification-preference-reply').element).toHaveProperty('checked', true)
    expect(wrapper.get('#notification-preference-mention').element).toHaveProperty('checked', false)
  })

  it('blocks saving and offers retry when preference loading fails', async () => {
    getMyNotificationSettingsMock.mockRejectedValueOnce(new Error('settings unavailable'))
    const wrapper = mountNotificationsTab()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法编辑或保存')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存通知设置'))
    expect(saveButton).toBeDefined()
    expect(saveButton!.attributes('disabled')).toBeDefined()

    getMyNotificationSettingsMock.mockResolvedValueOnce(defaultSettings)
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(wrapper.find('#notification-preference-follow').exists()).toBe(true)
    const retrySaveButton = wrapper.findAll('button').find((button) => button.text().includes('保存通知设置'))
    expect(retrySaveButton?.attributes('disabled')).toBeUndefined()
  })

  it('uses semantic notification actions with Chinese type, status, and time labels', async () => {
    listNotificationsMock.mockResolvedValueOnce({
      items: [{
        id: 7,
        type: 'reply',
        title: '有人回复了你',
        content: '请查看新的回复',
        read: false,
        createdAt: '2026-10-01T00:00:00Z',
        link: '/threads/7',
      }],
      page: 1,
      size: 20,
      total: 1,
    })
    const wrapper = mountNotificationsTab()
    await flushPromises()

    expect(wrapper.text()).toContain('回复通知')
    expect(wrapper.text()).toContain('未读')
    expect(wrapper.find('time').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="查看有人回复了你"]').exists()).toBe(true)
    expect(wrapper.find('li').attributes('role')).toBeUndefined()

    await wrapper.find('li').trigger('click')
    expect(markNotificationReadMock).not.toHaveBeenCalled()
  })

  it('keeps the newest notification result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<NotificationItem>>()
    const newRequest = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = mountNotificationsTab()
    await flushPromises()
    const refreshButton = wrapper.findAll('button').find((button) => button.text() === '刷新')!
    await refreshButton.trigger('click')
    expect(listNotificationsMock).toHaveBeenCalledTimes(2)

    newRequest.resolve(result(2, '新通知'))
    await flushPromises()
    oldRequest.resolve(result(1, '旧通知'))
    await flushPromises()

    expect(wrapper.text()).toContain('新通知')
    expect(wrapper.text()).not.toContain('旧通知')
    expect(wrapper.text()).not.toContain('正在加载通知...')
  })

  it('keeps loading while an older notification request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<NotificationItem>>()
    const newRequest = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = mountNotificationsTab()
    await flushPromises()
    const refreshButton = wrapper.findAll('button').find((button) => button.text() === '刷新')!
    await refreshButton.trigger('click')

    oldRequest.reject(new Error('stale failure'))
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载通知...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result(2, '新通知'))
    await flushPromises()
    expect(wrapper.text()).toContain('新通知')
    expect(wrapper.text()).not.toContain('正在加载通知...')
  })

  it('refreshes a marked notification with the current filter', async () => {
    listNotificationsMock.mockReset()
    listNotificationsMock.mockResolvedValue(result(7, '待处理通知'))
    markNotificationReadMock.mockResolvedValueOnce({ id: 7, read: true })

    const wrapper = mountNotificationsTab()
    await flushPromises()
    await wrapper.get('#notification-type-filter').setValue('reply')
    await wrapper.find('label.ui-button input[type="checkbox"]').setValue(true)
    await wrapper.findAll('button').find((button) => button.text() === '标记为已读')!.trigger('click')
    await flushPromises()

    expect(listNotificationsMock).toHaveBeenLastCalledWith({ type: 'reply', unread: true, page: 1, size: 20 })
    expect(markNotificationReadMock).toHaveBeenCalledWith(7)
  })

  it('ignores an in-flight notification response after unmount', async () => {
    const request = deferred<PageResult<NotificationItem>>()
    listNotificationsMock.mockReset()
    listNotificationsMock.mockReturnValueOnce(request.promise)

    const wrapper = mountNotificationsTab()
    await flushPromises()
    const vm = wrapper.vm as unknown as { notifList: PageResult<NotificationItem>; notifLoading: boolean }
    wrapper.unmount()

    request.resolve(result(1, '卸载后通知'))
    await flushPromises()

    expect(vm.notifList.items).toEqual([])
    expect(vm.notifLoading).toBe(true)
  })

  it('clears old-account preference state and reloads it on a visible resync', async () => {
    const staleSettings = deferred<typeof defaultSettings>()
    const currentSettings = deferred<typeof defaultSettings>()
    getMyNotificationSettingsMock.mockReset()
    getMyNotificationSettingsMock.mockReturnValueOnce(staleSettings.promise).mockReturnValueOnce(currentSettings.promise)

    const wrapper = mountNotificationsTab()
    await flushPromises()
    expect(getMyNotificationSettingsMock).toHaveBeenCalledTimes(1)

    localStorage.setItem('accessToken', 'account-b')
    signalNotificationChanges('session')
    signalNotificationChanges('resync')
    expect(getMyNotificationSettingsMock).toHaveBeenCalledTimes(2)

    staleSettings.resolve({
      ...defaultSettings,
      inApp: { ...defaultSettings.inApp, reply: false },
    })
    currentSettings.resolve(defaultSettings)
    await flushPromises()

    expect(wrapper.get('#notification-preference-reply').element).toHaveProperty('checked', true)
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存通知设置'))
    expect(saveButton?.attributes('disabled')).toBeUndefined()
  })

  it('does not start a second preference request while the same-account request is pending', async () => {
    const pendingSettings = deferred<typeof defaultSettings>()
    getMyNotificationSettingsMock.mockReset()
    getMyNotificationSettingsMock.mockReturnValueOnce(pendingSettings.promise)

    const wrapper = mountNotificationsTab()
    await flushPromises()
    expect(getMyNotificationSettingsMock).toHaveBeenCalledTimes(1)
    const notificationReads = listNotificationsMock.mock.calls.length

    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    signalNotificationChanges('resync')
    expect(listNotificationsMock).toHaveBeenCalledTimes(notificationReads)
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    signalNotificationChanges('resync')
    await flushPromises()

    expect(getMyNotificationSettingsMock).toHaveBeenCalledTimes(1)
    expect(listNotificationsMock).toHaveBeenCalledTimes(notificationReads + 1)
    pendingSettings.resolve(defaultSettings)
    await flushPromises()
    expect(wrapper.find('#notification-preference-reply').exists()).toBe(true)
  })

  it('keeps independently successful reads when different notifications resolve in reverse order', async () => {
    const first = deferred<{ id: number; read: true }>()
    const second = deferred<{ id: number; read: true }>()
    const items = [result(1, '第一条').items[0], result(2, '第二条').items[0]]
    listNotificationsMock.mockImplementation(async () => ({ items: items.map(item => ({ ...item })), total: 2, page: 1, size: 20 }))
    markNotificationReadMock.mockImplementation(id => id === 1 ? first.promise : second.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()
    const buttons = wrapper.findAll('li button').filter(button => button.text() === '标记为已读')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')
    items[1].read = true
    second.resolve({ id: 2, read: true })
    await flushPromises()
    items[0].read = true
    first.resolve({ id: 1, read: true })
    await flushPromises()
    expect(wrapper.findAll('li button').filter(button => button.text() === '标记为已读')).toHaveLength(0)
    expect(listNotificationsMock).toHaveBeenCalledTimes(3)
  })

  it('coalesces same-notification read clicks in the same tick', async () => {
    const write = deferred<{ id: number; read: true }>()
    listNotificationsMock.mockResolvedValue(result(7, '同一通知'))
    markNotificationReadMock.mockReturnValue(write.promise)
    const wrapper = mountNotificationsTab()
    try {
      await flushPromises()
      const readButton = wrapper.findAll('li button').find(button => button.text() === '标记为已读')!

      const firstClick = readButton.trigger('click')
      const secondClick = readButton.trigger('click')
      await Promise.all([firstClick, secondClick])

      expect(markNotificationReadMock).toHaveBeenCalledTimes(1)
      write.resolve({ id: 7, read: true })
      await flushPromises()
    } finally { wrapper.unmount() }
  })

  it('navigates before the read write completes and never navigates again on a late failure', async () => {
    const write = deferred<{ id: number; read: true }>()
    listNotificationsMock.mockResolvedValue(result(7, '即时查看'))
    markNotificationReadMock.mockReturnValue(write.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()
    await wrapper.get('button[aria-label="查看即时查看"]').trigger('click')
    expect(routerMock.push).toHaveBeenCalledExactlyOnceWith('/threads/7')
    write.reject(new Error('read unavailable'))
    await flushPromises()
    expect(routerMock.push).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('标记已读失败')
  })

  it('ignores a completed read after the account changes', async () => {
    const oldWrite = deferred<{ id: number; read: true }>()
    const currentWrite = deferred<{ id: number; read: true }>()
    listNotificationsMock.mockResolvedValue(result(7, '旧账号'))
    markNotificationReadMock.mockReturnValueOnce(oldWrite.promise).mockReturnValueOnce(currentWrite.promise)
    const wrapper = mountNotificationsTab()
    await flushPromises()
    await wrapper.findAll('li button').find(button => button.text() === '标记为已读')!.trigger('click')
    localStorage.setItem('accessToken', 'account-b')
    signalNotificationChanges('session')
    listNotificationsMock.mockResolvedValue(result(7, '新账号'))
    signalNotificationChanges('resync')
    await flushPromises()
    const calls = listNotificationsMock.mock.calls.length
    await wrapper.findAll('li button').find(button => button.text() === '标记为已读')!.trigger('click')
    expect(markNotificationReadMock).toHaveBeenCalledTimes(2)

    oldWrite.resolve({ id: 7, read: true })
    await flushPromises()
    expect(listNotificationsMock).toHaveBeenCalledTimes(calls)
    expect(wrapper.text()).toContain('新账号')
    expect(wrapper.findAll('li button').some(button => button.text() === '标记为已读')).toBe(true)

    await wrapper.findAll('li button').find(button => button.text() === '标记为已读')!.trigger('click')
    expect(markNotificationReadMock).toHaveBeenCalledTimes(2)
    currentWrite.resolve({ id: 7, read: true })
    await flushPromises()
  })

  it('does not report a failed list reload as a failed read write', async () => {
    listNotificationsMock.mockResolvedValueOnce(result(7, '待处理通知'))
    const wrapper = mountNotificationsTab()
    await flushPromises()
    listNotificationsMock.mockRejectedValue(new Error('reload unavailable'))
    await wrapper.findAll('li button').find(button => button.text() === '标记为已读')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('加载通知失败')
    expect(wrapper.text()).not.toContain('标记已读失败')
    const vm = wrapper.vm as unknown as { notifList: PageResult<NotificationItem> }
    expect(vm.notifList.items[0].read).toBe(true)
  })

  it('keeps an item unread and allows retry after an unconfirmed acknowledgement', async () => {
    listNotificationsMock.mockResolvedValueOnce(result(7, '待确认通知')).mockResolvedValue({
      ...result(7, '待确认通知'),
      items: [{ ...result(7, '待确认通知').items[0], read: true }],
    })
    markNotificationReadMock.mockRejectedValueOnce(new NotificationReadAcknowledgementError())
    const wrapper = mountNotificationsTab()
    try {
      await flushPromises()
      const readButton = wrapper.findAll('li button').find(button => button.text() === '标记为已读')!
      await readButton.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('已读结果暂未确认，请重试')
      expect(wrapper.findAll('li button').some(button => button.text() === '标记为已读')).toBe(true)
      expect(markNotificationReadMock).toHaveBeenCalledTimes(1)

      markNotificationReadMock.mockResolvedValueOnce({ id: 7, read: true })
      await wrapper.findAll('li button').find(button => button.text() === '标记为已读')!.trigger('click')
      await flushPromises()

      expect(markNotificationReadMock).toHaveBeenCalledTimes(2)
      expect(wrapper.findAll('li button').some(button => button.text() === '标记为已读')).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('shows the ordinary read failure separately while retaining the unread item', async () => {
    listNotificationsMock.mockResolvedValue(result(7, '普通失败通知'))
    markNotificationReadMock.mockRejectedValueOnce(new Error('gateway unavailable'))
    const wrapper = mountNotificationsTab()
    try {
      await flushPromises()
      await wrapper.findAll('li button').find(button => button.text() === '标记为已读')!.trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('标记已读失败，请稍后重试。')
      expect(wrapper.text()).not.toContain('已读结果暂未确认，请重试')
      expect(wrapper.findAll('li button').some(button => button.text() === '标记为已读')).toBe(true)
    } finally { wrapper.unmount() }
  })
})

describe('SettingsConnectedTab', () => {
  const wrappers: VueWrapper[] = []
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'connected-token', user: { id: 1, username: 'alice' } })
    listConnectedAccountsMock.mockResolvedValue({
      items: [
        { provider: 'github', connected: true },
        { provider: 'google', connected: false },
      ],
    })
  })

  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    localStorage.clear()
  })

  it('shows only backend-confirmed status without bind or unbind actions', async () => {
    const wrapper = mount(SettingsConnectedTab)
    wrappers.push(wrapper)
    await flushPromises()

    expect(wrapper.text()).toContain('暂不提供绑定或解绑操作')
    expect(wrapper.text()).toContain('该第三方账号已与当前账号关联。')
    expect(wrapper.text()).toContain('尚未关联到当前账号。')
    expect(wrapper.findAll('button').filter((button) => /绑定|解绑/.test(button.text()))).toHaveLength(0)
  })

  it('shows a retry state when linked-status loading fails', async () => {
    listConnectedAccountsMock.mockRejectedValueOnce(new Error('connected unavailable'))
    const wrapper = mount(SettingsConnectedTab)
    wrappers.push(wrapper)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法确认账号状态')
    expect(wrapper.get('button').text()).toBe('重试')
  })
})

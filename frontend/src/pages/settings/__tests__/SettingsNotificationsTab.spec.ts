import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsConnectedTab from '../SettingsConnectedTab.vue'
import SettingsNotificationsTab from '../SettingsNotificationsTab.vue'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings, updateMyNotificationSettings } from '@/api/settings'
import { listConnectedAccounts } from '@/api/connected'

vi.mock('@/api/notifications', () => ({
  listNotifications: vi.fn(),
  markNotificationRead: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getMyNotificationSettings: vi.fn(),
  updateMyNotificationSettings: vi.fn(),
}))

vi.mock('@/api/connected', () => ({
  listConnectedAccounts: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

const listNotificationsMock = vi.mocked(listNotifications)
const markNotificationReadMock = vi.mocked(markNotificationRead)
const getMyNotificationSettingsMock = vi.mocked(getMyNotificationSettings)
const updateMyNotificationSettingsMock = vi.mocked(updateMyNotificationSettings)
const listConnectedAccountsMock = vi.mocked(listConnectedAccounts)

const defaultSettings = {
  inApp: { reply: true, mention: true, like: true, follow: true, system: true as const },
  email: { enabled: false as const, frequency: 'instant' },
}

describe('SettingsNotificationsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listNotificationsMock.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    markNotificationReadMock.mockResolvedValue({ success: true })
    getMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
    updateMyNotificationSettingsMock.mockResolvedValue(defaultSettings)
  })

  it('exposes only supported preferences and sends fixed system/email contract values', async () => {
    const wrapper = mount(SettingsNotificationsTab)
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

  it('blocks saving and offers retry when preference loading fails', async () => {
    getMyNotificationSettingsMock.mockRejectedValueOnce(new Error('settings unavailable'))
    const wrapper = mount(SettingsNotificationsTab)
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
    const wrapper = mount(SettingsNotificationsTab)
    await flushPromises()

    expect(wrapper.text()).toContain('回复通知')
    expect(wrapper.text()).toContain('未读')
    expect(wrapper.find('time').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="查看有人回复了你"]').exists()).toBe(true)
    expect(wrapper.find('li').attributes('role')).toBeUndefined()

    await wrapper.find('li').trigger('click')
    expect(markNotificationReadMock).not.toHaveBeenCalled()
  })
})

describe('SettingsConnectedTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listConnectedAccountsMock.mockResolvedValue({
      items: [
        { provider: 'github', connected: true },
        { provider: 'google', connected: false },
      ],
    })
  })

  it('shows only backend-confirmed status without bind or unbind actions', async () => {
    const wrapper = mount(SettingsConnectedTab)
    await flushPromises()

    expect(wrapper.text()).toContain('当前为本地模式')
    expect(wrapper.text()).toContain('已关联（后端已确认）')
    expect(wrapper.text()).toContain('未关联（本地模式不可用）')
    expect(wrapper.findAll('button').filter((button) => /绑定|解绑/.test(button.text()))).toHaveLength(0)
  })

  it('shows a retry state when linked-status loading fails', async () => {
    listConnectedAccountsMock.mockRejectedValueOnce(new Error('connected unavailable'))
    const wrapper = mount(SettingsConnectedTab)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法确认账号状态')
    expect(wrapper.get('button').text()).toBe('重试')
  })
})

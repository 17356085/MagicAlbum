import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import SettingsNotificationsTab from '../SettingsNotificationsTab.vue'
import type { NotificationSettings } from '@/types'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

const originalAdapter = api.defaults.adapter
const defaults: NotificationSettings = {
  inApp: { reply: true, mention: true, like: true, follow: true, system: true },
  email: { enabled: false, frequency: 'instant' },
}
const preferencesSelector = 'section[aria-labelledby="notification-preferences-title"]'
let wrapper: VueWrapper | undefined
let readResponse: unknown
let saveResponse: unknown
const writes: NotificationSettings[] = []

async function createWrapper(): Promise<VueWrapper> {
  wrapper = mount(SettingsNotificationsTab)
  await flushPromises()
  return wrapper
}

describe('notification preferences with the real API boundary', () => {
  beforeEach(() => {
    localStorage.setItem('accessToken', 'preference-session')
    readResponse = defaults
    saveResponse = defaults
    writes.length = 0
    api.defaults.adapter = async (config) => {
      let data: unknown = { items: [], page: 1, size: 20, total: 0 }
      if (config.url === '/users/me/settings') {
        if (config.method === 'patch') {
          writes.push(JSON.parse(String(config.data)) as NotificationSettings)
          data = saveResponse
        } else data = readResponse
      }
      return { data, status: 200, statusText: 'OK', headers: {}, config }
    }
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    api.defaults.adapter = originalAdapter
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('keeps editing disabled after an invalid successful read and recovers through reload', async () => {
    readResponse = '<!doctype html><html>fallback</html>'
    const view = await createWrapper()
    const preferences = view.get(preferencesSelector)
    expect(preferences.text()).toContain('通知偏好加载失败')
    expect(preferences.find('#notification-preference-reply').exists()).toBe(false)
    expect(preferences.get('button.ui-button-primary').attributes('disabled')).toBeDefined()
    expect(writes).toHaveLength(0)

    readResponse = { ...defaults, inApp: { ...defaults.inApp, reply: false } }
    await preferences.get('button.ui-button').trigger('click')
    await flushPromises()
    expect(view.get('#notification-preference-reply').element).toHaveProperty('checked', false)
    expect(preferences.get('button.ui-button-primary').attributes('disabled')).toBeUndefined()
  })

  it('does not report an unconfirmed write as saved or replace the current draft with defaults', async () => {
    const view = await createWrapper()
    await view.get('#notification-preference-reply').setValue(false)
    saveResponse = null
    await view.get(`${preferencesSelector} button.ui-button-primary`).trigger('click')
    await flushPromises()

    expect(view.text()).not.toContain('保存成功')
    expect(view.text()).toContain('保存结果暂未确认，请重试。')
    expect(view.get('#notification-preference-reply').element).toHaveProperty('checked', false)
    expect(view.text()).toContain('有未保存的修改')
    expect(view.emitted('dirty-change')?.slice(-1)[0]).toEqual([true])

    saveResponse = { ...defaults, inApp: { ...defaults.inApp, reply: false } }
    await view.get(`${preferencesSelector} button.ui-button-primary`).trigger('click')
    await flushPromises()
    expect(writes).toHaveLength(2)
    expect(writes[1]).toEqual(writes[0])
    expect(view.text()).toContain('保存成功')
    expect(view.text()).not.toContain('有未保存的修改')
    expect(view.emitted('dirty-change')?.slice(-1)[0]).toEqual([false])
  })
})

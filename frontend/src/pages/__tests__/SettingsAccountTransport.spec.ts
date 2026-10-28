import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import SettingsAccount from '../SettingsAccount.vue'

const originalAdapter = api.defaults.adapter
const account = { id: 7, username: 'blue_user', phone: '13800138000', email: 'blue@example.test' }
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'account-token', user: { id: 7, username: account.username } })
  vi.stubGlobal('alert', vi.fn())
  window.__authExpiredAlertShown = false
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  api.defaults.adapter = originalAdapter
  localStorage.clear()
  window.__authExpiredAlertShown = false
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function submitPassword(): Promise<void> {
  await wrapper!.get('#settings-account-current-password').setValue('WrongCurrent1')
  await wrapper!.get('#settings-account-new-password').setValue('NewPassword1')
  await wrapper!.get('#settings-account-confirm-password').setValue('NewPassword1')
  await wrapper!.findAll('button').find(button => button.text() === '修改密码')!.trigger('click')
  await flushPromises()
}

describe('account form with the real API client and auth store', () => {
  it('sends a PATCH payload without id and accepts the canonical UserDto response', async () => {
    let patchPayload: unknown
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-token')
      if (config.method === 'get' && config.url === '/users/me/basic') {
        return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      }
      expect(config.method).toBe('patch')
      expect(config.url).toBe('/users/me/basic')
      patchPayload = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
      return {
        config,
        data: { id: 7, username: 'saved_user', phone: account.phone, email: account.email },
        headers: {},
        status: 200,
        statusText: 'OK',
      }
    }

    wrapper = mount(SettingsAccount)
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('saved_user')
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    expect(patchPayload).toEqual({ username: 'saved_user', phone: account.phone, email: account.email })
    expect(patchPayload).not.toHaveProperty('id')
    expect(wrapper.text()).toContain('保存成功')
  })

  it('keeps a malformed GET unloaded and retries only after an explicit user action', async () => {
    let getCalls = 0
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-token')
      expect(config.method).toBe('get')
      expect(config.url).toBe('/users/me/basic')
      getCalls += 1
      return {
        config,
        data: getCalls === 1 ? { ...account, email: '' } : account,
        headers: {},
        status: 200,
        statusText: 'OK',
      }
    }

    wrapper = mount(SettingsAccount)
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('账户信息响应无效')
    expect(wrapper.findAll('button').filter(button => button.text() === '保存修改')[0].attributes('disabled')).toBeDefined()

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()

    expect(getCalls).toBe(2)
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe(account.username)
  })

  it('clears a GET response that belongs to another known owner', async () => {
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-token')
      expect(config.method).toBe('get')
      expect(config.url).toBe('/users/me/basic')
      return {
        config,
        data: { id: 8, username: 'green_user', phone: '13900139000', email: 'green@example.test' },
        headers: {},
        status: 200,
        statusText: 'OK',
      }
    }

    wrapper = mount(SettingsAccount)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账户归属不一致')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').filter(button => button.text() === '保存修改')[0].attributes('disabled')).toBeDefined()
  })

  it('does not retry a PATCH after an unconfirmable response', async () => {
    let patchCalls = 0
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-token')
      if (config.method === 'get' && config.url === '/users/me/basic') {
        return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      }
      expect(config.method).toBe('patch')
      patchCalls += 1
      return {
        config,
        data: { id: 7, username: 'server_may_have_written', phone: account.phone },
        headers: {},
        status: 200,
        statusText: 'OK',
      }
    }

    wrapper = mount(SettingsAccount)
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('server_may_have_written')
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    expect(patchCalls).toBe(1)
    expect(wrapper.text()).toContain('保存结果无法确认，请重新加载账户信息核实')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('server_may_have_written')
    expect(useAuthStore().user).toEqual({ id: 7, username: account.username })
  })

  it('clears drafts for a parseable PATCH response from another owner', async () => {
    let patchCalls = 0
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-token')
      if (config.method === 'get' && config.url === '/users/me/basic') {
        return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      }
      expect(config.method).toBe('patch')
      patchCalls += 1
      return {
        config,
        data: { id: 8, username: 'green_user', phone: '13900139000', email: 'green@example.test' },
        headers: {},
        status: 200,
        statusText: 'OK',
      }
    }

    wrapper = mount(SettingsAccount)
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('draft_user')
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    expect(patchCalls).toBe(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('账户归属不一致')
    expect(wrapper.text()).not.toContain('保存结果无法确认，请重新加载账户信息核实')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').filter(button => button.text() === '保存修改')[0].attributes('disabled')).toBeDefined()
  })

  it('keeps the session on a password challenge error and allows a successful retry', async () => {
    let passwordCalls = 0
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-token')
      if (config.method === 'get' && config.url === '/users/me/basic') {
        return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      }
      expect(config.method).toBe('post')
      expect(config.url).toBe('/users/me/password')
      passwordCalls += 1
      if (passwordCalls === 1) {
        throw { config, response: { status: 400, data: { message: '当前密码不正确' } } }
      }
      return { config, data: '', headers: {}, status: 204, statusText: 'No Content' }
    }
    wrapper = mount(SettingsAccount)
    await flushPromises()
    await submitPassword()

    expect(wrapper.get('#settings-account-current-password-error').text()).toBe('当前密码不正确')
    expect(useAuthStore().isLoggedIn).toBe(true)
    expect(localStorage.getItem('accessToken')).toBe('account-token')
    expect(window.alert).not.toHaveBeenCalled()

    await wrapper.get('#settings-account-current-password').setValue('CorrectCurrent1')
    await wrapper.findAll('button').find(button => button.text() === '修改密码')!.trigger('click')
    await flushPromises()

    expect(passwordCalls).toBe(2)
    expect(wrapper.text()).toContain('密码已修改')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
    expect(useAuthStore().token).toBe('account-token')
    expect(window.alert).not.toHaveBeenCalled()
  })

  it('still clears a genuinely unauthorized bearer on the password endpoint', async () => {
    api.defaults.adapter = async config => {
      if (config.method === 'get') {
        return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      }
      expect(config.url).toBe('/users/me/password')
      throw { config, response: { status: 401, data: { message: '登录已失效' } } }
    }
    wrapper = mount(SettingsAccount)
    await flushPromises()
    await submitPassword()

    expect(useAuthStore().isLoggedIn).toBe(false)
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(window.alert).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('密码已修改')
  })
})

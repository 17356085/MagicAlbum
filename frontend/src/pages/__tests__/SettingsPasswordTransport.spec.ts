import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import SettingsAccount from '../SettingsAccount.vue'

const originalAdapter = api.defaults.adapter
const account = { id: 1, username: 'alice', phone: '13800138000', email: 'alice@example.test' }

describe('password update transport outcomes', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    localStorage.clear()
    sessionStorage.clear()
  })

  it.each(['timeout', 'server-error', 'unexpected-success'] as const)(
    'does not claim success or failure when the write outcome is unconfirmed: %s',
    async outcome => {
      let writes = 0
      api.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
        if (config.method === 'get') return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
        writes += 1
        if (outcome === 'timeout') throw new AxiosError('timeout', 'ECONNABORTED', config)
        if (outcome === 'server-error') {
          throw new AxiosError('server error', 'ERR_BAD_RESPONSE', config, undefined,
            { config, data: {}, headers: {}, status: 500, statusText: 'Server Error' })
        }
        return { config, data: '<html>gateway</html>', headers: {}, status: 200, statusText: 'OK' }
      }
      const wrapper = mount(SettingsAccount)
      try {
        await flushPromises()
        await wrapper.get('#settings-account-current-password').setValue('CurrentPassword1')
        await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
        await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
        await wrapper.findAll('button').find(button => button.text().includes('修改密码'))!.trigger('click')
        await flushPromises()
        expect(writes).toBe(1)
        expect(wrapper.text()).toContain('密码修改结果未确认')
        expect(wrapper.text()).not.toContain('密码已修改')
        expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('CurrentPassword1')
      } finally { wrapper.unmount() }
    },
  )

  it('keeps a confirmed incorrect-password response distinct', async () => {
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      if (config.method === 'get') return { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      throw new AxiosError('bad request', 'ERR_BAD_REQUEST', config, undefined,
        { config, data: { message: '当前密码不正确' }, headers: {}, status: 400, statusText: 'Bad Request' })
    }
    const wrapper = mount(SettingsAccount)
    try {
      await flushPromises()
      await wrapper.get('#settings-account-current-password').setValue('WrongPassword1')
      await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
      await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
      await wrapper.findAll('button').find(button => button.text().includes('修改密码'))!.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('当前密码不正确')
      expect(wrapper.text()).not.toContain('密码修改结果未确认')
    } finally { wrapper.unmount() }
  })

  it('confirms the documented 204 response', async () => {
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => config.method === 'get'
      ? { config, data: account, headers: {}, status: 200, statusText: 'OK' }
      : { config, data: '', headers: {}, status: 204, statusText: 'No Content' }
    const wrapper = mount(SettingsAccount)
    try {
      await flushPromises()
      await wrapper.get('#settings-account-current-password').setValue('CurrentPassword1')
      await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
      await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
      await wrapper.findAll('button').find(button => button.text().includes('修改密码'))!.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('密码已修改')
    } finally { wrapper.unmount() }
  })
})

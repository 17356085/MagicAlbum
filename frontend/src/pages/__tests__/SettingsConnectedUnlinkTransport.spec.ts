import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import SettingsConnectedTab from '../settings/SettingsConnectedTab.vue'

const originalAdapter = api.defaults.adapter
let wrapper: VueWrapper | undefined
let interceptor: number | undefined

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'account-a', user: { id: 7, username: 'alice' } })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  if (interceptor !== undefined) api.interceptors.request.eject(interceptor)
  interceptor = undefined
  api.defaults.adapter = originalAdapter
  localStorage.clear()
  sessionStorage.clear()
})

describe('verified unlink through the real API client', () => {
  it('sends the captured bearer and proof, then reads back the binding', async () => {
    let linked = true
    let sentBody: unknown
    api.defaults.adapter = async config => {
      expect(config.headers.get('Authorization')).toBe('Bearer account-a')
      if (config.method === 'get') {
        return { config, data: { items: [{ provider: 'github', connected: linked, bindingTag: linked ? 'a'.repeat(64) : null }] }, headers: {}, status: 200, statusText: 'OK' }
      }
      expect(config.url).toBe('/users/me/connected-accounts/github/disconnect')
      sentBody = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
      linked = false
      return { config, data: '', headers: {}, status: 204, statusText: 'No Content' }
    }

    wrapper = mount(SettingsConnectedTab)
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()

    expect(sentBody).toEqual({ identifier: 'alice@example.test', currentPassword: 'CurrentPassword1', bindingTag: 'a'.repeat(64) })
    expect(wrapper.get('[role="status"]').text()).toContain('解绑完成')
    expect(wrapper.find('[aria-label="解绑 GitHub"]').exists()).toBe(false)
  })

  it('never attaches a replacement account token to the first account password', async () => {
    let postedToken: unknown
    api.defaults.adapter = async config => {
      if (config.method === 'get') {
        return { config, data: { items: [{ provider: 'github', connected: true, bindingTag: 'a'.repeat(64) }] }, headers: {}, status: 200, statusText: 'OK' }
      }
      postedToken = config.headers.get('Authorization')
      return { config, data: '', headers: {}, status: 204, statusText: 'No Content' }
    }
    wrapper = mount(SettingsConnectedTab)
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    interceptor = api.interceptors.request.use(config => {
      if (String(config.url).endsWith('/disconnect')) localStorage.setItem('accessToken', 'account-b')
      return config
    }, undefined, { synchronous: true })

    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()

    expect(postedToken).toBe('Bearer account-a')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
    expect(wrapper.text()).not.toContain('解绑完成')
  })
})

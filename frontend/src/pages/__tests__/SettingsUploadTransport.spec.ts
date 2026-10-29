import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import SettingsProfileTab from '../settings/SettingsProfileTab.vue'

const mocks = vi.hoisted(() => ({ getMyProfile: vi.fn(), updateMyProfile: vi.fn() }))
vi.mock('@/api/settings', () => mocks)

const originalAdapter = api.defaults.adapter
const profile = {
  id: 1, userId: 1, username: 'alice', nickname: '原昵称', bio: '原简介',
  homepageUrl: '', location: '', avatarUrl: '/uploads/original.png',
}

describe('profile avatar upload response', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    mocks.getMyProfile.mockReset().mockResolvedValue({ ...profile })
    mocks.updateMyProfile.mockReset().mockResolvedValue({ ...profile })
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    localStorage.clear()
    sessionStorage.clear()
  })

  it('preserves the profile and does not PATCH when a 2xx response contains a non-string URL', async () => {
    let posts = 0
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      posts += 1
      return { config, data: { url: { invalid: true } }, headers: {}, status: 200, statusText: 'OK' }
    }
    const wrapper = mount(SettingsProfileTab, { global: { stubs: { MarkdownTextareaEditor: true } } })
    try {
      await flushPromises()
      const input = wrapper.get('#settings-profile-avatar')
      Object.defineProperty(input.element, 'files', {
        configurable: true,
        value: [new File(['image'], 'avatar.png', { type: 'image/png' })],
      })
      await input.trigger('change')
      await flushPromises()
      expect(posts).toBe(1)
      expect(mocks.updateMyProfile).not.toHaveBeenCalled()
      expect(wrapper.get('#settings-profile-avatar-error').text()).toContain('图片上传结果未确认')
      expect(wrapper.get('#settings-profile-avatar-error').text()).toContain('重复上传可能产生多份图片')
      expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('原昵称')
    } finally {
      wrapper.unmount()
    }
  })

  it('still saves an avatar returned in the backend URL-only format', async () => {
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => ({
      config, data: { url: '/uploads/new-avatar.png' }, headers: {}, status: 200, statusText: 'OK',
    })
    const wrapper = mount(SettingsProfileTab, { global: { stubs: { MarkdownTextareaEditor: true } } })
    try {
      await flushPromises()
      const input = wrapper.get('#settings-profile-avatar')
      Object.defineProperty(input.element, 'files', {
        configurable: true,
        value: [new File(['image'], 'avatar.png', { type: 'image/png' })],
      })
      await input.trigger('change')
      await flushPromises()
      expect(mocks.updateMyProfile).toHaveBeenCalledOnce()
      expect(mocks.updateMyProfile).toHaveBeenCalledWith(expect.objectContaining({ avatarUrl: '/uploads/new-avatar.png' }))
    } finally {
      wrapper.unmount()
    }
  })

  it('reports a timed-out avatar upload as unconfirmed without retrying or saving a new avatar', async () => {
    let posts = 0
    api.defaults.adapter = async () => {
      posts += 1
      throw new AxiosError('timeout', 'ECONNABORTED')
    }
    const wrapper = mount(SettingsProfileTab, { global: { stubs: { MarkdownTextareaEditor: true } } })
    try {
      await flushPromises()
      const input = wrapper.get('#settings-profile-avatar')
      Object.defineProperty(input.element, 'files', {
        configurable: true,
        value: [new File(['image'], 'avatar.png', { type: 'image/png' })],
      })
      await input.trigger('change')
      await flushPromises()
      expect(posts).toBe(1)
      expect(mocks.updateMyProfile).not.toHaveBeenCalled()
      expect(wrapper.get('#settings-profile-avatar-error').text()).toContain('图片上传结果未确认')
      expect(wrapper.get('#settings-profile-avatar-error').text()).toContain('重复上传可能产生多份图片')
      expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('原昵称')
    } finally {
      wrapper.unmount()
    }
  })
})

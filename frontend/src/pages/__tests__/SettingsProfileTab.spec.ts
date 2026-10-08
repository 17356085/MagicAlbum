import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsProfileTab from '../settings/SettingsProfileTab.vue'
import { getMyProfile, updateMyProfile } from '@/api/settings'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  getMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
  uploadImage: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getMyProfile: mocks.getMyProfile,
  updateMyProfile: mocks.updateMyProfile,
}))
vi.mock('@/api/uploads', () => ({ uploadImage: mocks.uploadImage }))

const getMyProfileMock = vi.mocked(getMyProfile)
const updateMyProfileMock = vi.mocked(updateMyProfile)
const uploadImageMock = vi.mocked(uploadImage)

const profile = {
  nickname: '旧昵称',
  bio: '旧简介',
  homepageUrl: 'https://example.com',
  location: '香港',
  avatarUrl: '',
}

function createWrapper() {
  return mount(SettingsProfileTab, {
    global: {
      stubs: {
        MarkdownTextareaEditor: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<textarea data-test="bio-editor" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
}

describe('SettingsProfileTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    localStorage.clear()
    getMyProfileMock.mockResolvedValue(profile)
    updateMyProfileMock.mockResolvedValue(profile)
    uploadImageMock.mockResolvedValue({ url: 'https://cdn.example/avatar.png' })
  })

  it('shows a retryable load error and blocks profile saves until loading succeeds', async () => {
    getMyProfileMock.mockRejectedValueOnce(new Error('资料服务不可用'))
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('资料服务不可用')
    expect(wrapper.get('button').text()).toContain('重试')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!
    expect(saveButton.attributes('disabled')).toBeDefined()
    expect(updateMyProfileMock).not.toHaveBeenCalled()

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('资料服务不可用')
    expect(saveButton.attributes('disabled')).toBeUndefined()
  })

  it('emits dirty changes, saves the edited profile, and reports upload errors inline', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-profile-nickname').setValue('新昵称')
    expect(wrapper.emitted('dirty-change')).toContainEqual([true])

    await wrapper.get('button:last-of-type').trigger('click')
    await flushPromises()
    expect(updateMyProfileMock).toHaveBeenCalledWith({
      nickname: '新昵称',
      bio: '旧简介',
      homepageUrl: 'https://example.com',
      location: '香港',
      avatarUrl: '',
    })
    expect(wrapper.emitted('dirty-change')).toContainEqual([false])

    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    uploadImageMock.mockRejectedValueOnce(new Error('上传服务不可用'))
    const fileInput = wrapper.get('#settings-profile-avatar')
    Object.defineProperty(fileInput.element, 'files', {
      configurable: true,
      value: [new File(['avatar'], 'avatar.png', { type: 'image/png' })],
    })
    await fileInput.trigger('change')
    await flushPromises()

    expect(wrapper.get('#settings-profile-avatar-error').text()).toContain('上传服务不可用')
    expect(wrapper.find('[role="alert"]').text()).toContain('上传服务不可用')
  })
})

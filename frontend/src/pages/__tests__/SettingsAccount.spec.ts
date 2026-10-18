import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsAccount from '../SettingsAccount.vue'
import { getMyBasicInfo, updateMyBasicInfo, updateMyPassword } from '@/api/settings'

const mocks = vi.hoisted(() => ({
  getMyBasicInfo: vi.fn(),
  updateMyBasicInfo: vi.fn(),
  updateMyPassword: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getMyBasicInfo: mocks.getMyBasicInfo,
  updateMyBasicInfo: mocks.updateMyBasicInfo,
  updateMyPassword: mocks.updateMyPassword,
}))

const getMyBasicInfoMock = vi.mocked(getMyBasicInfo)
const updateMyBasicInfoMock = vi.mocked(updateMyBasicInfo)
const updateMyPasswordMock = vi.mocked(updateMyPassword)

const account = { username: 'blue_user', phone: '13800138000', email: 'blue@example.com' }

function createWrapper() {
  return mount(SettingsAccount)
}

describe('SettingsAccount', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getMyBasicInfoMock.mockResolvedValue(account)
    updateMyBasicInfoMock.mockResolvedValue(account)
    updateMyPasswordMock.mockResolvedValue({})
  })

  it('shows a retryable load error and disables risky saves after a load failure', async () => {
    getMyBasicInfoMock.mockRejectedValueOnce(new Error('账户服务不可用'))
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账户服务不可用')
    expect(wrapper.get('[role="alert"] button').text()).toContain('重试')
    expect(wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').filter((button) => button.text().includes('修改密码'))[0].attributes('disabled')).toBeDefined()

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('账户服务不可用')
    expect((wrapper.find('#settings-account-username').element as HTMLInputElement).value).toBe('blue_user')
  })

  it('associates account labels, emits dirty state, and exposes live validation feedback', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    for (const id of [
      'settings-account-username',
      'settings-account-phone',
      'settings-account-email',
      'settings-account-current-password',
      'settings-account-new-password',
      'settings-account-confirm-password',
    ]) {
      expect(wrapper.find(`label[for="${id}"]`).exists()).toBe(true)
    }

    await wrapper.get('#settings-account-username').setValue('x')
    expect(wrapper.emitted('dirty-change')).toContainEqual([true])
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    expect(wrapper.get('#settings-account-username-error').attributes('aria-live')).toBe('assertive')
    expect(updateMyBasicInfoMock).not.toHaveBeenCalled()

    await wrapper.get('#settings-account-username').setValue('new_user')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()
    expect(updateMyBasicInfoMock).toHaveBeenCalledWith({ username: 'new_user', phone: '13800138000', email: 'blue@example.com' })
    expect(wrapper.emitted('dirty-change')).toContainEqual([false])
  })

  it('blocks empty phone and email values before the backend-required update', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-account-phone').setValue('')
    await wrapper.get('#settings-account-email').setValue('')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')

    expect(wrapper.get('#settings-account-phone-error').text()).toContain('请输入手机号')
    expect(wrapper.get('#settings-account-email-error').text()).toContain('请输入邮箱')
    expect(updateMyBasicInfoMock).not.toHaveBeenCalled()
  })
})

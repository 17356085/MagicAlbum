import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsAccount from '../SettingsAccount.vue'
import { getMyBasicInfo, updateMyBasicInfo, updateMyPassword } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'
import type { BasicInfoPayload, BasicInfoResponse } from '@/types'

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

const account: BasicInfoResponse = { id: 1, username: 'blue_user', phone: '13800138000', email: 'blue@example.com' }
const accountB: BasicInfoResponse = { id: 2, username: 'green_user', phone: '13900139000', email: 'green@example.com' }

function accountPayload(value: Pick<BasicInfoResponse, 'username' | 'phone' | 'email'>): BasicInfoPayload {
  return { username: value.username, phone: value.phone, email: value.email }
}

function serverAccount(overrides: Partial<BasicInfoResponse> = {}): BasicInfoResponse {
  return { ...account, ...overrides }
}

const malformedAccountResponses: Array<[string, unknown]> = [
  ['non-object response', []],
  ['missing id', (() => { const { id: _id, ...rest } = account; return rest })()],
  ['missing username', (() => { const { username: _username, ...rest } = account; return rest })()],
  ['missing phone', (() => { const { phone: _phone, ...rest } = account; return rest })()],
  ['missing email', (() => { const { email: _email, ...rest } = account; return rest })()],
  ['empty username', { ...account, username: '   ' }],
  ['non-string phone', { ...account, phone: 13800138000 }],
  ['empty email', { ...account, email: '' }],
  ['zero id', { ...account, id: 0 }],
  ['fractional id', { ...account, id: 1.5 }],
  ['unsafe numeric id', { ...account, id: Number.MAX_SAFE_INTEGER + 1 }],
  ['non-canonical string id', { ...account, id: '01' }],
  ['out-of-range decimal string id', { ...account, id: '9223372036854775808' }],
]

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

function serverError(message: string): Error & { response: { data: { message: string } } } {
  return Object.assign(new Error('request failed'), { response: { data: { message } } })
}

const wrappers: VueWrapper[] = []

function createWrapper(options: { token?: string; user?: { id: number; username: string } | null } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  auth.applyAuthState({
    accessToken: options.token ?? 'token-a',
    user: options.user === undefined ? { id: 1, username: 'blue_user' } : options.user,
  })
  const wrapper = mount(SettingsAccount, { global: { plugins: [pinia] } })
  wrappers.push(wrapper)
  return { wrapper, auth }
}

describe('SettingsAccount', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    getMyBasicInfoMock.mockResolvedValue(account)
    updateMyBasicInfoMock.mockResolvedValue({ ...account })
    updateMyPasswordMock.mockResolvedValue({})
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.useRealTimers()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('shows a retryable load error and disables risky saves after a load failure', async () => {
    getMyBasicInfoMock.mockRejectedValueOnce(new Error('账户服务不可用'))
    const { wrapper } = createWrapper()
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

  it.each(malformedAccountResponses)('keeps the account unloaded for a malformed GET response (%s)', async (_label, response) => {
    getMyBasicInfoMock.mockResolvedValueOnce(response as BasicInfoResponse)
    const { wrapper } = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账户信息响应无效')
    expect(wrapper.get('[role="alert"] button').text()).toContain('重试')
    expect(wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').filter((button) => button.text().includes('修改密码'))[0].attributes('disabled')).toBeDefined()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
  })

  it('clears state for a GET response belonging to another known owner', async () => {
    getMyBasicInfoMock.mockResolvedValueOnce(serverAccount({ id: 2, username: 'wrong_owner' }))
    const { wrapper } = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账户归属不一致')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').filter((button) => button.text().includes('修改密码'))[0].attributes('disabled')).toBeDefined()
  })

  it('accepts a canonical Java-long string id without hydrating a token-only owner', async () => {
    getMyBasicInfoMock.mockResolvedValueOnce(serverAccount({ id: '9223372036854775807' }))
    const { wrapper, auth } = createWrapper({ user: null })
    await flushPromises()

    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('blue_user')
    expect(auth.user).toBeNull()
  })

  it('warns before changing a contact and clears the warning when reverted or saved', async () => {
    const { wrapper } = createWrapper()
    await flushPromises()
    const warning = '新的邮箱或手机号暂不能用于验证码登录'
    expect(wrapper.text()).not.toContain(warning)
    await wrapper.get('#settings-account-email').setValue('new@example.com')
    expect(wrapper.text()).toContain(warning)
    await wrapper.get('#settings-account-email').setValue(account.email)
    expect(wrapper.text()).not.toContain(warning)
    await wrapper.get('#settings-account-phone').setValue('13900139000')
    expect(wrapper.text()).toContain(warning)
    updateMyBasicInfoMock.mockResolvedValueOnce({ ...account, phone: '13900139000' })
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain(warning)
  })

  it('does not claim or synthesize a successful save when PATCH returns a malformed UserDto', async () => {
    const response = serverAccount({ email: '' })
    updateMyBasicInfoMock.mockResolvedValueOnce(response)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const { wrapper, auth } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('server_may_have_written')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()

    expect(updateMyBasicInfoMock).toHaveBeenCalledTimes(1)
    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(1)
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('server_may_have_written')
    expect(wrapper.text()).toContain('保存结果无法确认，请重新加载账户信息核实')
    expect(auth.user).toEqual({ id: 1, username: 'blue_user' })
    expect(eventSpy).not.toHaveBeenCalled()
    const dirtyEventsAfterInvalidSave = wrapper.emitted('dirty-change') || []
    expect(dirtyEventsAfterInvalidSave[dirtyEventsAfterInvalidSave.length - 1]).toEqual([true])

    await wrapper.get('#settings-account-username').setValue('blue_user')
    const dirtyEventsAfterRevert = wrapper.emitted('dirty-change') || []
    expect(dirtyEventsAfterRevert[dirtyEventsAfterRevert.length - 1]).toEqual([false])
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('clears account and password drafts when PATCH returns another owner', async () => {
    updateMyBasicInfoMock.mockResolvedValueOnce(serverAccount({ id: 2, username: 'wrong_owner', phone: '13900139000', email: 'green@example.com' }))
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('draft_user')
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账户归属不一致')
    expect(wrapper.text()).not.toContain('保存结果无法确认，请重新加载账户信息核实')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].attributes('disabled')).toBeDefined()
  })

  it('prioritizes a valid mismatched owner over missing PATCH fields', async () => {
    updateMyBasicInfoMock.mockResolvedValueOnce({ id: 2, username: 'wrong_owner' } as unknown as BasicInfoResponse)
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('draft_user')
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('账户归属不一致')
    expect(wrapper.text()).not.toContain('保存结果无法确认，请重新加载账户信息核实')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
  })

  it('checks the hydrated owner before accepting a token-only PATCH response', async () => {
    const response = deferred<BasicInfoResponse>()
    updateMyBasicInfoMock.mockReturnValueOnce(response.promise)
    getMyBasicInfoMock.mockReset()
    getMyBasicInfoMock.mockResolvedValueOnce(account).mockResolvedValueOnce(accountB)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const { wrapper, auth } = createWrapper({ user: null })
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('token_only_draft')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')

    expect(auth.hydrateCurrentUser({ id: 2, username: 'green_user' }, 'token-a')).toBe(true)
    response.resolve(serverAccount({ id: 1, username: 'server_user' }))
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 0))
    await flushPromises()

    expect(wrapper.text()).not.toContain('保存结果无法确认，请重新加载账户信息核实')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('green_user')
    expect(auth.user).toEqual(expect.objectContaining({ id: 2, username: 'green_user' }))
    expect(eventSpy).not.toHaveBeenCalled()
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('associates account labels, emits dirty state, and exposes live validation feedback', async () => {
    const { wrapper } = createWrapper()
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
    updateMyBasicInfoMock.mockResolvedValueOnce(serverAccount({ username: 'new_user' }))
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()
    expect(updateMyBasicInfoMock).toHaveBeenCalledWith({ username: 'new_user', phone: '13800138000', email: 'blue@example.com' })
    expect(wrapper.emitted('dirty-change')).toContainEqual([false])
  })

  it('blocks empty phone and email values before the backend-required update', async () => {
    const { wrapper } = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-account-phone').setValue('')
    await wrapper.get('#settings-account-email').setValue('')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')

    expect(wrapper.get('#settings-account-phone-error').text()).toContain('请输入手机号')
    expect(wrapper.get('#settings-account-email-error').text()).toContain('请输入邮箱')
    expect(updateMyBasicInfoMock).not.toHaveBeenCalled()
  })

  it.each([
    ['username', '用户名格式不正确', 'settings-account-username-error'],
    ['phone', '手机号格式不正确', 'settings-account-phone-error'],
    ['email', '邮箱格式不正确', 'settings-account-email-error'],
  ] as const)('keeps the parsed non-duplicate server message for the %s field', async (field, message, errorId) => {
    const { wrapper } = createWrapper()
    await flushPromises()
    updateMyBasicInfoMock.mockRejectedValueOnce(serverError(message))

    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    const fieldError = wrapper.get(`#${errorId}`)
    expect(fieldError.text()).toBe(message)
    expect(fieldError.attributes('role')).toBe('alert')
    expect(wrapper.get(`#settings-account-${field}`).attributes('aria-describedby')).toBe(errorId)
    expect(wrapper.get('[role="status"]').text()).toBe(message)
  })

  it.each([
    ['username', '用户名已被使用', 'settings-account-username-error'],
    ['phone', '手机号已被使用', 'settings-account-phone-error'],
    ['email', '邮箱已被使用', 'settings-account-email-error'],
  ] as const)('keeps the exact duplicate server message for the %s field', async (field, message, errorId) => {
    const { wrapper } = createWrapper()
    await flushPromises()
    updateMyBasicInfoMock.mockRejectedValueOnce(serverError(message))

    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    expect(wrapper.get(`#${errorId}`).text()).toBe(message)
    expect(wrapper.get(`#settings-account-${field}`).attributes('aria-describedby')).toBe(errorId)
    expect(wrapper.get('[role="status"]').text()).toBe(message)
  })

  it.each([21, 64])('allows an existing %i-character username when saving another account field', async length => {
    const username = 'u'.repeat(length)
    getMyBasicInfoMock.mockResolvedValueOnce({ ...account, username })
    updateMyBasicInfoMock.mockResolvedValueOnce({ ...account, username, phone: '13900000000' })
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-phone').setValue('13900000000')
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    expect(updateMyBasicInfoMock).toHaveBeenCalledWith(accountPayload({ ...account, username, phone: '13900000000' }))
    expect(wrapper.text()).toContain('保存成功')
  })

  it('rejects a username longer than the account API limit before saving', async () => {
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('u'.repeat(65))
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    expect(updateMyBasicInfoMock).not.toHaveBeenCalled()
    expect(wrapper.get('#settings-account-username-error').text()).toContain('3-64')
  })

  it('allows a 255-character email when saving account profile', async () => {
    const email = `${'a'.repeat(63)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(63)}`
    expect(email).toHaveLength(255)
    updateMyBasicInfoMock.mockResolvedValueOnce({ ...account, email })
    const { wrapper } = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-account-email').setValue(email)
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('#settings-account-email').attributes('maxlength')).toBeUndefined()
    expect((wrapper.get('#settings-account-email').element as HTMLInputElement).value).toBe(email)
    expect(updateMyBasicInfoMock).toHaveBeenCalledWith(accountPayload({ ...account, email }))
    expect(wrapper.text()).toContain('保存成功')
  })

  it('rejects a 256-character email before saving without truncating the input', async () => {
    const email = `${'a'.repeat(64)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(63)}`
    expect(email).toHaveLength(256)
    const response = deferred<typeof account>()
    updateMyBasicInfoMock.mockReturnValueOnce(response.promise)
    const { wrapper } = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-account-email').setValue(email)
    await wrapper.findAll('button').find(button => button.text() === '保存修改')!.trigger('click')

    expect(updateMyBasicInfoMock).not.toHaveBeenCalled()
    expect(wrapper.get('#settings-account-email').attributes('maxlength')).toBeUndefined()
    expect((wrapper.get('#settings-account-email').element as HTMLInputElement).value).toBe(email)
    expect(wrapper.get('#settings-account-email-error').text()).toBe('邮箱长度不能超过 255 个字符')
  })

  it('ignores old loads across account switches and ABA, then applies only the final load', async () => {
    vi.useFakeTimers()
    const oldLoad = deferred<typeof account>()
    const nextLoad = deferred<typeof accountB>()
    const finalLoad = deferred<typeof account>()
    getMyBasicInfoMock.mockReset()
    getMyBasicInfoMock
      .mockReturnValueOnce(oldLoad.promise)
      .mockReturnValueOnce(nextLoad.promise)
      .mockReturnValueOnce(finalLoad.promise)
    const { wrapper, auth } = createWrapper()

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'green_user' } })
    await vi.runOnlyPendingTimersAsync()
    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(2)
    oldLoad.resolve({ ...account, username: 'stale_a' })
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')

    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'blue_user' } })
    await vi.runOnlyPendingTimersAsync()
    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(3)
    nextLoad.resolve({ ...accountB, username: 'stale_b' })
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')

    finalLoad.resolve({ ...account, username: 'final_a' })
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('final_a')
  })

  it('does not apply a late load after unmount', async () => {
    const response = deferred<typeof account>()
    getMyBasicInfoMock.mockReturnValueOnce(response.promise)
    const { wrapper } = createWrapper()
    wrapper.unmount()
    response.resolve({ ...account, username: 'late_account' })
    await flushPromises()

    expect(wrapper.element.isConnected).toBe(false)
  })

  it('invalidates a pending profile save when the account changes and emits no stale update', async () => {
    const response = deferred<typeof account>()
    updateMyBasicInfoMock.mockReturnValueOnce(response.promise)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const { wrapper, auth } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('account_a_edit')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'green_user' } })
    response.resolve({ ...account, username: 'account_a_edit' })
    await flushPromises()

    expect(eventSpy).not.toHaveBeenCalled()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).not.toBe('account_a_edit')
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('invalidates a pending load when an explicit auth event reuses the same token and owner', async () => {
    vi.useFakeTimers()
    const oldLoad = deferred<typeof account>()
    const nextLoad = deferred<typeof account>()
    getMyBasicInfoMock.mockReset()
    getMyBasicInfoMock.mockReturnValueOnce(oldLoad.promise).mockReturnValueOnce(nextLoad.promise)
    const { wrapper } = createWrapper()

    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await vi.runOnlyPendingTimersAsync()
    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(2)

    oldLoad.resolve({ ...account, username: 'stale_same_token' })
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')

    nextLoad.resolve({ ...account, username: 'fresh_same_token' })
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('fresh_same_token')
  })

  it('invalidates a pending password response after account switch without clearing new-session inputs', async () => {
    const response = deferred<unknown>()
    updateMyPasswordMock.mockReturnValueOnce(response.promise)
    const { wrapper, auth } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
    await wrapper.findAll('button').filter((button) => button.text().includes('修改密码'))[0].trigger('click')

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'green_user' } })
    response.resolve({})
    await flushPromises()

    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-new-password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).not.toContain('密码已修改')
  })

  it('allows same-token owner hydration without resetting an edited account form', async () => {
    const { wrapper, auth } = createWrapper({ user: null })
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('继续编辑')
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')

    expect(auth.hydrateCurrentUser({ id: 1, username: 'blue_user' }, 'token-a')).toBe(true)
    await flushPromises()

    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(1)
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('继续编辑')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('CurrentA1')
  })

  it('clears a loaded response owner mismatch and safely reloads for same-token hydration', async () => {
    vi.useFakeTimers()
    getMyBasicInfoMock.mockReset()
    getMyBasicInfoMock.mockResolvedValueOnce(account).mockResolvedValueOnce(accountB)
    const { wrapper, auth } = createWrapper({ user: null })
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('继续编辑')
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')

    expect(auth.hydrateCurrentUser({ id: 2, username: 'green_user' }, 'token-a')).toBe(true)
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')

    await vi.runOnlyPendingTimersAsync()
    await flushPromises()

    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(2)
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('green_user')
  })

  it('continues an in-flight request when only authGeneration advances', async () => {
    const response = deferred<typeof account>()
    getMyBasicInfoMock.mockReturnValueOnce(response.promise)
    const { wrapper, auth } = createWrapper()
    auth.invalidateAuthOperations()
    response.resolve({ ...account, username: 'generation_safe' })
    await flushPromises()

    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('generation_safe')
  })

  it('clears stale account data and loading state when storage drifts before a load settles', async () => {
    const response = deferred<typeof account>()
    getMyBasicInfoMock.mockReset()
    getMyBasicInfoMock.mockResolvedValueOnce({ ...account }).mockReturnValueOnce(response.promise)
    const { wrapper } = createWrapper()
    await flushPromises()
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('blue_user')

    await wrapper.findAll('button').filter((button) => button.text() === '重置')[0].trigger('click')
    localStorage.setItem('accessToken', 'token-b')
    response.resolve({ ...account, username: 'stale_storage' })
    await flushPromises()

    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
    expect(wrapper.text()).not.toContain('正在加载账户信息...')
    expect(wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].attributes('disabled')).toBeDefined()
  })

  it('clears old account and password fields rather than submitting them after a storage switch', async () => {
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-current-password').setValue('OldCurrent1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
    localStorage.setItem('accessToken', 'token-b')
    await wrapper.findAll('button').find(button => button.text() === '修改密码')!.trigger('click')

    expect(updateMyPasswordMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-new-password').element as HTMLInputElement).value).toBe('')
  })

  it('uses the server-normalized baseline while retaining post-submit edits for the next save', async () => {
    const response = deferred<typeof account>()
    updateMyBasicInfoMock.mockReset()
    updateMyBasicInfoMock
      .mockReturnValueOnce(response.promise)
      .mockResolvedValueOnce(serverAccount({ username: 'mixeduser', phone: '13900000000', email: 'draft@example.com' }))
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('MixedUser')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await wrapper.get('#settings-account-email').setValue('draft@example.com')

    response.resolve(serverAccount({ username: 'mixeduser', phone: '13900000000', email: 'canonical@example.com' }))
    await flushPromises()

    expect((wrapper.get('#settings-account-username').element as HTMLInputElement).value).toBe('mixeduser')
    expect((wrapper.get('#settings-account-phone').element as HTMLInputElement).value).toBe('13900000000')
    expect((wrapper.get('#settings-account-email').element as HTMLInputElement).value).toBe('draft@example.com')
    expect(wrapper.emitted('dirty-change')).toContainEqual([true])

    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()
    expect(updateMyBasicInfoMock).toHaveBeenLastCalledWith({ username: 'mixeduser', phone: '13900000000', email: 'draft@example.com' })
    expect(wrapper.emitted('dirty-change')).toContainEqual([false])
  })

  it('syncs known-owner saved values and emits only the server username', async () => {
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    updateMyBasicInfoMock.mockResolvedValueOnce(serverAccount({ username: 'server_user', phone: '13900139000', email: 'server@example.com' }))
    const { wrapper, auth } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('draft_user')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()

    expect(auth.user).toEqual(expect.objectContaining({ username: 'server_user', phone: '13900139000', email: 'server@example.com' }))
    expect(eventSpy).toHaveBeenCalledTimes(1)
    expect((eventSpy.mock.calls[0][0] as CustomEvent).detail).toEqual({ id: 1, username: 'server_user' })
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('does not infer an owner or emit profile updates for an unknown token-only session', async () => {
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    updateMyBasicInfoMock.mockResolvedValueOnce(serverAccount({ username: 'server_user', phone: '13900139000', email: 'server@example.com' }))
    const { wrapper } = createWrapper({ user: null })
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('draft_user')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()

    expect(eventSpy).not.toHaveBeenCalled()
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('syncs a verified owner hydrated while a token-only save is pending', async () => {
    const response = deferred<typeof account>()
    updateMyBasicInfoMock.mockReturnValueOnce(response.promise)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const { wrapper, auth } = createWrapper({ user: null })
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('draft_user')
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')

    expect(auth.hydrateCurrentUser({ id: 1, username: 'blue_user' }, 'token-a')).toBe(true)
    response.resolve(serverAccount({ username: 'server_user', phone: '13900139000', email: 'server@example.com' }))
    await flushPromises()

    expect(auth.user).toEqual(expect.objectContaining({ username: 'server_user', phone: '13900139000', email: 'server@example.com' }))
    expect(eventSpy).toHaveBeenCalledTimes(1)
    expect((eventSpy.mock.calls[0][0] as CustomEvent).detail).toEqual({ id: 1, username: 'server_user' })
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('prevents duplicate profile saves and blocks reset while a save is pending', async () => {
    const response = deferred<typeof account>()
    updateMyBasicInfoMock.mockReturnValueOnce(response.promise)
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('pending_user')
    const saveButton = wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0]
    void saveButton.trigger('click')
    await Promise.resolve()
    await wrapper.findAll('button').filter((button) => button.text() === '重置')[0].trigger('click')
    await saveButton.trigger('click')

    expect(updateMyBasicInfoMock).toHaveBeenCalledTimes(1)
    expect(getMyBasicInfoMock).toHaveBeenCalledTimes(1)
    response.resolve({ ...account, username: 'pending_user' })
    await flushPromises()
  })

  it('preserves password edits made after submission and clears only unchanged fields', async () => {
    const response = deferred<unknown>()
    updateMyPasswordMock.mockReturnValueOnce(response.promise)
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue('NewPassword1')
    await wrapper.get('#settings-account-confirm-password').setValue('NewPassword1')
    await wrapper.findAll('button').filter((button) => button.text().includes('修改密码'))[0].trigger('click')
    await wrapper.get('#settings-account-new-password').setValue('NextPassword2')
    await wrapper.get('#settings-account-confirm-password').setValue('NextPassword2')

    response.resolve({})
    await flushPromises()

    expect((wrapper.get('#settings-account-current-password').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('#settings-account-new-password').element as HTMLInputElement).value).toBe('NextPassword2')
    expect((wrapper.get('#settings-account-confirm-password').element as HTMLInputElement).value).toBe('NextPassword2')
  })

  it('rejects a new password exceeding the byte limit before sending it to the API', async () => {
    const { wrapper } = createWrapper()
    await flushPromises()
    const password = `Aa1${'汉'.repeat(24)}`
    await wrapper.get('#settings-account-current-password').setValue('CurrentA1')
    await wrapper.get('#settings-account-new-password').setValue(password)
    await wrapper.get('#settings-account-confirm-password').setValue(password)
    await wrapper.findAll('button').find(button => button.text() === '修改密码')!.trigger('click')

    expect(updateMyPasswordMock).not.toHaveBeenCalled()
    expect(wrapper.get('#settings-account-new-password-error').text()).toBe('密码过长，请减少字符数量')
    expect((wrapper.get('#settings-account-new-password').element as HTMLInputElement).value).toBe(password)
  })

  it('cleans message timers and prevents an older timer from clearing a newer message', async () => {
    vi.useFakeTimers()
    const { wrapper } = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-account-username').setValue('first_user')
    updateMyBasicInfoMock.mockResolvedValueOnce({ ...account, username: 'first_user' })
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()
    vi.advanceTimersByTime(1000)

    await wrapper.get('#settings-account-username').setValue('second_user')
    updateMyBasicInfoMock.mockResolvedValueOnce({ ...account, username: 'second_user' })
    await wrapper.findAll('button').filter((button) => button.text().includes('保存修改'))[0].trigger('click')
    await flushPromises()
    vi.advanceTimersByTime(2100)
    expect(wrapper.get('[role="status"]').text()).toContain('保存成功')

    wrapper.unmount()
    vi.runOnlyPendingTimers()
    expect(vi.getTimerCount()).toBe(0)
  })
})

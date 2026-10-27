import { defineComponent, h } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, getStoredCurrentUser } from '@/utils/authStorage'
import AuthLoginForm from '../AuthLoginForm.vue'

const verifyMocks = vi.hoisted(() => ({
  ensureVerified: vi.fn(),
  reset: vi.fn(),
  resetIfMatches: vi.fn(),
}))

vi.mock('@/services/authVerify', async () => {
  const actual = await vi.importActual<typeof import('@/services/authVerify')>('@/services/authVerify')
  return {
    ...actual,
    isAuthVerifyEnabled: () => true,
  }
})

const VerifyStub = defineComponent({
  name: 'AuthVerifyBlock',
  setup(_, { expose }) {
    expose({
      ensureVerified: verifyMocks.ensureVerified,
      reset: verifyMocks.reset,
      resetIfMatches: verifyMocks.resetIfMatches,
    })
    return () => h('div', { 'data-test': 'verify-stub' })
  },
})

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

type CapturedRequest = {
  method: string
  url: string
  body: Record<string, unknown>
  config: InternalAxiosRequestConfig
  response: Deferred<unknown>
}

const originalAdapter = api.defaults.adapter
let pinia: ReturnType<typeof createPinia>
let auth: ReturnType<typeof useAuthStore>
let wrapper: VueWrapper | undefined
let requests: CapturedRequest[]

const proof = {
  token: 'login-proof',
  provider: 'mock-manual' as const,
  scene: 'login' as const,
  verifiedAt: '2026-01-01T00:00:00.000Z',
}

function parseRequestBody(config: InternalAxiosRequestConfig): Record<string, unknown> {
  const raw = config.data === undefined ? '{}' : config.data
  if (typeof raw !== 'string') throw new Error('Axios adapter expected transformed JSON request data')
  return JSON.parse(raw) as Record<string, unknown>
}

function installAdapter(): void {
  requests = []
  api.defaults.adapter = (config) => {
    const response = deferred<unknown>()
    const request: CapturedRequest = {
      method: String(config.method || 'get').toLowerCase(),
      url: String(config.url || ''),
      body: parseRequestBody(config),
      config,
      response,
    }
    requests.push(request)
    return response.promise.then((data) => ({
      config,
      data,
      headers: {},
      status: 200,
      statusText: 'OK',
    } as never))
  }
}

function mountLogin(props: { initialPhone?: string; initialEmail?: string } = {}) {
  return mount(AuthLoginForm, {
    props,
    global: {
      plugins: [pinia],
      stubs: { AuthVerifyBlock: VerifyStub },
    },
  })
}

function findButton(login: VueWrapper, pattern: RegExp) {
  const button = login.findAll('button').find(candidate => pattern.test(candidate.text()))
  if (!button) throw new Error(`Could not find button matching ${pattern}`)
  return button
}

function getPhoneField(login: VueWrapper) {
  const phoneField = login.find('#auth-login-phone')
  return phoneField.exists() ? phoneField : login.get('#auth-login-phone-code')
}

function findRequest(method: string, url: string, occurrence = 0): CapturedRequest {
  const matching = requests.filter(request => request.method === method && request.url === url)
  const request = matching[occurrence]
  if (!request) throw new Error(`Missing ${method.toUpperCase()} ${url} request #${occurrence + 1}`)
  return request
}

function resolveLogin(request: CapturedRequest, accessToken: string, user: { id: number; username: string }): void {
  request.response.resolve({ accessToken, user })
}

function resolveCodeStart(request: CapturedRequest, session: string, maskedAddress: string): void {
  request.response.resolve({
    channel: request.body.channel,
    maskedAddress,
    session,
    expireSeconds: 300,
    cooldownSeconds: 1,
  })
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

async function fillPhonePassword(login: VueWrapper, phone: string, password: string): Promise<void> {
  await login.get('#auth-login-phone').setValue(phone)
  await login.get('#auth-login-password').setValue(password)
}

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  sessionStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  auth = useAuthStore()
  verifyMocks.ensureVerified.mockReset()
  verifyMocks.reset.mockReset()
  verifyMocks.resetIfMatches.mockReset()
  verifyMocks.ensureVerified.mockResolvedValue(proof)
  verifyMocks.resetIfMatches.mockReturnValue(true)
  vi.stubGlobal('alert', vi.fn())
  window.__authExpiredAlertShown = false
  installAdapter()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  api.defaults.adapter = originalAdapter
  localStorage.clear()
  sessionStorage.clear()
  window.__authExpiredAlertShown = false
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AuthLoginForm transport ownership', () => {
  it('does not send a login request when verification resolves after unmount', async () => {
    const verification = deferred<typeof proof>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'Password1')

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    wrapper.unmount()
    verification.resolve(proof)
    await settle()

    expect(requests).toHaveLength(0)
    expect(wrapper.emitted('notify')).toBeUndefined()
  })

  it('does not send a code-start request when verification resolves after unmount', async () => {
    const verification = deferred<typeof proof>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue('13800138000')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    wrapper.unmount()
    verification.resolve(proof)
    await settle()

    expect(requests).toHaveLength(0)
    expect(wrapper.emitted('notify')).toBeUndefined()
  })

  it('coalesces duplicate submits while verification is pending into one real login request', async () => {
    const verification = deferred<typeof proof>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'Password1')

    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()
    expect(requests).toHaveLength(0)

    verification.resolve(proof)
    await settle()
    expect(requests.filter(request => request.url === '/auth/login')).toHaveLength(1)

    const request = findRequest('post', '/auth/login')
    resolveLogin(request, 'phone-login-token', { id: 1, username: 'phone_user' })
    await settle()
  })

  it('sends an immutable phone/password snapshot, including a legacy password over 72 UTF-8 bytes', async () => {
    const verification = deferred<typeof proof>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    wrapper = mountLogin()
    const phone = '13800138000'
    const password = `Aa1${'汉'.repeat(24)}`
    await fillPhonePassword(wrapper, phone, password)

    await wrapper.get('form').trigger('submit')
    await settle()
    await wrapper.get('#auth-login-password').setValue('EditedPassword2')

    verification.resolve(proof)
    await settle()
    const request = findRequest('post', '/auth/login')
    expect(request.body).toMatchObject({
      phone,
      password,
      verifyToken: proof.token,
      verifyProvider: proof.provider,
      verifyScene: proof.scene,
    })
    expect(request.body.password).toBe(password)

    resolveLogin(request, 'snapshot-token', { id: 2, username: 'snapshot_user' })
    await settle()
  })

  it('does not let a cancelled old login overwrite a newer external session', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'Password1')
    await wrapper.get('form').trigger('submit')
    await settle()
    const oldRequest = findRequest('post', '/auth/login')

    await findButton(wrapper, /切换到邮箱登录/).trigger('click')
    await wrapper.get('#auth-login-email').setValue('new@example.test')
    await wrapper.get('#auth-login-password').setValue('NewPassword1')
    auth.applyAuthState({ accessToken: 'new-external-token', user: { id: 3, username: 'new_owner' } })

    resolveLogin(oldRequest, 'old-login-token', { id: 4, username: 'old_owner' })
    await settle()

    expect(auth.token).toBe('new-external-token')
    expect(getStoredAccessToken()).toBe('new-external-token')
    expect(getStoredCurrentUser('new-external-token')).toMatchObject({ id: 3, username: 'new_owner' })
    expect(getStoredCurrentUser('old-login-token')).toBeNull()
  })

  it('does not apply a login reply after storage changes owners without an auth event', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'Password1')
    await wrapper.get('form').trigger('submit')
    await settle()
    const request = findRequest('post', '/auth/login')

    localStorage.setItem('accessToken', 'storage-owner-token')
    request.response.resolve({
      accessToken: 'old-reply-token',
      user: { id: 9, username: 'old_reply_owner' },
    })
    await settle()

    expect(auth.token).toBe('')
    expect(getStoredAccessToken()).toBe('storage-owner-token')
    expect(getStoredCurrentUser('old-reply-token')).toBeNull()
    expect(wrapper.emitted('success')).toBeUndefined()
    expect(wrapper.emitted('notify')).toBeUndefined()
  })

  it('does not issue login after storage changes owners while verification is pending', async () => {
    const verification = deferred<typeof proof>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'Password1')
    await wrapper.get('form').trigger('submit')
    await settle()

    localStorage.setItem('accessToken', 'storage-owner-token')
    verification.resolve(proof)
    await settle()

    expect(requests.filter(request => request.url === '/auth/login')).toHaveLength(0)
    expect(getStoredAccessToken()).toBe('storage-owner-token')
  })

  it('keeps an unrelated current session after a password-login 401', async () => {
    auth.applyAuthState({ accessToken: 'current-session-token', user: { id: 5, username: 'current_owner' } })
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'WrongPassword1')
    await wrapper.get('form').trigger('submit')
    await settle()
    const request = findRequest('post', '/auth/login')
    request.response.reject({
      config: request.config,
      response: { status: 401, data: { message: '账号或密码错误' } },
    })
    await settle()

    expect(auth.token).toBe('current-session-token')
    expect(getStoredAccessToken()).toBe('current-session-token')
    expect(getStoredCurrentUser('current-session-token')).toMatchObject({ id: 5, username: 'current_owner' })
    expect(window.alert).not.toHaveBeenCalled()
  })

  it('uses one proof for phone OTP start, then finishes with its accepted address/session and no second proof', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue(' 13800138000 ')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()

    const start = findRequest('post', '/auth/code/start')
    expect(start.body).toEqual({
      channel: 'phone',
      address: '13800138000',
      verifyToken: proof.token,
      verifyProvider: proof.provider,
      verifyScene: proof.scene,
    })
    resolveCodeStart(start, 'phone-code-session', '138****0000')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    await wrapper.get('#auth-login-code').setValue(' 2468 ')
    await wrapper.get('form').trigger('submit')
    await settle()

    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()
    const finish = findRequest('post', '/auth/code/finish')
    expect(finish.body).toEqual({
      channel: 'phone',
      address: '13800138000',
      code: '2468',
      session: 'phone-code-session',
    })
    expect(finish.body).not.toHaveProperty('verifyToken')
    expect(finish.body).not.toHaveProperty('verifyProvider')
    expect(finish.body).not.toHaveProperty('verifyScene')

    resolveLogin(finish, 'otp-session-token', { id: 6, username: 'otp_owner' })
    await settle()
    expect(auth.token).toBe('otp-session-token')
    expect(getStoredAccessToken()).toBe('otp-session-token')
    expect(getStoredCurrentUser('otp-session-token')).toMatchObject({ id: 6, username: 'otp_owner' })
  })

  it('does not finish an OTP session accepted under an older storage owner', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue('13800138000')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()
    const start = findRequest('post', '/auth/code/start')
    resolveCodeStart(start, 'owner-bound-session', '138****0000')
    await settle()

    expect(wrapper.text()).toContain('138****0000')
    localStorage.setItem('accessToken', 'storage-owner-token')
    await wrapper.get('#auth-login-code').setValue('2468')
    await wrapper.get('form').trigger('submit')
    await settle()

    expect(requests.filter(request => request.url === '/auth/code/finish')).toHaveLength(0)
    expect(auth.token).toBe('')
    expect(getStoredAccessToken()).toBe('storage-owner-token')
    expect(wrapper.emitted('success')).toBeUndefined()
  })

  it('retries a wrong OTP code with the same session without a second verification', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue('13800138000')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()
    const start = findRequest('post', '/auth/code/start')
    resolveCodeStart(start, 'retryable-code-session', '138****0000')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    await wrapper.get('#auth-login-code').setValue('2468')
    await wrapper.get('form').trigger('submit')
    await settle()
    const firstFinish = findRequest('post', '/auth/code/finish')
    expect(firstFinish.body).toEqual({
      channel: 'phone',
      address: '13800138000',
      code: '2468',
      session: 'retryable-code-session',
    })
    firstFinish.response.reject({
      config: firstFinish.config,
      response: { status: 401, data: { message: '验证码错误' } },
    })
    await settle()
    expect(auth.token).toBe('')
    expect(getStoredAccessToken()).toBe('')

    await wrapper.get('#auth-login-code').setValue('2469')
    await wrapper.get('form').trigger('submit')
    await settle()
    const secondFinish = findRequest('post', '/auth/code/finish', 1)
    expect(secondFinish.body).toEqual({
      channel: 'phone',
      address: '13800138000',
      code: '2469',
      session: 'retryable-code-session',
    })
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    resolveLogin(secondFinish, 'otp-retry-token', { id: 10, username: 'otp_retry_owner' })
    await settle()
    expect(auth.token).toBe('otp-retry-token')
    expect(getStoredAccessToken()).toBe('otp-retry-token')
    expect(getStoredCurrentUser('otp-retry-token')).toMatchObject({ id: 10, username: 'otp_retry_owner' })
  })

  it('does not apply a stale code-start response to a newer address or mode', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue('13800138000')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()
    const staleStart = findRequest('post', '/auth/code/start')

    await findButton(wrapper, /邮箱验证码登录/).trigger('click')
    await wrapper.get('#auth-login-email').setValue('new@example.test')
    staleStart.response.resolve({
      channel: 'phone',
      maskedAddress: '138****0000',
      session: 'stale-phone-session',
      expireSeconds: 300,
      cooldownSeconds: 1,
    })
    await settle()

    expect(wrapper.text()).not.toContain('138****0000')
    await wrapper.get('#auth-login-code').setValue('2468')
    await wrapper.get('form').trigger('submit')
    await settle()
    expect(requests.filter(request => request.url === '/auth/code/finish')).toHaveLength(0)
    const notifications = wrapper.emitted('notify') || []
    expect(notifications[notifications.length - 1]).toEqual(['请先获取邮箱验证码'])
  })

  it.each(['success', 'error'] as const)('ignores a deferred code-start %s after storage changes owners without an auth event', async (outcome) => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue('13800138000')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()
    const request = findRequest('post', '/auth/code/start')

    localStorage.setItem('accessToken', 'storage-owner-token')
    if (outcome === 'success') {
      resolveCodeStart(request, 'stale-storage-session', '138****0000')
    } else {
      request.response.reject({
        config: request.config,
        response: { status: 503, data: { message: '旧验证码发送失败' } },
      })
    }
    await settle()

    expect(wrapper.text()).not.toContain('138****0000')
    const codeButton = wrapper.findAll('button').find(button => /获取验证码|后重试/.test(button.text()))
    expect(codeButton?.text()).toBe('获取验证码')
    const notifications = wrapper.emitted('notify') || []
    expect(notifications.some(([message]) => String(message).includes('验证码'))).toBe(false)
    expect(requests.filter(item => item.url === '/auth/code/finish')).toHaveLength(0)
  })

  it('ignores a late OTP finish error after another session takes over', async () => {
    wrapper = mountLogin()
    await findButton(wrapper, /验证码登录/).trigger('click')
    await getPhoneField(wrapper).setValue('13800138000')
    await findButton(wrapper, /获取验证码/).trigger('click')
    await settle()
    resolveCodeStart(findRequest('post', '/auth/code/start'), 'old-code-session', '138****0000')
    await settle()
    await wrapper.get('#auth-login-code').setValue('2468')
    await wrapper.get('form').trigger('submit')
    await settle()
    const request = findRequest('post', '/auth/code/finish')
    const notificationsBefore = wrapper.emitted('notify')?.length || 0
    auth.applyAuthState({ accessToken: 'replacement-token', user: { id: 12, username: 'replacement' } })
    request.response.reject({
      config: request.config,
      response: { status: 401, data: { message: '旧验证码已过期' } },
    })
    await settle()
    expect(auth.token).toBe('replacement-token')
    expect(getStoredAccessToken()).toBe('replacement-token')
    expect(getStoredCurrentUser('replacement-token')?.id).toBe(12)
    expect(wrapper.emitted('notify')?.length || 0).toBe(notificationsBefore)
    expect(wrapper.emitted('success')).toBeUndefined()
  })

  it('does not let an old form unmount cancel a newer external store login', async () => {
    verifyMocks.ensureVerified.mockResolvedValue(proof)
    wrapper = mountLogin()
    await fillPhonePassword(wrapper, '13800138000', 'Password1')
    await wrapper.get('form').trigger('submit')
    await settle()
    const oldRequest = findRequest('post', '/auth/login')

    const newerLogin = auth.loginWithEmailPassword({ email: 'new@example.test', password: 'NewPassword1' })
    await settle()
    const newerRequest = findRequest('post', '/auth/login', 1)

    wrapper.unmount()
    resolveLogin(newerRequest, 'newer-store-token', { id: 7, username: 'newer_owner' })
    await expect(newerLogin).resolves.toBeUndefined()
    expect(auth.token).toBe('newer-store-token')
    expect(getStoredAccessToken()).toBe('newer-store-token')

    resolveLogin(oldRequest, 'old-form-token', { id: 8, username: 'old_form_owner' })
    await settle()
    expect(auth.token).toBe('newer-store-token')
    expect(getStoredAccessToken()).toBe('newer-store-token')
    expect(getStoredCurrentUser('old-form-token')).toBeNull()
  })
})

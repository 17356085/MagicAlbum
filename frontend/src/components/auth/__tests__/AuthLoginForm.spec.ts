import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

type VerifyResult = {
  token: string
  provider: 'mock-manual'
  scene: 'login'
  verifiedAt: string
}

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

const apiMocks = vi.hoisted(() => ({
  startEmailCodeLogin: vi.fn(),
  startPhoneCodeLogin: vi.fn(),
}))

const verifyMocks = vi.hoisted(() => ({
  ensureVerified: vi.fn(),
  reset: vi.fn(),
  resetIfMatches: vi.fn(),
  isAuthVerifyEnabled: vi.fn(() => true),
  toVerifyPayload: vi.fn((result: VerifyResult | null | undefined) => result?.token
    ? { verifyToken: result.token, verifyProvider: result.provider, verifyScene: result.scene }
    : {}),
}))

const authMocks = vi.hoisted(() => {
  let generation = 0
  const store = {
    token: '',
    getAuthGeneration: vi.fn(() => generation),
    isAuthGenerationCurrent: vi.fn((value: number) => value === generation),
    invalidateAuthOperations: vi.fn(() => {
      generation += 1
      return generation
    }),
    loginWithPhonePassword: vi.fn((..._args: unknown[]) => {
      generation += 1
      return Promise.resolve()
    }),
    loginWithEmailPassword: vi.fn((..._args: unknown[]) => {
      generation += 1
      return Promise.resolve()
    }),
    loginWithPhoneCode: vi.fn((..._args: unknown[]) => {
      generation += 1
      return Promise.resolve()
    }),
    loginWithEmailCode: vi.fn((..._args: unknown[]) => {
      generation += 1
      return Promise.resolve()
    }),
  }
  return {
    store,
    resetGeneration: () => { generation = 0 },
    advanceGeneration: () => { generation += 1 },
  }
})

vi.mock('@/api/auth', () => apiMocks)
vi.mock('@/services/authVerify', () => verifyMocks)
vi.mock('@/stores/auth', () => ({
  isAuthOperationSuperseded: (error: unknown) => (error as { code?: string } | null)?.code === 'AUTH_OPERATION_SUPERSEDED',
  useAuthStore: () => authMocks.store,
}))

const VerifyStub = defineComponent({
  setup(_, { expose }) {
    expose({
      ensureVerified: verifyMocks.ensureVerified,
      reset: verifyMocks.reset,
      resetIfMatches: verifyMocks.resetIfMatches,
    })
    return () => h('div', { 'data-test': 'verify-stub' })
  },
})

import AuthLoginForm from '../AuthLoginForm.vue'

function mountLogin(props: { initialPhone?: string; initialEmail?: string } = {}) {
  return mount(AuthLoginForm, {
    props,
    global: {
      stubs: { AuthVerifyBlock: VerifyStub },
    },
  })
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function proof(token = 'proof-1'): VerifyResult {
  return {
    token,
    provider: 'mock-manual',
    scene: 'login',
    verifiedAt: '2026-01-01T00:00:00.000Z',
  }
}

async function useEmailCodeMode(wrapper: ReturnType<typeof mountLogin>): Promise<void> {
  await wrapper.get('button').trigger('click')
  const emailCodeButton = wrapper.findAll('button').find((button) => button.text() === '验证码登录')
  await emailCodeButton!.trigger('click')
}

describe('AuthLoginForm async lifecycle', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    authMocks.resetGeneration()
    localStorage.clear()
    sessionStorage.clear()
    verifyMocks.isAuthVerifyEnabled.mockReturnValue(true)
    verifyMocks.toVerifyPayload.mockImplementation((result: VerifyResult | null | undefined) => result?.token
      ? { verifyToken: result.token, verifyProvider: result.provider, verifyScene: result.scene }
      : {})
    verifyMocks.resetIfMatches.mockReturnValue(true)
    verifyMocks.ensureVerified.mockResolvedValue(proof())
    apiMocks.startEmailCodeLogin.mockResolvedValue({
      channel: 'email',
      maskedAddress: 'u***@example.com',
      session: 'email-session',
      expireSeconds: 300,
      cooldownSeconds: 60,
    })
    apiMocks.startPhoneCodeLogin.mockResolvedValue({
      channel: 'phone',
      maskedAddress: '138****0000',
      session: 'phone-session',
      expireSeconds: 300,
      cooldownSeconds: 60,
    })
  })

  it('guards duplicate submits while verification is pending', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')

    const submit = wrapper.get('form')
    const first = submit.trigger('submit')
    await submit.trigger('submit')
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    verification.resolve(proof())
    await first
    await settle()
    expect(authMocks.store.loginWithPhonePassword).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('guards duplicate code sends while verification is pending', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('send-once@example.com')
    const send = () => wrapper.findAll('button').find((button) => button.text() === '获取验证码' || button.text() === '发送中…')!
    const first = send().trigger('click')
    await send().trigger('click')
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()

    verification.resolve(proof())
    await first
    await settle()
    expect(apiMocks.startEmailCodeLogin).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('guards duplicate code finishes while the login action is pending', async () => {
    const finish = deferred<void>()
    authMocks.store.loginWithEmailCode.mockImplementationOnce(() => {
      authMocks.advanceGeneration()
      return finish.promise
    })
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('finish-once@example.com')
    await wrapper.findAll('button').find((button) => button.text() === '获取验证码')!.trigger('click')
    await settle()
    await wrapper.get('#auth-login-code').setValue('123456')

    const first = wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(authMocks.store.loginWithEmailCode).toHaveBeenCalledOnce()
    finish.resolve()
    await first
    await settle()
    wrapper.unmount()
  })

  it('uses an immutable password snapshot after verification', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Original1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()

    await wrapper.get('#auth-login-password').setValue('Edited99')
    verification.resolve(proof('immutable-proof'))
    await pending
    await settle()

    expect(authMocks.store.loginWithPhonePassword).toHaveBeenCalledWith({
      phone: '13800000000',
      password: 'Original1',
      verifyToken: 'immutable-proof',
      verifyProvider: 'mock-manual',
      verifyScene: 'login',
    })
    wrapper.unmount()
  })

  it('cancels a pending send when the address changes and ignores the late response', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('first@example.com')
    const send = wrapper.findAll('button').find((button) => button.text() === '获取验证码')!
    const pending = send.trigger('click')
    await settle()

    await wrapper.get('#auth-login-email').setValue('second@example.com')
    verification.resolve(proof())
    await pending
    await settle()

    expect(apiMocks.startEmailCodeLogin).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('u***@example.com')
    wrapper.unmount()
  })

  it('cancels a pending verification when the mode changes', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '验证码登录')!.trigger('click')
    verification.resolve(proof())
    await pending
    await settle()
    expect(authMocks.store.loginWithPhonePassword).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('invalidates a pending verification when a seed prop changes', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin({ initialEmail: 'seed-one@example.com' })
    await wrapper.findAll('button').find((button) => button.text() === '切换到邮箱登录')!.trigger('click')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()

    await wrapper.setProps({ initialEmail: 'seed-two@example.com' })
    verification.resolve(proof())
    await pending
    await settle()
    expect(authMocks.store.loginWithEmailPassword).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not continue after captcha resolves when the form unmounts', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()
    wrapper.unmount()

    verification.resolve(proof())
    await pending
    await settle()
    expect(authMocks.store.loginWithPhonePassword).not.toHaveBeenCalled()
    expect(apiMocks.startPhoneCodeLogin).not.toHaveBeenCalled()
  })

  it('blocks a request when the persisted auth token changes during verification', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()

    localStorage.setItem('accessToken', 'external-token')
    verification.resolve(proof())
    await pending
    await settle()
    expect(authMocks.store.loginWithPhonePassword).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('suppresses a late send result after unmount', async () => {
    const send = deferred<{ channel: 'email'; maskedAddress: string; session: string; expireSeconds: number; cooldownSeconds: number }>()
    apiMocks.startEmailCodeLogin.mockReturnValueOnce(send.promise)
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('unmount-send@example.com')
    await wrapper.findAll('button').find((button) => button.text() === '获取验证码')!.trigger('click')
    await settle()
    expect(apiMocks.startEmailCodeLogin).toHaveBeenCalledOnce()
    wrapper.unmount()

    send.resolve({ channel: 'email', maskedAddress: 'u***', session: 'late', expireSeconds: 300, cooldownSeconds: 60 })
    await settle()
    expect(wrapper.emitted('notify')).toBeUndefined()
  })

  it('binds a code session to the normalized address and finishes without another captcha', async () => {
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue(' User@Example.COM ')
    const send = wrapper.findAll('button').find((button) => button.text() === '获取验证码')!
    await send.trigger('click')
    await settle()
    expect(apiMocks.startEmailCodeLogin).toHaveBeenCalledWith(expect.objectContaining({
      channel: 'email',
      address: 'user@example.com',
    }))

    await wrapper.get('#auth-login-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledOnce()
    expect(authMocks.store.loginWithEmailCode).toHaveBeenCalledWith({
      channel: 'email',
      address: 'user@example.com',
      code: '123456',
      session: 'email-session',
    })
    wrapper.unmount()
  })

  it('rejects a code finish when the accepted session boundary becomes stale', async () => {
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('stale-session@example.com')
    await wrapper.findAll('button').find((button) => button.text() === '获取验证码')!.trigger('click')
    await settle()
    localStorage.setItem('accessToken', 'new-external-token')
    await wrapper.get('#auth-login-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await settle()

    expect(authMocks.store.loginWithEmailCode).not.toHaveBeenCalled()
    expect(wrapper.emitted('notify')?.some(([message]) => message === '验证码会话已失效，请重新获取验证码')).toBe(true)
    wrapper.unmount()
  })

  it('keeps a bound code session for a wrong-code retry after its own generation advance', async () => {
    const firstFailure = new Error('验证码错误')
    const retry = deferred<void>()
    authMocks.store.loginWithEmailCode
      .mockImplementationOnce(() => {
        authMocks.advanceGeneration()
        return Promise.reject(firstFailure)
      })
      .mockImplementationOnce(() => {
        authMocks.advanceGeneration()
        return retry.promise
      })
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('retry-session@example.com')
    await wrapper.findAll('button').find((button) => button.text() === '获取验证码')!.trigger('click')
    await settle()
    await wrapper.get('#auth-login-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await settle()
    expect(authMocks.store.loginWithEmailCode).toHaveBeenCalledOnce()

    const second = wrapper.get('form').trigger('submit')
    await settle()
    expect(authMocks.store.loginWithEmailCode).toHaveBeenCalledTimes(2)
    retry.resolve()
    await second
    await settle()
    wrapper.unmount()
  })

  it('rejects code finish without a bound session before captcha or API calls', async () => {
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('user@example.com')
    await wrapper.get('#auth-login-code').setValue('123456')
    await wrapper.get('form').trigger('submit')
    await settle()

    expect(verifyMocks.ensureVerified).not.toHaveBeenCalled()
    expect(authMocks.store.loginWithEmailCode).not.toHaveBeenCalled()
    const notifications = wrapper.emitted('notify') || []
    expect(notifications[notifications.length - 1]).toEqual(['请先获取邮箱验证码'])
    wrapper.unmount()
  })

  it('does not let an old finally clear a newer busy operation', async () => {
    const firstSend = deferred<{ channel: 'email'; maskedAddress: string; session: string; expireSeconds: number; cooldownSeconds: number }>()
    const secondSend = deferred<{ channel: 'email'; maskedAddress: string; session: string; expireSeconds: number; cooldownSeconds: number }>()
    apiMocks.startEmailCodeLogin
      .mockReturnValueOnce(firstSend.promise)
      .mockReturnValueOnce(secondSend.promise)
    const wrapper = mountLogin()
    await useEmailCodeMode(wrapper)
    await wrapper.get('#auth-login-email').setValue('first@example.com')
    const send = () => wrapper.findAll('button').find((button) => button.text() === '获取验证码' || button.text() === '发送中…')!
    const first = send().trigger('click')
    await settle()

    await wrapper.get('#auth-login-email').setValue('second@example.com')
    const second = send().trigger('click')
    await settle()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    firstSend.resolve({ channel: 'email', maskedAddress: 'f***', session: 'first', expireSeconds: 300, cooldownSeconds: 60 })
    await first
    await settle()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()

    secondSend.resolve({ channel: 'email', maskedAddress: 's***', session: 'second', expireSeconds: 300, cooldownSeconds: 60 })
    await second
    await settle()
    wrapper.unmount()
  })

  it('resets only the consumed proof after a password request settles', async () => {
    const firstProof = proof('request-proof')
    verifyMocks.ensureVerified.mockResolvedValueOnce(firstProof)
    const request = deferred<void>()
    authMocks.store.loginWithPhonePassword.mockImplementationOnce(() => {
      authMocks.advanceGeneration()
      return request.promise
    })
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()
    request.resolve()
    await pending
    await settle()
    expect(verifyMocks.resetIfMatches).toHaveBeenCalledWith(firstProof)
    wrapper.unmount()
  })

  it('keeps a newer proof when an older password request settles', async () => {
    const firstProof = proof('old-proof')
    verifyMocks.ensureVerified.mockResolvedValueOnce(firstProof)
    verifyMocks.resetIfMatches.mockReturnValue(false)
    const request = deferred<void>()
    authMocks.store.loginWithPhonePassword.mockImplementationOnce(() => {
      authMocks.advanceGeneration()
      return request.promise
    })
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()
    request.resolve()
    await pending
    await settle()
    expect(verifyMocks.resetIfMatches).toHaveBeenCalledWith(firstProof)
    expect(wrapper.find('[data-test="verify-stub"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('releases local busy state when an issued login is superseded externally', async () => {
    const request = deferred<void>()
    authMocks.store.loginWithPhonePassword.mockImplementationOnce(() => {
      authMocks.advanceGeneration()
      return request.promise
    })
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()
    authMocks.advanceGeneration()
    request.resolve()
    await pending
    await settle()
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('success')).toBeUndefined()
    wrapper.unmount()
  })

  it('does not invalidate a newer external auth operation when the form unmounts', async () => {
    const verification = deferred<VerifyResult>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountLogin()
    await wrapper.get('#auth-login-phone').setValue('13800000000')
    await wrapper.get('#auth-login-password').setValue('Password1')
    const pending = wrapper.get('form').trigger('submit')
    await settle()

    authMocks.advanceGeneration()
    wrapper.unmount()
    verification.resolve(proof())
    await pending
    await settle()

    expect(authMocks.store.invalidateAuthOperations).not.toHaveBeenCalled()
  })
})

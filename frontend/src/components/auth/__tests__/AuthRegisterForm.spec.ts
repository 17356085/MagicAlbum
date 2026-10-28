import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const apiMocks = vi.hoisted(() => ({
  checkUsernameAvailable: vi.fn(),
  registerUser: vi.fn(),
}))

const verifyMocks = vi.hoisted(() => {
  const isUsable = (result: { token?: string; expiresAt?: string } | null | undefined): boolean => {
    if (!result || !result.token) return false
    if (result.expiresAt === undefined) return true
    const expiryTime = Date.parse(result.expiresAt)
    return Number.isFinite(expiryTime) && Date.now() < expiryTime
  }

  return {
    ensureVerified: vi.fn(),
    reset: vi.fn(),
    resetIfMatches: vi.fn(),
    isAuthVerifyEnabled: vi.fn(() => true),
    toVerifyPayload: vi.fn((result: { token?: string; provider?: string; scene?: string; expiresAt?: string } | null | undefined) => {
      return result && isUsable(result)
        ? { verifyToken: result.token, verifyProvider: 'mock-manual', verifyScene: 'register' }
        : {}
    }),
  }
})

vi.mock('@/api/users', () => apiMocks)
vi.mock('@/services/authVerify', () => verifyMocks)

import AuthRegisterForm from '../AuthRegisterForm.vue'

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

function mountRegister() {
  return mount(AuthRegisterForm, {
    global: {
      stubs: { AuthVerifyBlock: VerifyStub },
    },
  })
}

async function fillValidForm(wrapper: ReturnType<typeof mountRegister>, overrides: Record<string, string> = {}) {
  const values = {
    username: 'register_user',
    phone: '13800000000',
    email: 'register@example.com',
    password: 'StrongPass1',
    confirmPassword: 'StrongPass1',
    ...overrides,
  }
  await wrapper.get('#auth-register-username').setValue(values.username)
  await wrapper.get('#auth-register-phone').setValue(values.phone)
  await wrapper.get('#auth-register-email').setValue(values.email)
  await wrapper.get('#auth-register-password').setValue(values.password)
  await wrapper.get('#auth-register-confirm-password').setValue(values.confirmPassword)
  return values
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

const invalidLocalFields: Record<string, string>[] = [
  { phone: '123' },
  { email: 'invalid' },
  { password: 'short', confirmPassword: 'short' },
]

describe('AuthRegisterForm async lifecycle', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    verifyMocks.isAuthVerifyEnabled.mockReturnValue(true)
    verifyMocks.toVerifyPayload.mockImplementation(result => result && (!result.expiresAt || Date.parse(result.expiresAt) > Date.now())
      ? { verifyToken: result.token, verifyProvider: 'mock-manual', verifyScene: 'register' }
      : {})
    apiMocks.checkUsernameAvailable.mockResolvedValue(true)
    apiMocks.registerUser.mockResolvedValue({
      id: 1,
      username: 'register_user',
      phone: '13800000000',
      email: 'register@example.com',
    })
    verifyMocks.ensureVerified.mockResolvedValue({
      token: 'verify-token',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    })
    verifyMocks.resetIfMatches.mockReturnValue(true)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('locks duplicate submits before the first asynchronous availability check', async () => {
    const availability = deferred<boolean>()
    apiMocks.checkUsernameAvailable.mockReturnValueOnce(availability.promise)
    const wrapper = mountRegister()
    await fillValidForm(wrapper)

    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('#auth-register-username').attributes('disabled')).toBeDefined()
    expect(wrapper.get('#auth-register-phone').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button[type="button"]').attributes('disabled')).toBeDefined()
    expect(apiMocks.checkUsernameAvailable).toHaveBeenCalledTimes(1)
    availability.resolve(true)
    await settle()

    expect(verifyMocks.ensureVerified).toHaveBeenCalledTimes(1)
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(1)
    await wrapper.get('form').trigger('submit')
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('keeps the latest username blur result when availability responses resolve out of order', async () => {
    const first = deferred<boolean>()
    const second = deferred<boolean>()
    apiMocks.checkUsernameAvailable
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
    const wrapper = mountRegister()
    const username = wrapper.get('#auth-register-username')

    await username.setValue('user_a')
    await username.trigger('blur')
    await username.setValue('user_b')
    await username.trigger('blur')

    expect(apiMocks.checkUsernameAvailable).toHaveBeenCalledTimes(2)
    second.resolve(false)
    await settle()
    expect(wrapper.get('p').text()).toBe('用户名不可重复')

    first.resolve(true)
    await settle()
    expect(wrapper.get('p').text()).toBe('用户名不可重复')
    wrapper.unmount()
  })

  it('coalesces a username blur and submit availability check for the same value', async () => {
    const availability = deferred<boolean>()
    apiMocks.checkUsernameAvailable.mockReturnValueOnce(availability.promise)
    const wrapper = mountRegister()
    await fillValidForm(wrapper, { username: 'coalesced_user' })

    await wrapper.get('#auth-register-username').trigger('blur')
    await wrapper.get('form').trigger('submit')
    expect(apiMocks.checkUsernameAvailable).toHaveBeenCalledTimes(1)

    availability.resolve(true)
    await settle()
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it.each([21, 64])('submits a valid username with %s characters', async (length) => {
    const wrapper = mountRegister()
    await fillValidForm(wrapper, { username: 'a'.repeat(length) })

    expect(wrapper.get('#auth-register-username').attributes('maxlength')).toBe('64')
    await wrapper.get('form').trigger('submit')
    await settle()

    expect(apiMocks.checkUsernameAvailable).toHaveBeenCalledWith('a'.repeat(length))
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('blocks a 65-character username before availability or registration', async () => {
    const wrapper = mountRegister()
    await fillValidForm(wrapper, { username: 'a'.repeat(65) })

    await wrapper.get('form').trigger('submit')
    await settle()

    expect(apiMocks.checkUsernameAvailable).not.toHaveBeenCalled()
    expect(apiMocks.registerUser).not.toHaveBeenCalled()
    expect(wrapper.find('p.text-red-600').text()).toBe('3-64 位字母、数字或下划线')
    wrapper.unmount()
  })

  it('allows a 255-character email to continue registration', async () => {
    const email = `${'a'.repeat(63)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(63)}`
    expect(email).toHaveLength(255)
    const wrapper = mountRegister()
    await fillValidForm(wrapper, { email })

    await wrapper.get('form').trigger('submit')
    await settle()

    expect(wrapper.get('#auth-register-email').attributes('maxlength')).toBeUndefined()
    expect((wrapper.get('#auth-register-email').element as HTMLInputElement).value).toBe(email)
    expect(apiMocks.checkUsernameAvailable).toHaveBeenCalledWith('register_user')
    expect(verifyMocks.ensureVerified).toHaveBeenCalledTimes(1)
    expect(apiMocks.registerUser).toHaveBeenCalledWith(expect.objectContaining({ email }))
    wrapper.unmount()
  })

  it('rejects a 256-character email before availability, verification, or registration', async () => {
    const email = `${'a'.repeat(64)}@${'b'.repeat(63)}.${'c'.repeat(63)}.${'d'.repeat(63)}`
    expect(email).toHaveLength(256)
    const wrapper = mountRegister()
    try {
      await fillValidForm(wrapper, { email })

      await wrapper.get('form').trigger('submit')
      await settle()

      expect(wrapper.get('#auth-register-email').attributes('maxlength')).toBeUndefined()
      expect((wrapper.get('#auth-register-email').element as HTMLInputElement).value).toBe(email)
      expect(wrapper.get('#auth-register-email-error').text()).toBe('邮箱长度不能超过 255 个字符')
      expect(apiMocks.checkUsernameAvailable).not.toHaveBeenCalled()
      expect(verifyMocks.ensureVerified).not.toHaveBeenCalled()
      expect(apiMocks.registerUser).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it.each(invalidLocalFields)('validates local fields before sending an availability request: %j', async overrides => {
    const wrapper = mountRegister()
    try {
      await fillValidForm(wrapper, overrides)
      await wrapper.get('form').trigger('submit')
      await settle()

      expect(apiMocks.checkUsernameAvailable).not.toHaveBeenCalled()
      expect(verifyMocks.ensureVerified).not.toHaveBeenCalled()
      expect(apiMocks.registerUser).not.toHaveBeenCalled()
      expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('rejects an overlong multibyte password before availability, verification, or registration', async () => {
    const wrapper = mountRegister()
    try {
      const password = `Aa1${'汉'.repeat(24)}`
      await fillValidForm(wrapper, { password, confirmPassword: password })
      await wrapper.get('form').trigger('submit')
      await settle()

      expect(wrapper.get('#auth-register-password-error').text()).toBe('密码过长，请减少字符数量')
      expect(apiMocks.checkUsernameAvailable).not.toHaveBeenCalled()
      expect(verifyMocks.ensureVerified).not.toHaveBeenCalled()
      expect(apiMocks.registerUser).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('uses one immutable snapshot for validation, verification, registration, and contacts', async () => {
    const availability = deferred<boolean>()
    const verification = deferred<{ token: string; provider: 'mock-manual'; scene: 'register'; verifiedAt: string }>()
    apiMocks.checkUsernameAvailable.mockReturnValueOnce(availability.promise)
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountRegister()
    const initial = await fillValidForm(wrapper, {
      username: 'snapshot_user',
      phone: '13800000001',
      email: 'snapshot@example.com',
    })

    await wrapper.get('form').trigger('submit')
    availability.resolve(true)
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledTimes(1)

    await wrapper.get('#auth-register-username').setValue('edited_user')
    await wrapper.get('#auth-register-phone').setValue('13900000000')
    await wrapper.get('#auth-register-email').setValue('edited@example.com')
    await wrapper.get('#auth-register-password').setValue('EditedPass2')
    await wrapper.get('#auth-register-confirm-password').setValue('EditedPass2')

    verification.resolve({
      token: 'snapshot-verify-token',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    })
    await settle()

    expect(apiMocks.registerUser).toHaveBeenCalledWith(expect.objectContaining({
      username: initial.username,
      phone: initial.phone,
      email: initial.email,
      password: initial.password,
      verifyToken: 'snapshot-verify-token',
    }))
    expect(wrapper.emitted('registered')).toEqual([[{ phone: initial.phone, email: initial.email }]])
    wrapper.unmount()
  })

  it('does not continue from an availability wait after unmount', async () => {
    const availability = deferred<boolean>()
    apiMocks.checkUsernameAvailable.mockReturnValueOnce(availability.promise)
    const wrapper = mountRegister()
    await fillValidForm(wrapper)

    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    availability.resolve(true)
    await settle()

    expect(verifyMocks.ensureVerified).not.toHaveBeenCalled()
    expect(apiMocks.registerUser).not.toHaveBeenCalled()
    expect(wrapper.emitted('notify')).toBeUndefined()
  })

  it('does not register or notify when verification resolves after unmount', async () => {
    const verification = deferred<{ token: string; provider: 'mock-manual'; scene: 'register'; verifiedAt: string }>()
    verifyMocks.ensureVerified.mockReturnValueOnce(verification.promise)
    const wrapper = mountRegister()
    await fillValidForm(wrapper)

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    verification.resolve({
      token: 'late-verify-token',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    })
    await settle()

    expect(apiMocks.registerUser).not.toHaveBeenCalled()
    expect(wrapper.emitted('notify')).toBeUndefined()
    expect(verifyMocks.resetIfMatches).not.toHaveBeenCalled()
  })

  it('suppresses UI effects after a register request was already sent and the component unmounts', async () => {
    const register = deferred<{ id: number; username: string; phone: string; email: string }>()
    apiMocks.registerUser.mockReturnValueOnce(register.promise)
    const wrapper = mountRegister()
    await fillValidForm(wrapper)

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    register.resolve({ id: 2, username: 'register_user', phone: '13800000000', email: 'register@example.com' })
    await settle()

    expect(wrapper.emitted('notify')).toBeUndefined()
    expect(wrapper.emitted('registered')).toBeUndefined()
    expect(verifyMocks.reset).not.toHaveBeenCalled()
    expect(verifyMocks.resetIfMatches).not.toHaveBeenCalled()
  })

  it('invalidates only the verification proof consumed by a failed request before retrying', async () => {
    const firstProof = {
      token: 'first-proof',
      provider: 'mock-manual' as const,
      scene: 'register' as const,
      verifiedAt: '2026-01-01T00:00:00.000Z',
    }
    const secondProof = {
      token: 'second-proof',
      provider: 'mock-manual' as const,
      scene: 'register' as const,
      verifiedAt: '2026-01-01T00:01:00.000Z',
    }
    const serverError = new Error('verification rejected')
    apiMocks.registerUser
      .mockRejectedValueOnce(serverError)
      .mockResolvedValueOnce({ id: 4, username: 'proof_retry', phone: '13800000003', email: 'proof@example.com' })
    verifyMocks.ensureVerified
      .mockResolvedValueOnce(firstProof)
      .mockResolvedValueOnce(secondProof)
    verifyMocks.resetIfMatches.mockImplementation((expected: typeof firstProof) => expected === firstProof)
    const wrapper = mountRegister()
    await fillValidForm(wrapper, {
      username: 'proof_retry',
      phone: '13800000003',
      email: 'proof@example.com',
    })

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(verifyMocks.resetIfMatches).toHaveBeenCalledWith(firstProof)

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(verifyMocks.ensureVerified).toHaveBeenCalledTimes(2)
    expect(apiMocks.registerUser).toHaveBeenNthCalledWith(1, expect.objectContaining({ verifyToken: 'first-proof' }))
    expect(apiMocks.registerUser).toHaveBeenNthCalledWith(2, expect.objectContaining({ verifyToken: 'second-proof' }))
    wrapper.unmount()
  })

  it('does not reset a newer proof when an older request settles', async () => {
    const firstProof = {
      token: 'old-proof',
      provider: 'mock-manual' as const,
      scene: 'register' as const,
      verifiedAt: '2026-01-01T00:00:00.000Z',
    }
    const newerProof = {
      token: 'new-proof',
      provider: 'mock-manual' as const,
      scene: 'register' as const,
      verifiedAt: '2026-01-01T00:01:00.000Z',
    }
    const register = deferred<{ id: number; username: string; phone: string; email: string }>()
    apiMocks.registerUser.mockReturnValueOnce(register.promise)
    verifyMocks.ensureVerified.mockResolvedValueOnce(firstProof)
    let currentProof: typeof firstProof | typeof newerProof = firstProof
    verifyMocks.resetIfMatches.mockImplementation((expected: typeof firstProof) => {
      if (expected !== currentProof) return false
      currentProof = newerProof
      return true
    })
    const wrapper = mountRegister()
    await fillValidForm(wrapper, {
      username: 'proof_survives',
      phone: '13800000004',
      email: 'survives@example.com',
    })

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(1)
    currentProof = newerProof
    register.resolve({ id: 5, username: 'proof_survives', phone: '13800000004', email: 'survives@example.com' })
    await settle()

    expect(verifyMocks.resetIfMatches).toHaveBeenCalledWith(firstProof)
    expect(currentProof).toBe(newerProof)
    wrapper.unmount()
  })

  it('does not invalidate a proof when verification produces no result and no request is sent', async () => {
    verifyMocks.ensureVerified.mockResolvedValueOnce(null)
    const wrapper = mountRegister()
    await fillValidForm(wrapper, {
      username: 'no_proof',
      phone: '13800000005',
      email: 'no-proof@example.com',
    })

    await wrapper.get('form').trigger('submit')
    await settle()

    expect(apiMocks.registerUser).not.toHaveBeenCalled()
    expect(verifyMocks.resetIfMatches).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('blocks registration when the proof expires before the payload is built', async () => {
    const now = Date.parse('2026-01-01T00:00:00.000Z')
    vi.useFakeTimers()
    vi.setSystemTime(now)
    const proof = {
      token: 'expiring-proof',
      provider: 'mock-manual' as const,
      scene: 'register' as const,
      verifiedAt: '2026-01-01T00:00:00.000Z',
      expiresAt: new Date(now + 100).toISOString(),
    }
    verifyMocks.ensureVerified.mockImplementationOnce(async () => {
      vi.setSystemTime(now + 100)
      return proof
    })
    const wrapper = mountRegister()
    try {
      await fillValidForm(wrapper, {
        username: 'expired_proof',
        phone: '13800000006',
        email: 'expired-proof@example.com',
      })

      await wrapper.get('form').trigger('submit')
      await settle()

      expect(apiMocks.registerUser).not.toHaveBeenCalled()
      expect(verifyMocks.resetIfMatches).toHaveBeenCalledWith(proof)
      expect(wrapper.emitted('notify')).toContainEqual(['验证已过期，请重新完成验证'])
    } finally {
      wrapper.unmount()
    }
  })

  it('preserves user input after a server error and retries with a fresh availability request', async () => {
    const serverError = new Error('邮箱已被使用')
    ;(serverError as Error & { response?: { data?: { message?: string } } }).response = {
      data: { message: '邮箱已被使用' },
    }
    apiMocks.registerUser
      .mockRejectedValueOnce(serverError)
      .mockResolvedValueOnce({ id: 3, username: 'retry_user', phone: '13800000002', email: 'retry@example.com' })
    const wrapper = mountRegister()
    const initial = await fillValidForm(wrapper, {
      username: 'retry_user',
      phone: '13800000002',
      email: 'retry@example.com',
    })

    await wrapper.get('form').trigger('submit')
    await settle()
    expect((wrapper.get('#auth-register-username').element as HTMLInputElement).value).toBe(initial.username)
    expect((wrapper.get('#auth-register-phone').element as HTMLInputElement).value).toBe(initial.phone)
    expect((wrapper.get('#auth-register-email').element as HTMLInputElement).value).toBe(initial.email)
    expect((wrapper.get('#auth-register-password').element as HTMLInputElement).value).toBe(initial.password)
    expect((wrapper.get('#auth-register-confirm-password').element as HTMLInputElement).value).toBe(initial.confirmPassword)
    const notifications = wrapper.emitted('notify') || []
    expect(notifications[notifications.length - 1]).toEqual(['注册失败，该邮箱已被注册'])

    await wrapper.get('form').trigger('submit')
    await settle()
    expect(apiMocks.checkUsernameAvailable).toHaveBeenCalledTimes(2)
    expect(apiMocks.registerUser).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})

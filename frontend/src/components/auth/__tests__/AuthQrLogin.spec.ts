import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import type { LoginResponse, QrLoginSession, QrLoginStatusResponse, User } from '@/types'

const apiMocks = vi.hoisted(() => ({
  cancelQrLoginSession: vi.fn(),
  confirmQrLoginSession: vi.fn(),
  createQrLoginSession: vi.fn(),
  exchangeQrLoginSession: vi.fn(),
  getQrLoginStatus: vi.fn(),
}))

vi.mock('@/api/auth', () => apiMocks)
vi.mock('@/api/users', () => ({ getCurrentUser: vi.fn() }))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

import AuthQrLogin from '../AuthQrLogin.vue'
import { useAuthStore } from '@/stores/auth'

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

async function flushPromises(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

function makeSession(qrId: string, pollToken: string): QrLoginSession {
  return {
    qrId,
    pollToken,
    qrUrl: `magicalbum://auth/qr/${qrId}`,
    expiresAt: '2099-01-01T00:00:00Z',
    status: 'PENDING',
  }
}

function makeStatus(session: QrLoginSession, status: QrLoginSession['status']): QrLoginStatusResponse {
  return { ...session, status, user: null }
}

function makeLogin(accessToken: string, user: User): LoginResponse {
  return { accessToken, user }
}

function mountQr() {
  const pinia = createPinia()
  setActivePinia(pinia)
  return mount(AuthQrLogin, {
    global: {
      plugins: [pinia],
      stubs: { AuthQrCode: true },
    },
  })
}

describe('AuthQrLogin lifecycle races', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    localStorage.clear()
    apiMocks.cancelQrLoginSession.mockResolvedValue(undefined)
    apiMocks.confirmQrLoginSession.mockResolvedValue(undefined)
    apiMocks.exchangeQrLoginSession.mockResolvedValue(makeLogin('token-qr', { id: 8, username: 'qr-user' }))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('cancels a session created after the component was unmounted', async () => {
    const create = deferred<QrLoginSession>()
    const first = makeSession('qr-late-create', 'poll-late-create')
    apiMocks.createQrLoginSession.mockReturnValueOnce(create.promise)

    const wrapper = mountQr()
    await flushPromises()
    wrapper.unmount()

    create.resolve(first)
    await flushPromises()

    expect(apiMocks.cancelQrLoginSession).toHaveBeenCalledWith(first.qrId, first.pollToken)
    expect(apiMocks.exchangeQrLoginSession).not.toHaveBeenCalled()
  })

  it('ignores a late poll response after unmount', async () => {
    const first = makeSession('qr-late-poll', 'poll-late-poll')
    const poll = deferred<QrLoginStatusResponse>()
    apiMocks.createQrLoginSession.mockResolvedValueOnce(first)
    apiMocks.getQrLoginStatus.mockReturnValueOnce(poll.promise)

    const wrapper = mountQr()
    await flushPromises()
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(apiMocks.getQrLoginStatus).toHaveBeenCalledWith(first.qrId, first.pollToken)

    wrapper.unmount()
    poll.resolve(makeStatus(first, 'CONFIRMED'))
    await flushPromises()

    expect(apiMocks.exchangeQrLoginSession).not.toHaveBeenCalled()
    expect(useAuthStore().isLoggedIn).toBe(false)
    expect(apiMocks.cancelQrLoginSession).toHaveBeenCalledWith(first.qrId, first.pollToken)
  })

  it('ignores a late exchange response after unmount', async () => {
    const first = makeSession('qr-late-exchange', 'poll-late-exchange')
    const exchange = deferred<LoginResponse>()
    apiMocks.createQrLoginSession.mockResolvedValueOnce(first)
    apiMocks.getQrLoginStatus.mockResolvedValueOnce(makeStatus(first, 'CONFIRMED'))
    apiMocks.exchangeQrLoginSession.mockReturnValueOnce(exchange.promise)

    const wrapper = mountQr()
    await flushPromises()
    vi.advanceTimersByTime(2000)
    await flushPromises()
    expect(apiMocks.exchangeQrLoginSession).toHaveBeenCalledWith(first.qrId, first.pollToken)

    wrapper.unmount()
    exchange.resolve(makeLogin('token-late-qr', { id: 9, username: 'late-qr' }))
    await flushPromises()

    expect(useAuthStore().isLoggedIn).toBe(false)
    expect(wrapper.emitted('success')).toBeUndefined()
    expect(apiMocks.cancelQrLoginSession).toHaveBeenCalledWith(first.qrId, first.pollToken)
  })

  it('keeps an old poll from exchanging after refresh and preserves its identifiers', async () => {
    const first = makeSession('qr-old', 'poll-old')
    const second = makeSession('qr-new', 'poll-new')
    const poll = deferred<QrLoginStatusResponse>()
    apiMocks.createQrLoginSession
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce(second)
    apiMocks.getQrLoginStatus.mockReturnValueOnce(poll.promise)

    const wrapper = mountQr()
    await flushPromises()
    vi.advanceTimersByTime(2000)
    await flushPromises()

    await wrapper.find('button[aria-label="刷新二维码"]').trigger('click')
    await flushPromises()
    expect(apiMocks.cancelQrLoginSession).toHaveBeenCalledWith(first.qrId, first.pollToken)
    expect(apiMocks.createQrLoginSession).toHaveBeenCalledTimes(2)

    poll.resolve(makeStatus(first, 'CONFIRMED'))
    await flushPromises()

    expect(apiMocks.getQrLoginStatus).toHaveBeenCalledWith(first.qrId, first.pollToken)
    expect(apiMocks.exchangeQrLoginSession).not.toHaveBeenCalled()
    expect(useAuthStore().isLoggedIn).toBe(false)
  })

  it('cancels a session created after another login completes', async () => {
    const create = deferred<QrLoginSession>()
    const first = makeSession('qr-login-during-create', 'poll-create')
    apiMocks.createQrLoginSession.mockReturnValueOnce(create.promise)
    const wrapper = mountQr()
    const auth = useAuthStore()
    auth.loginWithAuthResponse(makeLogin('token-password', { id: 10, username: 'password-user' }))

    create.resolve(first)
    await flushPromises()
    vi.advanceTimersByTime(4000)
    await flushPromises()

    expect(apiMocks.cancelQrLoginSession).toHaveBeenCalledWith(first.qrId, first.pollToken)
    expect(apiMocks.getQrLoginStatus).not.toHaveBeenCalled()
    expect(auth.token).toBe('token-password')
    expect(wrapper.emitted('success')).toBeUndefined()
    wrapper.unmount()
  })

  it.each(['login', 'pending attempt'] as const)('ignores an old poll after another %s changes the auth generation', async (change) => {
    const first = makeSession('qr-login-during-poll', 'poll-login')
    const poll = deferred<QrLoginStatusResponse>()
    apiMocks.createQrLoginSession.mockResolvedValueOnce(first)
    apiMocks.getQrLoginStatus.mockReturnValueOnce(poll.promise)
    const wrapper = mountQr()
    await flushPromises()
    vi.advanceTimersByTime(2000)
    await flushPromises()

    const auth = useAuthStore()
    if (change === 'login') {
      auth.loginWithAuthResponse(makeLogin('token-password', { id: 10, username: 'password-user' }))
    } else {
      auth.invalidateAuthOperations()
    }
    poll.resolve(makeStatus(first, 'CONFIRMED'))
    await flushPromises()
    vi.advanceTimersByTime(4000)
    await flushPromises()

    expect(apiMocks.exchangeQrLoginSession).not.toHaveBeenCalled()
    expect(apiMocks.getQrLoginStatus).toHaveBeenCalledTimes(1)
    expect(auth.token).toBe(change === 'login' ? 'token-password' : '')
    expect(wrapper.emitted('success')).toBeUndefined()
    wrapper.unmount()
  })

  it('does not let a password login that starts during exchange get overwritten', async () => {
    const first = makeSession('qr-password-race', 'poll-password-race')
    const exchange = deferred<LoginResponse>()
    apiMocks.createQrLoginSession.mockResolvedValueOnce(first)
    apiMocks.getQrLoginStatus.mockResolvedValueOnce(makeStatus(first, 'CONFIRMED'))
    apiMocks.exchangeQrLoginSession.mockReturnValueOnce(exchange.promise)

    const wrapper = mountQr()
    await flushPromises()
    vi.advanceTimersByTime(2000)
    await flushPromises()

    const auth = useAuthStore()
    auth.loginWithAuthResponse(makeLogin('token-password', { id: 10, username: 'password-user' }))
    exchange.resolve(makeLogin('token-qr', { id: 11, username: 'qr-user' }))
    await flushPromises()

    expect(auth.token).toBe('token-password')
    expect(wrapper.emitted('success')).toBeUndefined()
    wrapper.unmount()
  })

  it('commits a confirmed QR login when the auth generation is unchanged', async () => {
    const first = makeSession('qr-success', 'poll-success')
    apiMocks.createQrLoginSession.mockResolvedValueOnce(first)
    apiMocks.getQrLoginStatus.mockResolvedValueOnce(makeStatus(first, 'CONFIRMED'))
    const wrapper = mountQr()
    await flushPromises()
    vi.advanceTimersByTime(2000)
    await flushPromises()

    expect(useAuthStore().token).toBe('token-qr')
    expect(wrapper.emitted('success')).toHaveLength(1)
    vi.advanceTimersByTime(4000)
    expect(apiMocks.getQrLoginStatus).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

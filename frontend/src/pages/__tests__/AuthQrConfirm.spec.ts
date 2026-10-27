import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { QrLoginStatusResponse, User } from '@/types'

const apiMocks = vi.hoisted(() => ({
  scanQrLoginSession: vi.fn(),
  confirmQrLoginSession: vi.fn(),
}))

const mountedWrappers = new Set<VueWrapper>()

vi.mock('@/api/auth', () => apiMocks)

import AuthQrConfirm from '../AuthQrConfirm.vue'
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

function statusResponse(
  qrId: string,
  status: QrLoginStatusResponse['status'],
  options: Partial<QrLoginStatusResponse> = {},
): QrLoginStatusResponse {
  return {
    qrId,
    pollToken: '',
    qrUrl: `magicalbum://auth/qr/${qrId}`,
    expiresAt: '2099-01-01T00:00:00.000Z',
    status,
    ...options,
  }
}

async function mountPage(options: {
  qrId?: string
  token?: string
  user?: User | null
} = {}): Promise<{
  auth: ReturnType<typeof useAuthStore>
  router: Router
  wrapper: VueWrapper
}> {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.clearAuthState()
  if (options.token) {
    auth.applyAuthState({
      accessToken: options.token,
      user: options.user === undefined ? { id: 1, username: 'alice' } : options.user,
    })
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/auth/qr/confirm', component: AuthQrConfirm }],
  })
  await router.push({ path: '/auth/qr/confirm', query: { qrId: options.qrId ?? 'qr-a' } })
  await router.isReady()
  const wrapper = mount(AuthQrConfirm, { global: { plugins: [pinia, router] } })
  mountedWrappers.add(wrapper)
  await flushPromises()
  return { auth, router, wrapper }
}

async function settle(): Promise<void> {
  await flushPromises()
  await vi.runOnlyPendingTimersAsync()
  await flushPromises()
}

describe('AuthQrConfirm lifecycle ownership', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    apiMocks.scanQrLoginSession.mockReset()
    apiMocks.confirmQrLoginSession.mockReset()
    localStorage.clear()
    apiMocks.scanQrLoginSession.mockResolvedValue(statusResponse('qr-a', 'SCANNED', { message: '扫码完成' }))
    apiMocks.confirmQrLoginSession.mockResolvedValue(statusResponse('qr-a', 'CONFIRMED', { message: '服务端确认成功' }))
  })

  afterEach(() => {
    for (const wrapper of mountedWrappers) wrapper.unmount()
    mountedWrappers.clear()
    vi.useRealTimers()
    localStorage.clear()
  })

  it('deduplicates each action and never runs scan and confirm concurrently', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    const confirm = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    apiMocks.confirmQrLoginSession.mockReturnValueOnce(confirm.promise)
    const { wrapper } = await mountPage({ token: 'token-a' })

    const scanButton = wrapper.findAll('button').find((button) => button.text().includes('扫码'))!
    await Promise.all([scanButton.trigger('click'), scanButton.trigger('click')])
    expect(apiMocks.scanQrLoginSession).toHaveBeenCalledTimes(1)

    scan.resolve(statusResponse('qr-a', 'SCANNED'))
    await settle()
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认登录'))!
    await Promise.all([confirmButton.trigger('click'), confirmButton.trigger('click')])
    expect(apiMocks.confirmQrLoginSession).toHaveBeenCalledTimes(1)
    await scanButton.trigger('click')
    expect(apiMocks.scanQrLoginSession).toHaveBeenCalledTimes(1)

    confirm.resolve(statusResponse('qr-a', 'CONFIRMED', { message: '服务端确认成功' }))
    await settle()
    expect(wrapper.text()).toContain('服务端确认成功')
    wrapper.unmount()
  })

  it('binds a pending response to its qrId and ignores it after a route switch', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const { router, wrapper } = await mountPage({ token: 'token-a' })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    await router.push({ path: '/auth/qr/confirm', query: { qrId: 'qr-b' } })
    await settle()

    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: '旧会话已扫码' }))
    await settle()
    expect(wrapper.text()).toContain('qr-b')
    expect(wrapper.text()).toContain('待确认')
    expect(wrapper.text()).not.toContain('旧会话已扫码')

    apiMocks.scanQrLoginSession.mockResolvedValueOnce(statusResponse('qr-b', 'SCANNED'))
    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    expect(apiMocks.scanQrLoginSession).toHaveBeenLastCalledWith('qr-b')
    wrapper.unmount()
  })

  it('does not send requests for a guest or when memory and persisted tokens drift', async () => {
    const guest = await mountPage()
    const guestButtons = guest.wrapper.findAll('button')
    expect(guestButtons.some((button) => button.text().includes('扫码'))).toBe(false)
    guest.wrapper.unmount()

    const owned = await mountPage({ token: 'token-a' })
    localStorage.setItem('accessToken', 'token-b')
    await owned.wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    expect(apiMocks.scanQrLoginSession).not.toHaveBeenCalled()
    expect(owned.wrapper.text()).toContain('账号状态已变化，请刷新页面后重试')

    localStorage.setItem('accessToken', 'token-a')
    owned.auth.token = 'token-c'
    await owned.wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    expect(apiMocks.scanQrLoginSession).not.toHaveBeenCalled()
    owned.wrapper.unmount()
  })

  it('resets on logout/account replacement and ignores the old response', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const { auth, wrapper } = await mountPage({ token: 'token-a' })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    auth.logout()
    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: '旧账号扫码' }))
    await settle()
    expect(wrapper.text()).not.toContain('旧账号扫码')
    expect(wrapper.text()).toContain('请先登录移动端账号')

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await settle()
    expect(wrapper.text()).toContain('待确认')
    expect(wrapper.text()).not.toContain('旧账号扫码')
    wrapper.unmount()
  })

  it('invalidates on an owner change and ABA even when the token is unchanged', async () => {
    const first = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(first.promise)
    const { auth, wrapper } = await mountPage({ token: 'token-a', user: { id: 1, username: 'alice' } })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    auth.user = { id: 2, username: 'bob' }
    await settle()
    auth.user = { id: 1, username: 'alice' }
    await settle()
    first.resolve(statusResponse('qr-a', 'SCANNED', { message: 'ABA旧响应' }))
    await settle()

    expect(wrapper.text()).not.toContain('ABA旧响应')
    expect(wrapper.text()).toContain('待确认')
    wrapper.unmount()
  })

  it('catches a same-tick owner ABA instead of letting the old response commit', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const { auth, wrapper } = await mountPage({ token: 'token-a', user: { id: 1, username: 'alice' } })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    auth.user = { id: 2, username: 'bob' }
    auth.user = { id: 1, username: 'alice' }
    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: '同 tick owner ABA 旧响应' }))
    await settle()

    expect(wrapper.text()).not.toContain('同 tick owner ABA 旧响应')
    expect(wrapper.text()).toContain('待确认')
    wrapper.unmount()
  })

  it('catches a same-tick token ABA instead of letting the old response commit', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const { auth, wrapper } = await mountPage({ token: 'token-a' })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    auth.token = 'token-b'
    auth.token = 'token-a'
    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: '同 tick token ABA 旧响应' }))
    await settle()

    expect(wrapper.text()).not.toContain('同 tick token ABA 旧响应')
    expect(wrapper.text()).toContain('待确认')
    wrapper.unmount()
  })

  it('keeps a same-token operation alive while unknown identity hydrates', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const { auth, wrapper } = await mountPage({ token: 'token-a', user: null })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')
    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: '同令牌扫码完成' }))
    await settle()

    expect(wrapper.text()).toContain('已扫码')
    expect(wrapper.text()).toContain('同令牌扫码完成')
    wrapper.unmount()
  })

  it('treats username-only identity as unknown until a valid id hydrates', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const { auth, wrapper } = await mountPage({ token: 'token-a', user: { username: 'alice' } as User })

    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')
    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: 'username hydrate 扫码完成' }))
    await settle()

    expect(wrapper.text()).toContain('已扫码')
    expect(wrapper.text()).toContain('username hydrate 扫码完成')
    wrapper.unmount()
  })

  it('expires pending and scanned sessions locally without changing confirmed sessions', async () => {
    vi.setSystemTime(new Date('2026-01-01T00:00:00.000Z'))
    apiMocks.scanQrLoginSession.mockResolvedValueOnce(statusResponse('qr-a', 'SCANNED', {
      expiresAt: '2026-01-01T00:00:02.000Z',
    }))
    const first = await mountPage({ token: 'token-a' })
    await first.wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    await settle()
    vi.advanceTimersByTime(2_100)
    await settle()
    expect(first.wrapper.text()).toContain('已过期')
    expect(first.wrapper.findAll('button').find((button) => button.text().includes('确认登录'))!.attributes('disabled')).toBeDefined()
    first.wrapper.unmount()

    apiMocks.scanQrLoginSession.mockResolvedValueOnce(statusResponse('qr-a', 'CONFIRMED', {
      expiresAt: '2026-01-01T00:00:01.000Z',
    }))
    const confirmed = await mountPage({ token: 'token-a' })
    await confirmed.wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    await settle()
    vi.advanceTimersByTime(5_000)
    await settle()
    expect(confirmed.wrapper.text()).toContain('已确认')
    expect(confirmed.wrapper.text()).not.toContain('已过期')
    confirmed.wrapper.unmount()
  })

  it('does not render NaN for an invalid expiry and keeps server status/message', async () => {
    apiMocks.scanQrLoginSession.mockResolvedValueOnce(statusResponse('qr-a', 'CANCELLED', {
      expiresAt: 'not-a-date',
      message: '服务端已取消',
    }))
    const { wrapper } = await mountPage({ token: 'token-a' })
    await wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    await settle()

    expect(wrapper.text()).toContain('已取消')
    expect(wrapper.text()).toContain('服务端已取消')
    expect(wrapper.text()).not.toContain('NaN')
    expect(wrapper.text()).not.toContain('已确认')
    wrapper.unmount()
  })

  it('preserves retryable state after failures and retries the same backend operation', async () => {
    apiMocks.scanQrLoginSession
      .mockRejectedValueOnce(new Error('temporary scan failure'))
      .mockResolvedValueOnce(statusResponse('qr-a', 'SCANNED', { message: '扫码成功' }))
    apiMocks.confirmQrLoginSession
      .mockRejectedValueOnce(new Error('temporary confirm failure'))
      .mockResolvedValueOnce(statusResponse('qr-a', 'PENDING', { message: '服务端仍待确认' }))
    const { wrapper } = await mountPage({ token: 'token-a' })
    const scanButton = wrapper.findAll('button').find((button) => button.text().includes('扫码'))!
    await scanButton.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('扫码失败，请稍后再试')

    await scanButton.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('已扫码')
    const confirmButton = wrapper.findAll('button').find((button) => button.text().includes('确认登录'))!
    await confirmButton.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('确认失败，请稍后再试')
    expect(wrapper.text()).toContain('已扫码')

    await confirmButton.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('待确认')
    expect(wrapper.text()).toContain('服务端仍待确认')
    expect(wrapper.text()).not.toContain('已确认')
    wrapper.unmount()
  })

  it('stops late callbacks after unmount and after an explicit auth-state boundary', async () => {
    const scan = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(scan.promise)
    const mounted = await mountPage({ token: 'token-a' })
    await mounted.wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    mounted.wrapper.unmount()
    scan.resolve(statusResponse('qr-a', 'SCANNED', { message: '卸载后的旧响应' }))
    await settle()
    expect(apiMocks.scanQrLoginSession).toHaveBeenCalledTimes(1)

    const next = deferred<QrLoginStatusResponse>()
    apiMocks.scanQrLoginSession.mockReturnValueOnce(next.promise)
    const boundary = await mountPage({ token: 'token-a' })
    await boundary.wrapper.findAll('button').find((button) => button.text().includes('扫码'))!.trigger('click')
    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: false } }))
    next.resolve(statusResponse('qr-a', 'SCANNED', { message: '边界后的旧响应' }))
    await settle()
    expect(boundary.wrapper.text()).not.toContain('边界后的旧响应')
    boundary.wrapper.unmount()
  })
})

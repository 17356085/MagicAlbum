import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoginResponse } from '@/types'
import AuthOAuthCallback from '../AuthOAuthCallback.vue'
import { useAuthStore } from '@/stores/auth'
import { storeOAuthBrowserProof } from '@/utils/oauthBrowserProof'

const mocks = vi.hoisted(() => ({
  exchange: vi.fn(),
  replace: vi.fn(),
  route: { query: { provider: 'google', code: 'one-use-code' } },
}))
vi.mock('@/api/auth', () => ({ exchangeOAuthCode: mocks.exchange }))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
}))

function deferred() {
  let resolve!: (value: LoginResponse) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<LoginResponse>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}

function httpError(status: number): Error & { response: { status: number } } {
  return Object.assign(new Error(`HTTP ${status}`), { response: { status } })
}

describe('OAuth callback lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    mocks.route.query = { provider: 'google', code: 'one-use-code' }
    storeOAuthBrowserProof({ provider: 'google', verifier: 'v'.repeat(43), expiresAt: Date.now() + 300_000 })
    setActivePinia(createPinia())
  })
  afterEach(() => vi.useRealTimers())

  it.each(['logout', 'new-login', 'unmount'])('ignores an exchange superseded by %s', async (action) => {
    const request = deferred()
    mocks.exchange.mockReturnValueOnce(request.promise)
    const wrapper = mount(AuthOAuthCallback)
    const auth = useAuthStore()
    if (action === 'logout') auth.logout()
    if (action === 'new-login') auth.loginWithAuthResponse({ accessToken: 'new-token', user: { id: 2, username: 'new-user' } })
    if (action === 'unmount') wrapper.unmount()
    request.resolve({ accessToken: 'old-oauth-token', user: { id: 1, username: 'old-user' } })
    await flushPromises()
    expect(auth.token).toBe(action === 'new-login' ? 'new-token' : '')
    expect(mocks.replace).not.toHaveBeenCalled()
    if (action !== 'unmount') wrapper.unmount()
  })

  it('commits a current exchange and clears deferred navigation on unmount', async () => {
    vi.useFakeTimers()
    mocks.exchange.mockResolvedValueOnce({ accessToken: 'oauth-token', user: { id: 1 } })
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()
    expect(useAuthStore().token).toBe('oauth-token')
    expect(mocks.exchange).toHaveBeenCalledWith('one-use-code', 'v'.repeat(43))
    expect(wrapper.text()).toContain('登录成功')
    wrapper.unmount()
    vi.advanceTimersByTime(500)
    expect(mocks.replace).not.toHaveBeenCalled()
  })

  it('announces processing without showing a failure while the exchange is pending', async () => {
    const request = deferred()
    mocks.exchange.mockReturnValueOnce(request.promise)
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()
    expect(wrapper.get('h1').text()).toContain('正在登录')
    expect(wrapper.text()).not.toContain('第三方登录未完成')
    request.resolve({ accessToken: 'oauth-token', user: { id: 1, username: 'alice' } })
    await flushPromises()
    expect(wrapper.text()).toContain('登录成功')
    expect(wrapper.text()).not.toContain('收口')
    wrapper.unmount()
  })

  it('does not announce or navigate when an auth event replaces the just-committed session', async () => {
    vi.useFakeTimers()
    mocks.exchange.mockResolvedValueOnce({ accessToken: 'oauth-token', user: { id: 1 } })
    const auth = useAuthStore()
    const replaceSession = () => {
      window.removeEventListener('auth-state-changed', replaceSession)
      auth.applyAuthState({ accessToken: 'reentrant-token', user: { id: 2, username: 'bob' } })
    }
    window.addEventListener('auth-state-changed', replaceSession)
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()
    vi.advanceTimersByTime(500)
    expect(auth.token).toBe('reentrant-token')
    expect(wrapper.text()).not.toContain('登录成功')
    expect(mocks.replace).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the browser proof after a retryable exchange failure and supports manual retry', async () => {
    vi.useFakeTimers()
    mocks.exchange
      .mockRejectedValueOnce(new Error('network unavailable'))
      .mockResolvedValueOnce({ accessToken: 'oauth-token', user: { id: 1 } })
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"verifier":"' + 'v'.repeat(43))
    const retry = wrapper.get('[data-test="oauth-retry"]')
    await retry.trigger('click')
    await flushPromises()

    expect(mocks.exchange).toHaveBeenCalledTimes(2)
    expect(useAuthStore().token).toBe('oauth-token')
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
    wrapper.unmount()
  })

  it('stops before exchanging when the persisted owner changes during URL cleanup', async () => {
    const historySpy = vi.spyOn(window.history, 'replaceState')
    historySpy.mockImplementation(() => {
      localStorage.setItem('accessToken', 'storage-owner')
    })

    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    expect(mocks.exchange).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="oauth-retry"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录状态已更新')
    historySpy.mockRestore()
    wrapper.unmount()
  })

  it('stops a pending exchange when the persisted owner changes before its reply', async () => {
    const request = deferred()
    mocks.exchange.mockReturnValueOnce(request.promise)
    const wrapper = mount(AuthOAuthCallback)
    const auth = useAuthStore()
    await flushPromises()
    localStorage.setItem('accessToken', 'storage-owner')
    request.resolve({ accessToken: 'old-oauth-token', user: { id: 1, username: 'old-user' } })
    await flushPromises()

    expect(auth.token).toBe('')
    expect(mocks.exchange).toHaveBeenCalledTimes(1)
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"verifier":"' + 'v'.repeat(43))
    expect(wrapper.find('[data-test="oauth-retry"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录状态已更新')
    wrapper.unmount()
  })

  it('does not retry after a persisted owner change invalidates a retryable exchange', async () => {
    mocks.exchange.mockRejectedValueOnce(new Error('network unavailable'))
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    expect(wrapper.find('[data-test="oauth-retry"]').exists()).toBe(true)
    localStorage.setItem('accessToken', 'storage-owner')
    await wrapper.get('[data-test="oauth-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.exchange).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="oauth-retry"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录状态已更新')
    wrapper.unmount()
  })

  it('does not navigate from a committed callback after the persisted owner changes', async () => {
    vi.useFakeTimers()
    mocks.exchange.mockResolvedValueOnce({ accessToken: 'oauth-token', user: { id: 1 } })
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    localStorage.setItem('accessToken', 'storage-owner')
    vi.advanceTimersByTime(500)
    await flushPromises()

    expect(mocks.replace).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('登录状态已更新')
    wrapper.unmount()
  })

  it('does not show retry for a consumed code and keeps the proof for the real callback', async () => {
    mocks.exchange.mockRejectedValueOnce(httpError(400))
    const stale = mount(AuthOAuthCallback)
    await flushPromises()

    expect(stale.find('[data-test="oauth-retry"]').exists()).toBe(false)
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"provider":"google"')
    stale.unmount()

    mocks.route.query = { provider: 'google', code: 'real-code' }
    mocks.exchange.mockResolvedValueOnce({ accessToken: 'real-token', user: { id: 2 } })
    const current = mount(AuthOAuthCallback)
    await flushPromises()

    expect(mocks.exchange).toHaveBeenLastCalledWith('real-code', 'v'.repeat(43))
    expect(useAuthStore().token).toBe('real-token')
    current.unmount()
  })

  it('ignores an old callback success after a new login and preserves the newer proof', async () => {
    const oldExchange = deferred()
    mocks.exchange.mockReturnValueOnce(oldExchange.promise)
    const wrapper = mount(AuthOAuthCallback)
    const auth = useAuthStore()

    auth.loginWithAuthResponse({ accessToken: 'new-token', user: { id: 2, username: 'new-user' } })
    storeOAuthBrowserProof({ provider: 'google', verifier: 'n'.repeat(43), expiresAt: Date.now() + 300_000 })
    oldExchange.resolve({ accessToken: 'old-token', user: { id: 1, username: 'old-user' } })
    await flushPromises()

    expect(auth.token).toBe('new-token')
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"verifier":"' + 'n'.repeat(43))
    expect(wrapper.text()).not.toContain('登录成功')
    wrapper.unmount()
  })

  it('shares one generation when a stale callback fails before a concurrent real callback succeeds', async () => {
    const fakeExchange = deferred()
    const realExchange = deferred()
    mocks.route.query = { provider: 'google', code: 'fake-code' }
    mocks.exchange
      .mockReturnValueOnce(fakeExchange.promise)
      .mockReturnValueOnce(realExchange.promise)

    const auth = useAuthStore()
    const stale = mount(AuthOAuthCallback)
    await flushPromises()
    const sharedGeneration = auth.getAuthGeneration()

    expect(mocks.exchange).toHaveBeenNthCalledWith(1, 'fake-code', 'v'.repeat(43))
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"verifier":"' + 'v'.repeat(43))

    mocks.route.query = { provider: 'google', code: 'real-code' }
    const current = mount(AuthOAuthCallback)
    await flushPromises()

    expect(auth.getAuthGeneration()).toBe(sharedGeneration)
    expect(mocks.exchange).toHaveBeenCalledTimes(2)
    expect(mocks.exchange).toHaveBeenNthCalledWith(2, 'real-code', 'v'.repeat(43))
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"provider":"google"')

    fakeExchange.reject(httpError(400))
    await flushPromises()

    expect(stale.find('[data-test="oauth-retry"]').exists()).toBe(false)
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"verifier":"' + 'v'.repeat(43))

    realExchange.resolve({ accessToken: 'real-token', user: { id: 2, username: 'real-user' } })
    await flushPromises()

    expect(auth.token).toBe('real-token')
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
    stale.unmount()
    current.unmount()
  })

  it('ignores an old success after proof replacement without auth invalidation', async () => {
    const oldExchange = deferred()
    mocks.exchange.mockReturnValueOnce(oldExchange.promise)
    const wrapper = mount(AuthOAuthCallback)
    const auth = useAuthStore()
    await flushPromises()

    const newProof = {
      provider: 'google' as const,
      verifier: 'n'.repeat(43),
      expiresAt: Date.now() + 300_000,
    }
    storeOAuthBrowserProof(newProof)
    oldExchange.resolve({ accessToken: 'old-token', user: { id: 1, username: 'old-user' } })
    await flushPromises()

    expect(auth.token).toBe('')
    expect(sessionStorage.getItem('oauthBrowserProof')).toBe(JSON.stringify(newProof))
    expect(wrapper.text()).not.toContain('登录成功')
    expect(mocks.replace).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('clears the callback URL before exchanging while retaining the in-memory code and verifier', async () => {
    const request = deferred()
    const callOrder: string[] = []
    const historySpy = vi.spyOn(window.history, 'replaceState')
    historySpy.mockImplementation((_state, _title, url) => {
      callOrder.push('history')
      expect(String(url)).not.toContain('url-cleanup-code')
      expect(String(url)).not.toContain('code=')
    })
    mocks.route.query = { provider: 'google', code: 'url-cleanup-code' }
    mocks.exchange.mockImplementationOnce((code: string, verifier: string) => {
      callOrder.push('exchange')
      expect(code).toBe('url-cleanup-code')
      expect(verifier).toBe('v'.repeat(43))
      return request.promise
    })

    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    expect(callOrder).toEqual(['history', 'exchange'])
    expect(historySpy).toHaveBeenCalledTimes(1)
    expect(String(historySpy.mock.calls[0][2])).not.toContain('code=')

    request.resolve({ accessToken: 'oauth-token', user: { id: 1, username: 'oauth-user' } })
    await flushPromises()
    wrapper.unmount()
    historySpy.mockRestore()
  })

  it('does not exchange a callback from a foreign provider or change the current login', async () => {
    const auth = useAuthStore()
    auth.loginWithAuthResponse({ accessToken: 'current-token', user: { id: 2, username: 'current-user' } })
    storeOAuthBrowserProof({ provider: 'google', verifier: 'v'.repeat(43), expiresAt: Date.now() + 300_000 })
    const generation = auth.getAuthGeneration()
    mocks.route.query = { provider: 'apple', code: 'foreign-code' }

    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    expect(mocks.exchange).not.toHaveBeenCalled()
    expect(auth.token).toBe('current-token')
    expect(auth.getAuthGeneration()).toBe(generation)
    expect(sessionStorage.getItem('oauthBrowserProof')).toContain('"provider":"google"')
    wrapper.unmount()
  })

  it('rejects an expired browser proof without invalidating the current auth operation', async () => {
    const auth = useAuthStore()
    auth.loginWithAuthResponse({ accessToken: 'current-token', user: { id: 2, username: 'current-user' } })
    const generation = auth.getAuthGeneration()
    storeOAuthBrowserProof({ provider: 'google', verifier: 'v'.repeat(43), expiresAt: Date.now() - 1 })

    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()

    expect(mocks.exchange).not.toHaveBeenCalled()
    expect(auth.token).toBe('current-token')
    expect(auth.getAuthGeneration()).toBe(generation)
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
    wrapper.unmount()
  })

  it('rejects an unsolicited same-provider callback without exchanging or replacing an existing session', async () => {
    sessionStorage.clear()
    const auth = useAuthStore()
    auth.loginWithAuthResponse({ accessToken: 'current-token', user: { id: 2, username: 'current-user' } })
    const generation = auth.getAuthGeneration()
    const wrapper = mount(AuthOAuthCallback)
    await flushPromises()
    expect(mocks.exchange).not.toHaveBeenCalled()
    expect(auth.token).toBe('current-token')
    expect(auth.getAuthGeneration()).toBe(generation)
    wrapper.unmount()
  })
})

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
  const promise = new Promise<LoginResponse>((done) => { resolve = done })
  return { promise, resolve }
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

  it('does not exchange a callback from a foreign provider or change the current login', async () => {
    const auth = useAuthStore()
    auth.loginWithAuthResponse({ accessToken: 'current-token', user: { id: 2, username: 'current-user' } })
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

import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { LoginResponse } from '@/types'
import AuthOAuthCallback from '../AuthOAuthCallback.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({ exchange: vi.fn(), replace: vi.fn() }))
vi.mock('@/api/auth', () => ({ exchangeOAuthCode: mocks.exchange }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { code: 'one-use-code' } }),
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
    expect(wrapper.text()).toContain('登录成功')
    wrapper.unmount()
    vi.advanceTimersByTime(500)
    expect(mocks.replace).not.toHaveBeenCalled()
  })
})

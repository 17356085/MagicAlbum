import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsConnectedTab from '../settings/SettingsConnectedTab.vue'
import { listConnectedAccounts } from '@/api/connected'
import { useAuthStore } from '@/stores/auth'
import type { ConnectedAccount } from '@/types'

const mocks = vi.hoisted(() => ({
  listConnectedAccounts: vi.fn(),
}))

vi.mock('@/api/connected', () => ({
  listConnectedAccounts: mocks.listConnectedAccounts,
}))

const listConnectedAccountsMock = vi.mocked(listConnectedAccounts)

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

const accountA: ConnectedAccount = { provider: 'github', connected: true }
const accountB: ConnectedAccount = { provider: 'google', connected: true }

const wrappers: Array<{ unmount: () => void }> = []

function createWrapper() {
  const wrapper = mount(SettingsConnectedTab)
  wrappers.push(wrapper)
  return wrapper
}

async function runPendingReloads(): Promise<void> {
  await vi.runOnlyPendingTimersAsync()
  await flushPromises()
}

describe('SettingsConnectedTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    listConnectedAccountsMock.mockResolvedValue({ items: [accountA] })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.useRealTimers()
  })

  it('loads confirmed account state without exposing connect or disconnect operations', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('GitHub')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('does not start a request when storage belongs to another account before an auth event', async () => {
    localStorage.setItem('accessToken', 'token-b')
    const wrapper = createWrapper()
    await flushPromises()

    expect(listConnectedAccountsMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
    expect(wrapper.text()).not.toContain('暂无已关联账号')
  })

  it('does not claim an empty account list when storage changes before a pending response returns', async () => {
    const response = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReturnValueOnce(response.promise)
    const wrapper = createWrapper()
    localStorage.setItem('accessToken', 'token-b')
    response.resolve({ items: [accountA] })
    await flushPromises()

    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
    expect(wrapper.text()).not.toContain('暂无已关联账号')
    expect(wrapper.text()).not.toContain('正在加载关联状态')
  })

  it('shows that login is required after logout instead of reporting an empty linked-account list', async () => {
    vi.useFakeTimers()
    const wrapper = createWrapper()
    await flushPromises()
    useAuthStore().logout()
    await runPendingReloads()

    expect(wrapper.get('[role="alert"]').text()).toContain('请登录后查看关联状态')
    expect(wrapper.text()).not.toContain('暂无已关联账号')
    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
  })

  it('shows a retryable failure, blocks duplicate retry while loading, and accepts one retry', async () => {
    const retryResponse = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockRejectedValueOnce(new Error('关联服务不可用')).mockReturnValueOnce(retryResponse.promise)
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('关联状态加载失败')
    const retry = wrapper.get('[role="alert"] button')
    expect(retry.classes()).toContain('min-h-11')

    const firstClick = retry.trigger('click')
    const secondClick = retry.trigger('click')
    await Promise.all([firstClick, secondClick])
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)

    retryResponse.resolve({ items: [accountB] })
    await flushPromises()
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('Google')
  })

  it('does not re-read after same-token owner hydration completes a token-only session', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const wrapper = createWrapper()
    await flushPromises()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)

    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')).toBe(true)
    await flushPromises()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('GitHub')
  })

  it('ignores a late response from the previous account while keeping the new load active', async () => {
    vi.useFakeTimers()
    const oldResponse = deferred<{ items: ConnectedAccount[] }>()
    const newResponse = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const wrapper = createWrapper()
    await Promise.resolve()

    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await runPendingReloads()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载关联状态')

    oldResponse.reject(new Error('旧账号请求失败'))
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载关联状态')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newResponse.resolve({ items: [accountB] })
    await flushPromises()
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('Google')
  })

  it('invalidates old requests across an ABA session switch and keeps the final session response', async () => {
    vi.useFakeTimers()
    const oldResponse = deferred<{ items: ConnectedAccount[] }>()
    const finalResponse = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(finalResponse.promise)
    const wrapper = createWrapper()
    await Promise.resolve()

    const auth = useAuthStore()
    auth.logout()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    await runPendingReloads()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)

    oldResponse.resolve({ items: [accountA] })
    await flushPromises()
    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)

    finalResponse.resolve({ items: [accountB] })
    await flushPromises()
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('Google')
  })

  it('invalidates a pending request when a same-token auth event starts a new session', async () => {
    vi.useFakeTimers()
    const oldResponse = deferred<{ items: ConnectedAccount[] }>()
    const newResponse = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const wrapper = createWrapper()
    await Promise.resolve()

    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await runPendingReloads()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)

    oldResponse.resolve({ items: [accountA] })
    await flushPromises()
    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)

    newResponse.resolve({ items: [accountB] })
    await flushPromises()
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('Google')
  })

  it('reloads when a known owner changes under the same token', async () => {
    vi.useFakeTimers()
    const oldResponse = deferred<{ items: ConnectedAccount[] }>()
    const newResponse = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const wrapper = createWrapper()
    await Promise.resolve()

    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 2, username: 'bob' } })
    await runPendingReloads()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)

    oldResponse.resolve({ items: [accountA] })
    await flushPromises()
    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)

    newResponse.resolve({ items: [accountB] })
    await flushPromises()
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('Google')
  })

  it('does not treat a failed login generation as a session change', async () => {
    const response = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockReturnValueOnce(response.promise)
    const wrapper = createWrapper()
    await Promise.resolve()

    useAuthStore().invalidateAuthOperations()
    response.resolve({ items: [accountA] })
    await flushPromises()

    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('GitHub')
  })

  it('removes auth listeners and ignores late success or failure after unmount', async () => {
    const response = deferred<{ items: ConnectedAccount[] }>()
    listConnectedAccountsMock.mockReset()
    listConnectedAccountsMock.mockReturnValueOnce(response.promise)
    const wrapper = createWrapper()
    await Promise.resolve()
    wrapper.unmount()

    response.reject(new Error('late failure'))
    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await flushPromises()

    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
  })
})

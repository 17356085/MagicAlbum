import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsConnectedTab from '../settings/SettingsConnectedTab.vue'
import { disconnectAccount, listConnectedAccounts } from '@/api/connected'
import { useAuthStore } from '@/stores/auth'
import type { ConnectedAccount } from '@/types'

const mocks = vi.hoisted(() => ({
  listConnectedAccounts: vi.fn(),
  disconnectAccount: vi.fn(),
}))

vi.mock('@/api/connected', () => ({
  listConnectedAccounts: mocks.listConnectedAccounts,
  disconnectAccount: mocks.disconnectAccount,
}))

const listConnectedAccountsMock = vi.mocked(listConnectedAccounts)
const disconnectAccountMock = vi.mocked(disconnectAccount)

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

const accountA: ConnectedAccount = { provider: 'github', connected: true, bindingTag: 'a'.repeat(64) }
const accountB: ConnectedAccount = { provider: 'google', connected: true, bindingTag: 'b'.repeat(64) }

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
  it('allows an existing short password to verify account unlinking', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('old123')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()
    expect(disconnectAccountMock).toHaveBeenCalledWith('github',
      { identifier: 'alice@example.test', currentPassword: 'old123', bindingTag: 'a'.repeat(64) }, 'token-a')
  })
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    listConnectedAccountsMock.mockResolvedValue({ items: [accountA] })
    disconnectAccountMock.mockResolvedValue()
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    vi.useRealTimers()
  })

  it('offers verified unlink only for connected accounts', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('GitHub')
    expect(wrapper.find('[aria-label="解绑 GitHub"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('绑定功能尚未开放')
  })

  it('shows a connected account from an older server without offering unsafe unlink', async () => {
    listConnectedAccountsMock.mockResolvedValueOnce({ items: [{ provider: 'github', connected: true }] })
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.find('[aria-label="解绑 GitHub"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('当前服务暂不支持安全解绑')
  })

  it('verifies local credentials once, clears the password and refreshes after success', async () => {
    const pending = deferred<void>()
    disconnectAccountMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    expect(wrapper.text()).toContain('可能创建新账号')
    await wrapper.get('#connected-disconnect-identifier').setValue('  ALICE@example.test  ')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')

    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    expect(disconnectAccountMock).toHaveBeenCalledTimes(1)
    expect(disconnectAccountMock).toHaveBeenCalledWith('github',
      { identifier: 'alice@example.test', currentPassword: 'CurrentPassword1', bindingTag: 'a'.repeat(64) }, 'token-a')
    expect((wrapper.get('#connected-disconnect-password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.get('#connected-disconnect-form button[type="submit"]').attributes('disabled')).toBeDefined()

    listConnectedAccountsMock.mockResolvedValueOnce({ items: [{ ...accountA, connected: false }] })
    pending.resolve()
    await flushPromises()
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[role="status"]').text()).toContain('解绑完成')
    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.find('[aria-label="解绑 GitHub"]').exists()).toBe(false)
  })

  it('keeps the form available after rejected credentials and never retains the password', async () => {
    disconnectAccountMock.mockRejectedValueOnce({ response: { status: 400 } })
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('WrongPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('邮箱、手机号或密码不正确')
    expect((wrapper.get('#connected-disconnect-password').element as HTMLInputElement).value).toBe('')
    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(true)
  })

  it('treats a network failure as uncertain and confirms the current binding by refreshing', async () => {
    disconnectAccountMock.mockRejectedValueOnce(new Error('connection lost'))
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('13900001701')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法确认解绑结果')

    listConnectedAccountsMock.mockResolvedValueOnce({ items: [{ ...accountA, connected: false }] })
    await wrapper.findAll('button').find(button => button.text() === '刷新关联状态')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('当前未关联')
  })

  it('keeps the form when a refresh changes only provider letter case', async () => {
    disconnectAccountMock.mockRejectedValueOnce(new Error('connection lost'))
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()
    listConnectedAccountsMock.mockResolvedValueOnce({ items: [{ ...accountA, provider: ' GITHUB ' }] })

    await wrapper.findAll('button').find(button => button.text() === '刷新关联状态')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('当前未关联')
  })

  it('discards the stale form if the binding marker changes after conflict', async () => {
    disconnectAccountMock.mockRejectedValueOnce({ response: { status: 409 } })
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('关联状态已变更')
    listConnectedAccountsMock.mockResolvedValueOnce({ items: [{ ...accountA, bindingTag: 'c'.repeat(64) }] })

    await wrapper.findAll('button').find(button => button.text() === '刷新关联状态')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('关联状态已变更，请重新确认')
    expect(wrapper.find('[aria-label="解绑 GitHub"]').exists()).toBe(true)
  })

  it('does not claim the account is unlinked if the read-back still shows it connected', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toContain('当前仍显示关联')
    expect(wrapper.text()).not.toContain('解绑完成。')
  })

  it('does not infer the binding state when the read-back omits that provider', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    listConnectedAccountsMock.mockResolvedValueOnce({ items: [] })
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toContain('列表未返回该平台')
    expect(wrapper.text()).not.toContain('解绑完成。')
  })

  it('explains a failed read-back after the server confirms the unlink request', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    listConnectedAccountsMock.mockRejectedValueOnce(new Error('read-back unavailable'))
    await wrapper.get('#connected-disconnect-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toContain('关联状态刷新失败')
    expect(wrapper.get('[role="alert"]').text()).toContain('关联状态加载失败')
    expect(wrapper.text()).not.toContain('正在刷新关联状态')

    listConnectedAccountsMock.mockResolvedValueOnce({ items: [{ ...accountA, connected: false }] })
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toContain('解绑完成')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('does not offer a sensitive form until the current owner has been hydrated', async () => {
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: null })
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')

    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('账号信息尚未就绪')
    expect(disconnectAccountMock).not.toHaveBeenCalled()
  })

  it('invalidates a pending unlink across a same-token session event', async () => {
    vi.useFakeTimers()
    const pending = deferred<void>()
    disconnectAccountMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')

    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await runPendingReloads()
    pending.resolve()
    await flushPromises()

    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('解绑完成')
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(2)
  })

  it('clears the form when the stored account changes before a pending result arrives', async () => {
    const pending = deferred<void>()
    disconnectAccountMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    await wrapper.get('#connected-disconnect-form').trigger('submit')

    localStorage.setItem('accessToken', 'token-b')
    pending.resolve()
    await flushPromises()

    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
    expect(wrapper.text()).not.toContain('解绑完成')
    expect(listConnectedAccountsMock).toHaveBeenCalledTimes(1)
  })

  it('does not submit a password when the account changes before the click', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('[aria-label="解绑 GitHub"]').trigger('click')
    await wrapper.get('#connected-disconnect-identifier').setValue('alice@example.test')
    await wrapper.get('#connected-disconnect-password').setValue('CurrentPassword1')
    localStorage.setItem('accessToken', 'token-b')

    await wrapper.get('#connected-disconnect-form').trigger('submit')

    expect(disconnectAccountMock).not.toHaveBeenCalled()
    expect(wrapper.find('#connected-disconnect-form').exists()).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更')
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

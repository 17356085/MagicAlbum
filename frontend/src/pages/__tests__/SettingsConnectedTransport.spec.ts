import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken } from '@/utils/authStorage'
import SettingsConnectedTab from '../settings/SettingsConnectedTab.vue'

const originalAdapter = api.defaults.adapter
const wrappers: VueWrapper[] = []

type TransportPayload = unknown

function storageSnapshot(storage: Storage): Array<[string, string]> {
  return Array.from({ length: storage.length }, (_, index) => storage.key(index))
    .filter((key): key is string => key !== null)
    .map((key): [string, string] => [key, storage.getItem(key) || ''])
    .sort(([left], [right]) => left.localeCompare(right))
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function createTransport(payloads: TransportPayload[]) {
  const requests: Array<{ method?: string; url?: string; authorization?: string }> = []
  api.defaults.adapter = async (config) => {
    const authorization = typeof config.headers.get === 'function'
      ? config.headers.get('Authorization')
      : undefined
    requests.push({
      method: config.method,
      url: config.url,
      authorization: typeof authorization === 'string' ? authorization : undefined,
    })

    return {
      config,
      data: payloads.shift(),
      headers: {},
      status: 200,
      statusText: 'OK',
    }
  }
  return requests
}

function mountConnected(vueErrors: unknown[]): VueWrapper {
  const wrapper = mount(SettingsConnectedTab, {
    global: {
      config: {
        errorHandler(error) {
          vueErrors.push(error)
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

function expectNoAuthSideEffects(
  auth: ReturnType<typeof useAuthStore>,
  beforeLocalStorage: Array<[string, string]>,
  beforeSessionStorage: Array<[string, string]>,
  beforeGeneration: number,
): void {
  expect(auth.token).toBe('token-a')
  expect(auth.user).toEqual({ id: 1, username: 'user1' })
  expect(getStoredAccessToken()).toBe('token-a')
  expect(storageSnapshot(localStorage)).toEqual(beforeLocalStorage)
  expect(storageSnapshot(sessionStorage)).toEqual(beforeSessionStorage)
  expect(auth.getAuthGeneration()).toBe(beforeGeneration)
}

describe('connected-account reader transport contract', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({
      accessToken: 'token-a',
      user: { id: 1, username: 'user1' },
    })
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    api.defaults.adapter = originalAdapter
    localStorage.clear()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('shows a local error for a malformed envelope instead of claiming an empty list', async () => {
    const requests = createTransport([{}])
    const vueErrors: unknown[] = []
    const wrapper = mountConnected(vueErrors)

    await settle()

    expect(vueErrors).toEqual([])
    expect(requests).toEqual([
      { method: 'get', url: '/users/me/connected-accounts', authorization: 'Bearer token-a' },
    ])
    const error = wrapper.find('[role="alert"]')
    expect(error.exists()).toBe(true)
    if (!error.exists()) return
    expect(error.text()).not.toBe('')
    expect(wrapper.text()).not.toContain('暂无已关联账号')
    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)
  })

  it.each([
    ['a null row', { items: [null] }],
    ['a row without provider', { items: [{ connected: true }] }],
  ])('shows a local error for %s without Vue render errors', async (_label, payload) => {
    createTransport([payload])
    const vueErrors: unknown[] = []
    const wrapper = mountConnected(vueErrors)

    await settle()

    expect(vueErrors).toEqual([])
    const error = wrapper.find('[role="alert"]')
    expect(error.exists()).toBe(true)
    if (!error.exists()) return
    expect(error.text()).not.toBe('')
    expect(wrapper.text()).not.toContain('暂无已关联账号')
    expect(wrapper.find('[aria-label="已关联账号状态"]').exists()).toBe(false)
  })

  it('renders a confirmed provider and exposes no connect or disconnect operation buttons', async () => {
    const requests = createTransport([{ items: [{ provider: 'github', connected: true }] }])
    const vueErrors: unknown[] = []
    const wrapper = mountConnected(vueErrors)

    await settle()

    expect(vueErrors).toEqual([])
    expect(requests).toHaveLength(1)
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('GitHub')
    expect(wrapper.text()).toContain('该第三方账号已与当前账号关联。')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })

  it('retries through the existing retry button after a malformed envelope and preserves auth state', async () => {
    const requests = createTransport([{}, { items: [{ provider: 'github', connected: true }] }])
    const vueErrors: unknown[] = []
    const auth = useAuthStore()
    const beforeLocalStorage = storageSnapshot(localStorage)
    const beforeSessionStorage = storageSnapshot(sessionStorage)
    const beforeGeneration = auth.getAuthGeneration()
    const wrapper = mountConnected(vueErrors)

    await settle()
    const error = wrapper.find('[role="alert"]')
    expect(error.exists()).toBe(true)
    if (!error.exists()) return
    expect(wrapper.text()).not.toContain('暂无已关联账号')

    await error.get('button').trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(requests).toHaveLength(2)
    expect(requests.every((request) => request.authorization === 'Bearer token-a')).toBe(true)
    expect(wrapper.get('[aria-label="已关联账号状态"]').text()).toContain('GitHub')
    expect(wrapper.text()).toContain('该第三方账号已与当前账号关联。')
    expect(wrapper.findAll('button')).toHaveLength(0)
    expectNoAuthSideEffects(auth, beforeLocalStorage, beforeSessionStorage, beforeGeneration)
  })
})

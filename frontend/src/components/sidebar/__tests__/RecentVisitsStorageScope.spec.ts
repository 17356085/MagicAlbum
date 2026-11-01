import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecentVisitsPanel from '../RecentVisitsPanel.vue'
import { addVisit } from '@/composables/useRecentVisits'
import { useAuthStore } from '@/stores/auth'
import * as authStorage from '@/utils/authStorage'

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

const ACCOUNT_A = { accessToken: 'token-a', user: { id: 1, username: 'a' } }
const ACCOUNT_B = { accessToken: 'token-b', user: { id: 2, username: 'b' } }
const SHARED_PATH = '/threads/shared-history'
const SHARED_ID = 42
const A_KEY = 'recent_visits_v1:1'
const B_KEY = 'recent_visits_v1:2'
const BINDING_KEY = 'currentUserSession.v1'

let pinia: ReturnType<typeof createPinia>
const wrappers: VueWrapper[] = []

async function createPanelRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/history', name: 'history', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'discover' })
  await router.isReady()
  return router
}

function seedAccountHistory(): void {
  const auth = useAuthStore()
  auth.applyAuthState(ACCOUNT_A)
  addVisit({ path: SHARED_PATH, title: '账号 A 浏览', id: SHARED_ID })
  auth.applyAuthState(ACCOUNT_B)
  addVisit({ path: SHARED_PATH, title: '账号 B 浏览', id: SHARED_ID })
  auth.applyAuthState(ACCOUNT_A)
}

function storageBytes(): { a: string | null; b: string | null } {
  return {
    a: localStorage.getItem(A_KEY),
    b: localStorage.getItem(B_KEY),
  }
}

function dispatchStorage(key: string | null, storageArea: Storage | null = localStorage): void {
  window.dispatchEvent(new StorageEvent('storage', {
    key,
    newValue: key === null || !storageArea ? null : storageArea.getItem(key),
    storageArea,
  }))
}

function driftPersistedStorageToB(): void {
  const auth = useAuthStore()
  const authChanged = vi.fn()
  window.addEventListener('auth-state-changed', authChanged)
  try {
    authStorage.persistAuthState(ACCOUNT_B)
    expect(authChanged).not.toHaveBeenCalled()
    expect(auth.token).toBe(ACCOUNT_A.accessToken)
    expect(auth.user).toMatchObject(ACCOUNT_A.user)
    expect(authStorage.getStoredCurrentUser(ACCOUNT_B.accessToken)).toMatchObject(ACCOUNT_B.user)
  } finally {
    window.removeEventListener('auth-state-changed', authChanged)
  }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().trim() === text)
  expect(button, `expected button ${text}`).toBeDefined()
  return button!
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

async function mountPanel(vueErrors: unknown[]): Promise<VueWrapper> {
  const router = await createPanelRouter()
  const wrapper = mount(RecentVisitsPanel, {
    global: {
      plugins: [pinia, router],
      config: {
        errorHandler(error) {
          vueErrors.push(error)
        },
      },
      stubs: { RouterLink: RouterLinkStub },
    },
  })
  wrappers.push(wrapper)
  await settle()
  return wrapper
}

describe('RecentVisitsPanel storage scope during a stale rendered session', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('preserves both raw keys when stale account A clears its rendered panel', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    const before = storageBytes()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')

    driftPersistedStorageToB()
    const clearButton = buttonByText(wrapper, '清除')
    expect(clearButton.attributes('disabled')).toBeUndefined()
    await clearButton.trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(storageBytes()).toEqual(before)
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
  })

  it('does not publish account B when refresh resolves storage after account A was rendered', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    const before = storageBytes()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')

    driftPersistedStorageToB()
    await buttonByText(wrapper, '刷新').trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(wrapper.text()).not.toContain('账号 B 浏览')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
    expect(storageBytes()).toEqual(before)

    useAuthStore().applyAuthState(ACCOUNT_B)
    await settle()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('账号 B 浏览')
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(vueErrors).toEqual([])
  })

  it('clears only the guest key in a standalone guest panel', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: '', user: null })
    addVisit({ path: '/threads/guest', title: '访客浏览', id: 99 })
    auth.applyAuthState(ACCOUNT_A)
    addVisit({ path: SHARED_PATH, title: '账号 A 浏览', id: SHARED_ID })
    const accountBytes = localStorage.getItem('recent_visits_v1:1')
    auth.applyAuthState({ accessToken: '', user: null })

    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    expect(wrapper.text()).toContain('访客浏览')

    await buttonByText(wrapper, '清除').trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(localStorage.getItem('recent_visits_v1:guest')).toBeNull()
    expect(localStorage.getItem('recent_visits_v1:1')).toBe(accountBytes)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('refreshes the current account on update and clear storage events', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)

    const updated = JSON.stringify([
      { path: '/threads/new', title: '账号 A 新增', id: 43, ts: Date.now() },
    ])
    localStorage.setItem(A_KEY, updated)
    localStorage.setItem(B_KEY, JSON.stringify([
      { path: '/threads/other', title: '账号 B 更新', id: 44, ts: Date.now() },
    ]))
    sessionStorage.setItem(A_KEY, JSON.stringify([
      { path: '/threads/session', title: 'sessionStorage 记录', id: 45, ts: Date.now() },
    ]))
    dispatchStorage(B_KEY)
    dispatchStorage('unrelated-key')
    dispatchStorage(A_KEY, sessionStorage)
    await settle()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 A 新增')
    expect(wrapper.text()).not.toContain('账号 B 更新')
    expect(wrapper.text()).not.toContain('sessionStorage 记录')
    expect(localStorage.getItem(A_KEY)).toBe(updated)

    dispatchStorage(A_KEY)
    await settle()

    expect(wrapper.text()).toContain('账号 A 新增')
    expect(wrapper.text()).not.toContain('账号 A 浏览')

    localStorage.removeItem(A_KEY)
    dispatchStorage(A_KEY)
    await settle()

    expect(wrapper.text()).not.toContain('账号 A 新增')
    expect(localStorage.getItem(A_KEY)).toBeNull()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(vueErrors).toEqual([])

    localStorage.clear()
    dispatchStorage(null)
    await settle()

    expect(wrapper.text()).not.toContain('账号 A 新增')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
  })

  it('supports guest storage events while ignoring other accounts and sessionStorage', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: '', user: null })
    addVisit({ path: '/threads/guest', title: '访客浏览', id: 99 })
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)

    const guestKey = 'recent_visits_v1:guest'
    const updated = JSON.stringify([
      { path: '/threads/guest-new', title: '访客新增', id: 100, ts: Date.now() },
    ])
    localStorage.setItem(guestKey, updated)
    dispatchStorage(guestKey)
    await settle()
    expect(wrapper.text()).toContain('访客新增')
    expect(localStorage.getItem(guestKey)).toBe(updated)

    localStorage.setItem(B_KEY, JSON.stringify([
      { path: '/threads/other', title: '账号 B 更新', id: 44, ts: Date.now() },
    ]))
    dispatchStorage(B_KEY)
    dispatchStorage('unrelated-key')
    sessionStorage.setItem(guestKey, JSON.stringify([
      { path: '/threads/session', title: 'sessionStorage 记录', id: 45, ts: Date.now() },
    ]))
    dispatchStorage(guestKey, sessionStorage)
    await settle()

    expect(wrapper.text()).toContain('访客新增')
    expect(wrapper.text()).not.toContain('账号 B 更新')
    expect(wrapper.text()).not.toContain('sessionStorage 记录')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(vueErrors).toEqual([])

    localStorage.clear()
    dispatchStorage(null)
    await settle()

    expect(wrapper.text()).not.toContain('访客新增')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('ignores storage events without the localStorage area', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    expect(wrapper.text()).toContain('账号 A 浏览')

    driftPersistedStorageToB()
    dispatchStorage(B_KEY, null)
    sessionStorage.setItem(B_KEY, JSON.stringify([
      { path: '/threads/session-drift', title: 'sessionStorage 漂移', id: 49, ts: Date.now() },
    ]))
    dispatchStorage(B_KEY, sessionStorage)
    await settle()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(vueErrors).toEqual([])
  })

  it('clears the stale view before filtering when persisted identity drifts', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    expect(wrapper.text()).toContain('账号 A 浏览')

    driftPersistedStorageToB()
    localStorage.setItem(B_KEY, JSON.stringify([
      { path: '/threads/drift', title: '账号 B 漂移事件', id: 46, ts: Date.now() },
    ]))
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
    window.dispatchEvent(new StorageEvent('storage', { key: B_KEY, newValue: null, storageArea: localStorage }))
    await settle()

    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 漂移事件')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
    expect(getItem.mock.calls.some(([key]) => key === B_KEY)).toBe(false)
    expect(vueErrors).toEqual([])
    getItem.mockRestore()
  })

  it('recovers the same account after its binding record is restored without reading B or writing', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    const bindingValue = localStorage.getItem(BINDING_KEY)
    const beforeA = localStorage.getItem(A_KEY)
    expect(bindingValue).toBeTruthy()

    localStorage.removeItem(BINDING_KEY)
    dispatchStorage(BINDING_KEY)
    await settle()
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')

    localStorage.setItem(BINDING_KEY, bindingValue!)
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    window.dispatchEvent(new StorageEvent('storage', { key: BINDING_KEY, newValue: bindingValue, storageArea: localStorage }))
    await settle()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(localStorage.getItem(A_KEY)).toBe(beforeA)
    expect(getItem.mock.calls.some(([key]) => key === B_KEY)).toBe(false)
    expect(setItem.mock.calls.some(([key]) => key === A_KEY)).toBe(false)
    expect(vueErrors).toEqual([])
    getItem.mockRestore()
    setItem.mockRestore()
  })

  it('removes storage and recent-visits listeners on unmount', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountPanel(vueErrors)
    const removeEventListener = vi.spyOn(window, 'removeEventListener')

    wrapper.unmount()

    expect(removeEventListener).toHaveBeenCalledWith('recent-visits-updated', expect.any(Function))
    expect(removeEventListener).toHaveBeenCalledWith('auth-state-changed', expect.any(Function))
    expect(removeEventListener).toHaveBeenCalledWith('storage', expect.any(Function))
    expect(vueErrors).toEqual([])
    removeEventListener.mockRestore()
  })
})

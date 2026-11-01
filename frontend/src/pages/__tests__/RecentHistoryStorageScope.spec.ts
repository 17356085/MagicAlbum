import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecentHistory from '../RecentHistory.vue'
import { useAuthStore } from '@/stores/auth'
import { addVisit, clearAllRecentVisits } from '@/composables/useRecentVisits'
import * as authStorage from '@/utils/authStorage'

const listSectionsMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/sections', () => ({
  listSections: listSectionsMock,
}))

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

async function createHistoryRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/history', name: 'history', component: { template: '<div />' } },
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'history' })
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

async function mountHistory(vueErrors: unknown[]): Promise<VueWrapper> {
  const router = await createHistoryRouter()
  const wrapper = mount(RecentHistory, {
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

describe('RecentHistory storage scope during a stale rendered session', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    listSectionsMock.mockReset()
    listSectionsMock.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
  })

  afterEach(() => {
    for (const wrapper of wrappers.splice(0)) wrapper.unmount()
    localStorage.clear()
    sessionStorage.clear()
  })

  it('renders account A before storage drifts, then preserves both raw keys on clear', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
    const before = storageBytes()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')

    driftPersistedStorageToB()
    const clearButton = buttonByText(wrapper, '清空')
    expect(clearButton.attributes('disabled')).toBeUndefined()
    await clearButton.trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(storageBytes()).toEqual(before)
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')

    await buttonByText(wrapper, '重新加载').trigger('click')
    await settle()
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
    expect(wrapper.text()).not.toContain('账号 B 浏览')

    useAuthStore().applyAuthState(ACCOUNT_B)
    await settle()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('账号 B 浏览')
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(vueErrors).toEqual([])
  })

  it('preserves account B when stale account A removes the shared rendered target', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
    const before = storageBytes()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')

    driftPersistedStorageToB()
    const removeButton = buttonByText(wrapper, '移除')
    expect(removeButton.attributes('disabled')).toBeUndefined()
    await removeButton.trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(storageBytes()).toEqual(before)
  })

  it('clears only account A during a normal current-account action', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
    const before = storageBytes()

    await buttonByText(wrapper, '清空').trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(localStorage.getItem(A_KEY)).toBe('[]')
    expect(localStorage.getItem(B_KEY)).toBe(before.b)
  })

  it('removes only account A when the current displayed owner removes one item', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
    const before = storageBytes()

    await buttonByText(wrapper, '移除').trigger('click')
    await settle()

    expect(vueErrors).toEqual([])
    expect(localStorage.getItem(A_KEY)).toBe('[]')
    expect(localStorage.getItem(B_KEY)).toBe(before.b)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('refreshes current-account updates and clear events without writing back', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)

    const updated = JSON.stringify([
      { path: '/threads/new', title: '账号 A 新增', id: 43, ts: Date.now() },
    ])
    localStorage.setItem(A_KEY, updated)
    dispatchStorage(A_KEY)
    await settle()

    expect(wrapper.text()).toContain('账号 A 新增')
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(localStorage.getItem(A_KEY)).toBe(updated)

    localStorage.removeItem(A_KEY)
    dispatchStorage(A_KEY)
    await settle()
    expect(wrapper.text()).not.toContain('账号 A 新增')
    expect(localStorage.getItem(A_KEY)).toBeNull()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    addVisit({ path: '/threads/same-window', title: '同窗口新增', id: 47 })
    const sameWindowUpdated = localStorage.getItem(A_KEY)
    await settle()

    expect(wrapper.text()).toContain('同窗口新增')
    expect(wrapper.text()).not.toContain('账号 A 新增')
    expect(localStorage.getItem(A_KEY)).toBe(sameWindowUpdated)

    clearAllRecentVisits()
    await settle()

    expect(wrapper.text()).not.toContain('同窗口新增')
    expect(localStorage.getItem(A_KEY)).toBeNull()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(vueErrors).toEqual([])

    localStorage.clear()
    dispatchStorage(null)
    await settle()

    expect(wrapper.find('[role="alert"]').text()).toContain('账号状态已变化')
    expect(wrapper.text()).not.toContain('账号 A 新增')
  })

  it('ignores other-account, unrelated-key, and sessionStorage events', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
    const currentUpdated = JSON.stringify([
      { path: '/threads/current-updated', title: '账号 A 静默更新', id: 48, ts: Date.now() },
    ])
    localStorage.setItem(A_KEY, currentUpdated)

    const otherUpdated = JSON.stringify([
      { path: '/threads/other', title: '账号 B 更新', id: 44, ts: Date.now() },
    ])
    localStorage.setItem(B_KEY, otherUpdated)
    dispatchStorage(B_KEY)
    dispatchStorage('unrelated-key')
    sessionStorage.setItem(A_KEY, JSON.stringify([
      { path: '/threads/session', title: 'sessionStorage 记录', id: 45, ts: Date.now() },
    ]))
    dispatchStorage(A_KEY, sessionStorage)
    await settle()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 A 静默更新')
    expect(wrapper.text()).not.toContain('账号 B 更新')
    expect(wrapper.text()).not.toContain('sessionStorage 记录')
    expect(storageBytes().a).toBe(currentUpdated)
    expect(storageBytes().b).toBe(otherUpdated)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(vueErrors).toEqual([])

    dispatchStorage(A_KEY)
    await settle()
    expect(wrapper.text()).toContain('账号 A 静默更新')
    expect(wrapper.text()).not.toContain('账号 A 浏览')
    expect(storageBytes().a).toBe(currentUpdated)
    expect(storageBytes().b).toBe(otherUpdated)
  })

  it('ignores storage events without the localStorage area', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
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
    const wrapper = await mountHistory(vueErrors)
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
    const wrapper = await mountHistory(vueErrors)
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

  it('recovers a stale view from a same-window recent-visits event after binding restoration', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const wrapper = await mountHistory(vueErrors)
    const bindingValue = localStorage.getItem(BINDING_KEY)
    expect(bindingValue).toBeTruthy()

    localStorage.removeItem(BINDING_KEY)
    dispatchStorage(BINDING_KEY)
    await settle()
    expect(wrapper.find('[role="alert"]').text()).toContain('账号状态已变化')

    localStorage.setItem(BINDING_KEY, bindingValue!)
    window.dispatchEvent(new CustomEvent('recent-visits-updated'))
    await settle()

    expect(wrapper.text()).toContain('账号 A 浏览')
    expect(wrapper.text()).not.toContain('账号 B 浏览')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(vueErrors).toEqual([])
  })

  it('removes storage and recent-visits listeners on unmount', async () => {
    seedAccountHistory()
    const vueErrors: unknown[] = []
    const router = await createHistoryRouter()
    const removeEventListener = vi.spyOn(window, 'removeEventListener')
    const wrapper = mount(RecentHistory, {
      global: {
        plugins: [pinia, router],
        config: { errorHandler(error) { vueErrors.push(error) } },
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    wrappers.push(wrapper)
    await settle()

    wrapper.unmount()

    expect(removeEventListener).toHaveBeenCalledWith('auth-state-changed', expect.any(Function))
    expect(removeEventListener).toHaveBeenCalledWith('storage', expect.any(Function))
    expect(removeEventListener).toHaveBeenCalledWith('recent-visits-updated', expect.any(Function))
    expect(vueErrors).toEqual([])
    removeEventListener.mockRestore()
  })
})

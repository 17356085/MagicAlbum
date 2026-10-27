import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RecentHistory from '../RecentHistory.vue'
import { useAuthStore } from '@/stores/auth'
import { addVisit } from '@/composables/useRecentVisits'
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
})

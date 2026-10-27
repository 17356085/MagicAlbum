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
})

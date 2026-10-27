import { flushPromises, mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RecentHistory from '../RecentHistory.vue'
import { useAuthStore } from '@/stores/auth'

const listSectionsMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/sections', () => ({
  listSections: listSectionsMock,
}))

const RouterLinkStub = {
  props: ['to'],
  template: '<a :href="String(to)"><slot /></a>',
}

async function createHistoryRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/history', name: 'history', component: RecentHistory },
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
    ],
  })
  await router.push({ name: 'history' })
  await router.isReady()
  return router
}

describe('RecentHistory auth isolation', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    listSectionsMock.mockResolvedValue({ items: [], page: 1, size: 200, total: 0 })
  })

  it('shows owner history when a token-only session is confirmed without a new login event', async () => {
    localStorage.setItem('recent_visits_v1:7', JSON.stringify([
      { path: '/threads/seven', title: '确认身份后恢复的记录', ts: Date.now() },
    ]))
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-seven', user: null })
    const router = await createHistoryRouter()
    const wrapper = mount(RecentHistory, { global: { plugins: [router], stubs: { RouterLink: RouterLinkStub } } })
    try {
      await flushPromises()
      expect(wrapper.text()).not.toContain('确认身份后恢复的记录')
      expect(auth.hydrateCurrentUser({ id: 7, username: 'seven' }, 'token-seven')).toBe(true)
      await flushPromises()
      expect(wrapper.text()).toContain('确认身份后恢复的记录')
    } finally { wrapper.unmount() }
  })

  it('reloads the current account history on auth changes and clears it on logout', async () => {
    localStorage.setItem('recent_visits_v1:1', JSON.stringify([
      { path: '/threads/a', title: '账号 A 记录', ts: Date.now() },
    ]))
    localStorage.setItem('recent_visits_v1:2', JSON.stringify([
      { path: '/threads/b', title: '账号 B 记录', ts: Date.now() },
    ]))

    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
    const router = await createHistoryRouter()
    const wrapper = mount(RecentHistory, {
      global: {
        plugins: [router],
        stubs: { RouterLink: RouterLinkStub },
      },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('账号 A 记录')
    expect(wrapper.text()).not.toContain('账号 B 记录')

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'account-b' } })
    await nextTick()
    await flushPromises()

    expect(wrapper.text()).toContain('账号 B 记录')
    expect(wrapper.text()).not.toContain('账号 A 记录')

    auth.clearAuthState()
    await nextTick()
    await flushPromises()

    expect(wrapper.text()).not.toContain('账号 B 记录')
    expect(router.currentRoute.value.name).toBe('discover')

    wrapper.unmount()
  })

  it('removes the auth listener on unmount', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
    const router = await createHistoryRouter()
    const removeEventListener = vi.spyOn(window, 'removeEventListener')
    const wrapper = mount(RecentHistory, {
      global: {
        plugins: [router],
        stubs: { RouterLink: RouterLinkStub },
      },
    })

    wrapper.unmount()

    expect(removeEventListener).toHaveBeenCalledWith('auth-state-changed', expect.any(Function))
    removeEventListener.mockRestore()
  })
})

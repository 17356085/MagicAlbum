import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import MobileMe from '@/pages/MobileMe.vue'
import { useAuthStore } from '@/stores/auth'
import { getPendingAuthRedirect } from '@/utils/authStorage'

const page = { template: '<div />' }
const mocks = vi.hoisted(() => ({ getGovernanceMe: vi.fn() }))
vi.mock('@/api/governance', () => ({ getGovernanceMe: mocks.getGovernanceMe }))
let dialogShowModal: PropertyDescriptor | undefined
let dialogClose: PropertyDescriptor | undefined
const showModalMock = vi.fn(function (this: HTMLDialogElement) {
  Object.defineProperty(this, 'open', { configurable: true, value: true, writable: true })
})
const closeMock = vi.fn(function (this: HTMLDialogElement) {
  Object.defineProperty(this, 'open', { configurable: true, value: false, writable: true })
})

async function createWrapper(loggedIn: boolean) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.clearAuthState()
  if (loggedIn) {
    auth.applyAuthState({ accessToken: 'mobile-me-token', user: { id: 7, username: 'luna', nickname: 'Luna' } })
  }

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/me', name: 'me', component: page },
      { path: '/users', name: 'users', component: page },
      { path: '/users/:id', name: 'user-profile', component: page },
      { path: '/settings', name: 'settings', component: page },
      { path: '/my/threads', name: 'my-threads', component: page },
      { path: '/my/posts', name: 'my-posts', component: page },
      { path: '/my/bookmarks', name: 'my-bookmarks', component: page },
      { path: '/admin/reports', name: 'admin-reports', component: page },
      { path: '/admin/community', name: 'admin-community', component: page },
      { path: '/history', name: 'history', component: page },
    ],
  })
  await router.push('/me')
  await router.isReady()
  const wrapper = mount(MobileMe, { global: { plugins: [pinia, router] } })
  return { auth, wrapper }
}

describe('MobileMe', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.getGovernanceMe.mockReset()
    mocks.getGovernanceMe.mockResolvedValue({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
    document.documentElement.classList.remove('dark')
    dialogShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
    dialogClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', { configurable: true, value: showModalMock })
    Object.defineProperty(HTMLDialogElement.prototype, 'close', { configurable: true, value: closeMock })
    showModalMock.mockClear()
    closeMock.mockClear()
  })

  afterEach(() => {
    if (dialogShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', dialogShowModal)
    else delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).showModal
    if (dialogClose) Object.defineProperty(HTMLDialogElement.prototype, 'close', dialogClose)
    else delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).close
    document.documentElement.classList.remove('dark')
    localStorage.clear()
  })

  it('keeps the guest login, history, theme, and AI entries reachable', async () => {
    const { wrapper } = await createWrapper(false)
    const loginEvent = vi.spyOn(window, 'dispatchEvent')

    expect(wrapper.text()).toContain('登录后管理你的账户')
    const historyButton = wrapper.findAll('button').find((button) => button.text().includes('浏览历史'))
    expect(historyButton).toBeDefined()
    await historyButton!.trigger('click')
    expect(getPendingAuthRedirect()).toBe('/history')
    expect(loginEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'open-login-modal' }))
    expect(wrapper.find('a[href="/my/threads"]').exists()).toBe(false)

    await wrapper.get('button.mobile-me__primary').trigger('click')
    expect(loginEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'open-login-modal' }))
    const aiButton = wrapper.findAll('button').find((button) => button.text().includes('AI 助手'))
    expect(aiButton).toBeDefined()
    await aiButton!.trigger('click')
    expect(loginEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'open-ai-chat' }))

    const themeButton = wrapper.findAll('button').find((button) => button.text().includes('黑夜模式'))
    expect(themeButton).toBeDefined()
    await themeButton!.trigger('click')
    await nextTick()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    wrapper.unmount()
    loginEvent.mockRestore()
  })

  it('opens the native dialog and logs out through the auth store', async () => {
    const { auth, wrapper } = await createWrapper(true)

    expect(wrapper.find('a[href="/users/7"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/my/threads"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/my/posts"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/my/bookmarks"]').exists()).toBe(true)
    await nextTick()
    expect(wrapper.find('a[href="/admin/reports"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/community"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/settings"]').exists()).toBe(true)

    await wrapper.get('button.mobile-me__logout').trigger('click')
    expect(showModalMock).toHaveBeenCalledTimes(1)
    await wrapper.get('button.mobile-me__dialog-button--primary').trigger('click')
    expect(auth.isLoggedIn).toBe(false)
    expect(wrapper.find('button.mobile-me__logout').exists()).toBe(false)
    wrapper.unmount()
  })
})

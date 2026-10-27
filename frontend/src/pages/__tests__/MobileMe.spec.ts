import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import MobileMe from '@/pages/MobileMe.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { getPendingAuthRedirect } from '@/utils/authStorage'

const page = { template: '<div />' }
const mocks = vi.hoisted(() => ({ getGovernanceMe: vi.fn() }))
vi.mock('@/api/governance', () => ({ getGovernanceMe: mocks.getGovernanceMe }))
const mountedWrappers: Array<ReturnType<typeof mount>> = []

async function createWrapper(loggedIn: boolean, attachTo?: Element) {
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
  const wrapper = mount(MobileMe, { global: { plugins: [pinia, router] }, attachTo })
  mountedWrappers.push(wrapper)
  return { auth, wrapper }
}

function findDialogButton(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.get('[role="dialog"]').findAll('button').find((button) => button.text() === text)
}

describe('MobileMe', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.getGovernanceMe.mockReset()
    mocks.getGovernanceMe.mockResolvedValue({ admin: true, moderatedSectionIds: [], postingRestricted: false, restrictedUntil: null })
    document.documentElement.classList.remove('dark')
  })

  afterEach(() => {
    for (const wrapper of mountedWrappers.splice(0)) wrapper.unmount()
    vi.restoreAllMocks()
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
  })

  it('opens the shared confirmation and logs out through the auth store', async () => {
    const { auth, wrapper } = await createWrapper(true, document.body)

    expect(wrapper.find('a[href="/users/7"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/my/threads"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/my/posts"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/my/bookmarks"]').exists()).toBe(true)
    await nextTick()
    expect(wrapper.find('a[href="/admin/reports"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/community"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/settings"]').exists()).toBe(true)

    await wrapper.get('button.mobile-me__logout').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('退出登录？')
    await findDialogButton(wrapper, '确认退出')!.trigger('click')
    expect(auth.isLoggedIn).toBe(false)
    expect(wrapper.find('button.mobile-me__logout').exists()).toBe(false)
  })

  it('cancels and escapes without native dialog methods, then confirms normally', async () => {
    const originalShowModal = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'showModal')
    const originalClose = Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, 'close')
    try {
      delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).showModal
      delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).close
      const { auth, wrapper } = await createWrapper(true, document.body)
      const logoutButton = wrapper.get<HTMLButtonElement>('button.mobile-me__logout')

      await logoutButton.trigger('click')
      await findDialogButton(wrapper, '取消')!.trigger('click')
      expect(auth.isLoggedIn).toBe(true)
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

      logoutButton.element.focus()
      await logoutButton.trigger('click')
      const dialog = wrapper.get('[role="dialog"]')
      expect(document.activeElement).toBe(dialog.element)
      await dialog.trigger('keydown', { key: 'Escape' })
      expect(auth.isLoggedIn).toBe(true)
      expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
      expect(document.activeElement).toBe(logoutButton.element)

      await logoutButton.trigger('click')
      await findDialogButton(wrapper, '确认退出')!.trigger('click')
      expect(auth.isLoggedIn).toBe(false)
    } finally {
      if (originalShowModal) Object.defineProperty(HTMLDialogElement.prototype, 'showModal', originalShowModal)
      else delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).showModal
      if (originalClose) Object.defineProperty(HTMLDialogElement.prototype, 'close', originalClose)
      else delete (HTMLDialogElement.prototype as unknown as Record<string, unknown>).close
    }
  })

  it('keeps the confirmation open for an auth event in the same session', async () => {
    const { auth, wrapper } = await createWrapper(true, document.body)
    await wrapper.get('button.mobile-me__logout').trigger('click')
    const generation = auth.getAuthGeneration()
    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await nextTick()
    expect(auth.getAuthGeneration()).toBe(generation)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    await findDialogButton(wrapper, '取消')!.trigger('click')
    expect(auth.isLoggedIn).toBe(true)
  })

  it('logs out once for repeated opens and same-tick confirmations', async () => {
    const { auth, wrapper } = await createWrapper(true, document.body)
    const logout = vi.spyOn(auth, 'logout')
    const logoutButton = wrapper.get('button.mobile-me__logout')
    await logoutButton.trigger('click')
    await logoutButton.trigger('click')
    expect(wrapper.findAll('[role="dialog"]')).toHaveLength(1)
    const confirmButton = findDialogButton(wrapper, '确认退出')!
    confirmButton.element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    confirmButton.element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(logout).toHaveBeenCalledTimes(1)
    expect(auth.isLoggedIn).toBe(false)
  })

  it('closes A confirmation on a B session change and ignores the stale confirm', async () => {
    const { auth, wrapper } = await createWrapper(true, document.body)
    await wrapper.get('button.mobile-me__logout').trigger('click')
    const confirmDialog = wrapper.findComponent(ConfirmDialog)
    const confirmHandler = (confirmDialog.vm as unknown as {
      $: { vnode: { props?: Record<string, unknown> } }
    }).$.vnode.props?.onConfirm
    expect(typeof confirmHandler).toBe('function')
    const logout = vi.spyOn(auth, 'logout')

    auth.applyAuthState({ accessToken: 'mobile-me-token-b', user: { id: 8, username: 'b', nickname: 'B' } })
    await nextTick()
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.token).toBe('mobile-me-token-b')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)

    ;(confirmHandler as () => void)()
    expect(logout).not.toHaveBeenCalled()
    expect(auth.token).toBe('mobile-me-token-b')
  })

  it('ignores a delayed parent confirm callback after the page is unmounted', async () => {
    const { auth, wrapper } = await createWrapper(true, document.body)
    await wrapper.get('button.mobile-me__logout').trigger('click')
    const confirmDialog = wrapper.findComponent(ConfirmDialog)
    const confirmHandler = (confirmDialog.vm as unknown as {
      $: { vnode: { props?: Record<string, unknown> } }
    }).$.vnode.props?.onConfirm
    expect(typeof confirmHandler).toBe('function')
    const logout = vi.spyOn(auth, 'logout')

    wrapper.unmount()
    mountedWrappers.splice(mountedWrappers.indexOf(wrapper), 1)
    ;(confirmHandler as () => void)()

    expect(logout).not.toHaveBeenCalled()
    expect(auth.isLoggedIn).toBe(true)
  })
})

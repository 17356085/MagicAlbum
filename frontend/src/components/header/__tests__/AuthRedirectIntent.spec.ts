import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { setPendingAuthRedirect, getPendingAuthRedirect } from '@/utils/authStorage'

const mocks = vi.hoisted(() => ({
  getMyProfile: vi.fn(),
  suggestUsers: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getMyProfile: mocks.getMyProfile,
}))

vi.mock('@/api/users', () => ({
  getCurrentUser: vi.fn(),
  suggestUsers: mocks.suggestUsers,
}))

const SearchBoxStub = defineComponent({
  template: '<div data-testid="search-box" />',
})

const ControlledAuthLoginFormStub = defineComponent({
  emits: ['close', 'success'],
  setup(_, { emit }) {
    const completeLogin = (): void => {
      useAuthStore().applyAuthState({
        accessToken: 'redirect-intent-token',
        user: {
          id: 42,
          username: 'redirect-user',
          nickname: 'Redirect User',
          avatarUrl: '/redirect-user.png',
        },
      })
      emit('success')
    }
    return { completeLogin }
  },
  template: '<button type="button" data-testid="complete-login" @click="completeLogin">完成登录</button>',
})

const simpleStub = defineComponent({
  template: '<div />',
})

type Harness = {
  wrapper: VueWrapper
  router: ReturnType<typeof createRouter>
  navigationCount: () => number
}

const mountedWrappers: VueWrapper[] = []

async function settle(): Promise<void> {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

async function createHarness(): Promise<Harness> {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().clearAuthState()

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: simpleStub },
      { path: '/threads/new', name: 'thread-new', component: simpleStub },
    ],
  })
  await router.push('/discover')
  await router.isReady()

  let navigations = 0
  router.afterEach(() => { navigations += 1 })
  const wrapper = mount(AppHeader, {
    global: {
      plugins: [pinia, router],
      stubs: {
        SearchBox: SearchBoxStub,
        BrandBar: simpleStub,
        NotificationsMenu: simpleStub,
        AuthEntry: simpleStub,
        UserMenu: simpleStub,
        AuthLoginForm: ControlledAuthLoginFormStub,
        AuthQrLogin: simpleStub,
        AuthRegisterForm: simpleStub,
        AuthThirdPartyLogin: simpleStub,
      },
    },
  })
  mountedWrappers.push(wrapper)
  await settle()
  return { wrapper, router, navigationCount: () => navigations }
}

async function openLogin(harness: Harness): Promise<void> {
  window.dispatchEvent(new Event('open-login-modal'))
  await vi.waitFor(() => {
    expect(harness.wrapper.find('[data-testid="complete-login"]').exists()).toBe(true)
  }, { timeout: 3000 })
}

async function cancelWith(harness: Harness, method: 'button' | 'escape' | 'mask'): Promise<void> {
  const dialog = harness.wrapper.find('[role="dialog"]')
  expect(dialog.exists()).toBe(true)
  if (method === 'button') {
    await dialog.find('button[aria-label="关闭"]').trigger('click')
  } else if (method === 'escape') {
    await dialog.trigger('keydown', { key: 'Escape' })
  } else {
    await harness.wrapper.find('.ui-dialog-layer > .absolute.inset-0').trigger('click')
  }
  await settle()
}

describe('AppHeader authentication redirect intent ownership', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mocks.getMyProfile.mockResolvedValue({
      id: 42,
      userId: 42,
      username: 'redirect-user',
      nickname: 'Redirect User',
      avatarUrl: '/redirect-user.png',
    })
    mocks.suggestUsers.mockResolvedValue([])
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    while (mountedWrappers.length > 0) mountedWrappers.pop()?.unmount()
    vi.restoreAllMocks()
    localStorage.clear()
    sessionStorage.clear()
  })

  it.each([
    ['close button', 'button'],
    ['Escape', 'escape'],
    ['backdrop', 'mask'],
  ] as const)('clears a pending redirect when the user cancels with the %s', async (_, method) => {
    const harness = await createHarness()
    setPendingAuthRedirect('/threads/new?draft=cancel#title')

    await openLogin(harness)
    await cancelWith(harness, method)

    expect(getPendingAuthRedirect()).toBe('')
    expect(harness.wrapper.find('[data-testid="complete-login"]').exists()).toBe(false)
  })

  it('does not reuse a cancelled redirect or add a stale history entry on the next direct success', async () => {
    const harness = await createHarness()
    setPendingAuthRedirect('/threads/new?draft=cancelled#title')

    await openLogin(harness)
    await cancelWith(harness, 'button')
    const navigationCountAfterCancel = harness.navigationCount()

    await openLogin(harness)
    await harness.wrapper.find('[data-testid="complete-login"]').trigger('click')
    await settle()

    expect(harness.router.currentRoute.value.fullPath).toBe('/discover')
    expect(harness.navigationCount()).toBe(navigationCountAfterCancel)
    expect(getPendingAuthRedirect()).toBe('')
  })

  it('navigates with query and hash on success, then consumes the intent once before close', async () => {
    const harness = await createHarness()
    setPendingAuthRedirect('/threads/new?draft=1#title')

    await openLogin(harness)
    await harness.wrapper.find('[data-testid="complete-login"]').trigger('click')
    await settle()

    expect(harness.router.currentRoute.value.fullPath).toBe('/threads/new?draft=1#title')
    expect(getPendingAuthRedirect()).toBe('')
    expect(harness.navigationCount()).toBe(1)

    await openLogin(harness)
    await harness.wrapper.find('[data-testid="complete-login"]').trigger('click')
    await settle()
    expect(harness.router.currentRoute.value.fullPath).toBe('/threads/new?draft=1#title')
    expect(harness.navigationCount()).toBe(1)
  })

  it('keeps the pending redirect when the header unmounts without a close event', async () => {
    const harness = await createHarness()
    setPendingAuthRedirect('/threads/new?oauth=1#callback')

    await openLogin(harness)
    harness.wrapper.unmount()
    mountedWrappers.splice(mountedWrappers.indexOf(harness.wrapper), 1)

    expect(getPendingAuthRedirect()).toBe('/threads/new?oauth=1#callback')
  })
})

import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, ref } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AuthModal from '../AuthModal.vue'
import SidebarLeft from '@/components/SidebarLeft.vue'
import { useAuthStore } from '@/stores/auth'
import type { GovernanceMe } from '@/api/governance'

const api = vi.hoisted(() => ({
  getGovernanceMe: vi.fn(),
  listSections: vi.fn(),
}))

vi.mock('@/api/governance', () => ({ getGovernanceMe: api.getGovernanceMe }))
vi.mock('@/api/sections', () => ({ listSections: api.listSections }))
vi.mock('@/services/thirdPartyAuth', () => ({ startOAuthAuthorize: vi.fn() }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

const ControlledAuthLoginForm = defineComponent({
  name: 'ControlledAuthLoginForm',
  emits: ['success', 'close', 'notify', 'switch-register'],
  setup(_, { emit }) {
    const auth = useAuthStore()

    function completeLogin(): void {
      auth.applyAuthState({
        accessToken: 'new-account-token',
        user: { id: 7, username: 'new-account' },
      })
      emit('success')
    }

    return { completeLogin }
  },
  template: '<button type="button" data-testid="controlled-login-success" @click="completeLogin">完成受控登录</button>',
})

const PassivePanel = defineComponent({ template: '<div data-testid="sidebar-panel-stub"></div>' })
const PassiveAuthPanel = defineComponent({ template: '<div data-testid="auth-panel-stub"></div>' })

let wrapper: VueWrapper | undefined
let authStateChangedHandler = vi.fn<() => void>()

async function mountHarness() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/discover', name: 'discover', component: { template: '<div />' } },
      { path: '/sections', name: 'sections', component: { template: '<div />' } },
      { path: '/ranking', name: 'ranking', component: { template: '<div />' } },
      { path: '/feed', name: 'feed', component: { template: '<div />' } },
      { path: '/my/threads', name: 'my-threads', component: { template: '<div />' } },
      { path: '/my/posts', name: 'my-posts', component: { template: '<div />' } },
      { path: '/my/bookmarks', name: 'my-bookmarks', component: { template: '<div />' } },
      { path: '/settings', name: 'settings', component: { template: '<div />' } },
      { path: '/admin/reports', name: 'admin-reports', component: { template: '<div />' } },
      { path: '/admin/community', name: 'admin-community', component: { template: '<div />' } },
    ],
  })
  await router.push('/discover')
  await router.isReady()

  const onSuccess = vi.fn()
  const Harness = defineComponent({
    components: { AuthModal, SidebarLeft },
    setup() {
      const showAuth = ref(true)
      return { onSuccess, showAuth }
    },
    template: '<div><SidebarLeft /><AuthModal v-if="showAuth" @success="onSuccess" @close="showAuth = false" /></div>',
  })

  wrapper = mount(Harness, {
    global: {
      plugins: [pinia, router],
      stubs: {
        AuthLoginForm: ControlledAuthLoginForm,
        AuthQrLogin: PassiveAuthPanel,
        AuthRegisterForm: PassiveAuthPanel,
        AuthThirdPartyLogin: PassiveAuthPanel,
        PopularTagsPanel: PassivePanel,
        FollowingFeedPanel: PassivePanel,
        RecentVisitsPanel: PassivePanel,
      },
    },
  })
  await flushPromises()
  return { auth, onSuccess }
}

describe('AuthModal successful session generation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    sessionStorage.clear()
    api.listSections.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
    authStateChangedHandler = vi.fn<() => void>()
    window.addEventListener('auth-state-changed', authStateChangedHandler)
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    window.removeEventListener('auth-state-changed', authStateChangedHandler)
    localStorage.clear()
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('keeps the new account governance response visible after successful modal close', async () => {
    const governance = deferred<GovernanceMe>()
    api.getGovernanceMe.mockReturnValue(governance.promise)

    const { auth, onSuccess } = await mountHarness()
    expect(auth.isLoggedIn).toBe(false)
    expect(wrapper!.find('a[href="/admin/reports"]').exists()).toBe(false)
    expect(wrapper!.find('a[href="/admin/community"]').exists()).toBe(false)

    await wrapper!.get('[data-testid="controlled-login-success"]').trigger('click')
    await flushPromises()

    expect(authStateChangedHandler).toHaveBeenCalledOnce()
    expect(api.getGovernanceMe).toHaveBeenCalledOnce()
    expect(onSuccess).toHaveBeenCalledOnce()
    expect(wrapper!.findComponent(AuthModal).exists()).toBe(false)
    expect(auth.token).toBe('new-account-token')
    expect(localStorage.getItem('accessToken')).toBe('new-account-token')
    expect(wrapper!.find('a[href="/admin/reports"]').exists()).toBe(false)
    expect(wrapper!.find('a[href="/admin/community"]').exists()).toBe(false)

    governance.resolve({
      admin: true,
      moderatedSectionIds: [],
      postingRestricted: false,
      restrictedUntil: null,
    })
    await flushPromises()

    expect(auth.token).toBe('new-account-token')
    expect(wrapper!.get('a[href="/admin/reports"]').text()).toContain('举报审核')
    expect(wrapper!.get('a[href="/admin/community"]').text()).toContain('社区管理')
  })

  it('still invalidates the current generation for an explicit close button cancel', async () => {
    api.getGovernanceMe.mockResolvedValue({
      admin: false,
      moderatedSectionIds: [],
      postingRestricted: false,
      restrictedUntil: null,
    })

    const { auth } = await mountHarness()
    const previousGeneration = auth.getAuthGeneration()

    await wrapper!.get('button[aria-label="关闭"]').trigger('click')

    expect(auth.isAuthGenerationCurrent(previousGeneration)).toBe(false)
    expect(wrapper!.findComponent(AuthModal).exists()).toBe(false)
  })
})

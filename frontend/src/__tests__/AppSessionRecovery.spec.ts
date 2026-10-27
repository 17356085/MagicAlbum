import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '../App.vue'
import ThreadCreate from '@/pages/ThreadCreate.vue'
import productionRouter from '@/router'
import { useAuthStore } from '@/stores/auth'
import { persistAuthState } from '@/utils/authStorage'

const api = vi.hoisted(() => ({ profile: vi.fn(), create: vi.fn() }))
vi.mock('@/api/settings', () => ({ getMyProfile: api.profile }))
vi.mock('@/api/users', () => ({ getCurrentUser: vi.fn(), suggestUsers: vi.fn() }))
vi.mock('@/api/threadWrites', () => ({ createThread: api.create }))
vi.mock('@/api/sections', () => ({ listSections: vi.fn(async () => ({ items: [{ id: 3, name: '编程' }], total: 1, page: 1, size: 100 })) }))
vi.mock('@/composables/useLiveNotifications', () => ({ useLiveNotifications: vi.fn() }))
vi.mock('@/composables/useMediaProcessing', () => ({ useMediaProcessing: vi.fn() }))

let wrapper: VueWrapper | undefined

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function startEditor() {
  const pinia = createPinia()
  const auth = useAuthStore(pinia)
  const router = createRouter({ history: createMemoryHistory(), routes: productionRouter.options.routes })
  await router.push('/threads/new')
  await router.isReady()
  wrapper = mount(App, {
    global: {
      plugins: [pinia, router],
      stubs: {
        SidebarLeft: true, AiChat: true, MobileNavigation: true, MediaProcessingStatus: true,
        BrandBar: true, SearchBox: true, UserMenu: true, NotificationsMenu: true, AuthEntry: true, AuthModal: true,
        MarkdownTextareaEditor: {
          props: ['modelValue'], emits: ['update:modelValue'],
          template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
      },
    },
  })
  await flushPromises()
  return auth
}

describe('cold session recovery through App, header and editor', () => {
  it.each(['legacy', 'mismatched-bound'] as const)('confirms a %s cache before assigning drafts and preserves edits during header hydration', async kind => {
    const oldUser = { id: 1, username: 'alice', nickname: 'Alice', avatarUrl: '/alice.png' }
    if (kind === 'mismatched-bound') persistAuthState({ accessToken: 'account-a', user: oldUser })
    else localStorage.setItem('currentUser', JSON.stringify(oldUser))
    localStorage.setItem('accessToken', 'account-b')
    const oldDraft = JSON.stringify({ title: 'Alice private draft', content: 'A content', sectionId: '3', tags: '' })
    localStorage.setItem('thread-draft-v1:1', oldDraft)
    localStorage.setItem('thread-draft-v1:2', JSON.stringify({ title: 'Bob saved draft', content: 'B saved', sectionId: '3', tags: 'bob-tag' }))
    let confirm!: (value: object) => void
    api.profile.mockReturnValueOnce(new Promise(resolve => { confirm = resolve }))

    const auth = await startEditor()
    const instance = wrapper!.findComponent(ThreadCreate).vm.$.uid
    expect(auth.user).toBeNull()
    expect(wrapper!.text()).toContain('账号信息正在加载')
    expect((wrapper!.get('#thread-create-title').element as HTMLInputElement).value).toBe('')
    await wrapper!.get('#thread-create-title').setValue('Bob pending title')
    await wrapper!.get('textarea').setValue('Bob pending content')
    const events = vi.spyOn(window, 'dispatchEvent')

    confirm({ id: 2, username: 'bob', nickname: 'Bob', avatarUrl: '/bob.png' })
    await flushPromises()

    expect(auth.user).toMatchObject({ id: 2, username: 'bob' })
    expect(wrapper!.findComponent(ThreadCreate).vm.$.uid).toBe(instance)
    expect((wrapper!.get('#thread-create-title').element as HTMLInputElement).value).toBe('Bob pending title')
    expect((wrapper!.get('textarea').element as HTMLTextAreaElement).value).toBe('Bob pending content')
    expect((wrapper!.get('#thread-create-tags').element as HTMLInputElement).value).toBe('bob-tag')
    expect(localStorage.getItem('thread-draft-v1:1')).toBe(oldDraft)
    expect(JSON.parse(localStorage.getItem('thread-draft-v1:2')!)).toMatchObject({ title: 'Bob pending title', content: 'Bob pending content' })
    expect(api.profile).toHaveBeenCalledTimes(1)
    expect(api.create).not.toHaveBeenCalled()
    expect(events.mock.calls.some(([event]) => event.type === 'auth-state-changed')).toBe(false)
    expect(wrapper!.find('[data-testid="thread-create-identity-warning"]').exists()).toBe(false)
  })

  it('restores a paired complete identity and owner draft without another profile read', async () => {
    persistAuthState({ accessToken: 'account-b', user: { id: 2, username: 'bob', nickname: 'Bob', avatarUrl: '/bob.png' } })
    localStorage.setItem('thread-draft-v1:2', JSON.stringify({ title: 'Bob saved draft', content: 'Bob saved content', sectionId: '3', tags: '' }))
    const auth = await startEditor()
    expect(auth.user).toMatchObject({ id: 2, username: 'bob' })
    expect((wrapper!.get('#thread-create-title').element as HTMLInputElement).value).toBe('Bob saved draft')
    expect(api.profile).not.toHaveBeenCalled()
    expect(wrapper!.find('[data-testid="thread-create-identity-warning"]').exists()).toBe(false)
  })
})

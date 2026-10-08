import { flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Settings from '../Settings.vue'

const mocks = vi.hoisted(() => ({
  route: undefined as any,
  routerPush: vi.fn(),
  leaveGuard: undefined as any,
  updateGuard: undefined as any,
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.routerPush }),
  onBeforeRouteLeave: (guard: unknown) => { mocks.leaveGuard = guard },
  onBeforeRouteUpdate: (guard: unknown) => { mocks.updateGuard = guard },
}))

function childStub(testId: string) {
  return {
    emits: ['dirty-change'],
    template: `<div><button type="button" data-test="${testId}-dirty" @click="$emit('dirty-change', true)">mark dirty</button></div>`,
  }
}

function createWrapper() {
  return mount(Settings, {
    global: {
      stubs: {
        SettingsProfileTab: childStub('profile'),
        SettingsNotificationsTab: childStub('notifications'),
        SettingsConnectedTab: childStub('connected'),
        SettingsAccount: childStub('account'),
      },
    },
  })
}

describe('Settings navigation and draft protection', () => {
  beforeEach(() => {
    mocks.route = reactive({ path: '/settings', query: reactive<Record<string, unknown>>({}) })
    mocks.routerPush.mockReset()
    mocks.leaveGuard = undefined
    mocks.updateGuard = undefined
  })

  it('uses the route query as tab state and pushes tab history entries', async () => {
    const wrapper = createWrapper()

    expect(wrapper.find('button[aria-current="page"]').text()).toBe('资料设置')
    await wrapper.findAll('button')[1].trigger('click')

    expect(mocks.routerPush).toHaveBeenCalledWith({ path: '/settings', query: { tab: 'notifications' } })

    mocks.route.query.tab = 'account'
    await nextTick()
    expect(wrapper.find('button[aria-current="page"]').text()).toBe('账号信息')
    await flushPromises()
  })

  it('cancels tab switches and route leaves when a child reports a dirty draft', async () => {
    const wrapper = createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.findAll('button')[1].trigger('click')
    expect(confirmSpy).toHaveBeenCalled()
    expect(mocks.routerPush).not.toHaveBeenCalled()

    confirmSpy.mockReturnValue(true)
    await wrapper.findAll('button')[1].trigger('click')
    expect(mocks.routerPush).toHaveBeenCalledWith({ path: '/settings', query: { tab: 'notifications' } })

    expect(mocks.leaveGuard({ path: '/discover' }, { path: '/settings' })).toBe(true)
    confirmSpy.mockRestore()
  })

  it('guards browser back updates and beforeunload while a draft is dirty', async () => {
    const wrapper = createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    expect(mocks.updateGuard(
      { path: '/settings', query: { tab: 'account' } },
      { path: '/settings', query: { tab: 'profile' } },
    )).toBe(false)

    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)

    confirmSpy.mockRestore()
    wrapper.unmount()
  })
})

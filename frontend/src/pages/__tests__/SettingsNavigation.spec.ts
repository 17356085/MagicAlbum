import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Settings from '../Settings.vue'

const Root = defineComponent({ components: { RouterView }, template: '<RouterView />' })
const Placeholder = defineComponent({ template: '<div data-test="placeholder" />' })
const lateDirtyEvents = new Map<string, () => void>()

function childStub(testId: string) {
  return defineComponent({
    emits: ['dirty-change'],
    setup(_, { emit }) {
      lateDirtyEvents.set(testId, () => emit('dirty-change', true))
      return {}
    },
    template: `<div><button type="button" data-test="${testId}-dirty" @click="$emit('dirty-change', true)">mark dirty</button></div>`,
  })
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function waitForAfterEach(router: Router): Promise<unknown> {
  return new Promise((resolve) => {
    router.afterEach((_to, _from, failure) => resolve(failure))
  })
}

let router: Router
let wrapper: VueWrapper

async function createWrapper(path = '/settings') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings', name: 'settings', component: Settings },
      { path: '/discover', name: 'discover', component: Placeholder },
    ],
  })
  await router.push(path)
  await router.isReady()
  wrapper = mount(Root, {
    global: {
      plugins: [router],
      stubs: {
        SettingsProfileTab: childStub('profile'),
        SettingsNotificationsTab: childStub('notifications'),
        SettingsConnectedTab: childStub('connected'),
        SettingsAccount: childStub('account'),
      },
    },
  })
  await flushPromises()
  return { router, wrapper }
}

function currentTab(): string {
  return wrapper.find('button[aria-current="page"]').text()
}

function beforeUnloadEvent(): Event {
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  return event
}

function latestPush(pushSpy: { mock: { results: unknown[] } }): Promise<unknown> {
  const latestResult = pushSpy.mock.results[pushSpy.mock.results.length - 1] as { value?: unknown } | undefined
  const pushResult = latestResult?.value
  if (!(pushResult instanceof Promise)) throw new Error('router.push was not called')
  return pushResult
}

describe('Settings navigation and draft protection', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    lateDirtyEvents.clear()
  })

  afterEach(() => {
    wrapper?.unmount()
  })

  it('uses the route query as tab state, pushes history entries, and supports back navigation', async () => {
    await createWrapper()
    const pushSpy = vi.spyOn(router, 'push')

    expect(currentTab()).toBe('资料设置')
    await wrapper.findAll('.settings-tab')[1].trigger('click')
    await latestPush(pushSpy)
    await nextTick()

    expect(pushSpy).toHaveBeenCalledWith({ path: '/settings', query: { tab: 'notifications' } })
    expect(router.currentRoute.value.query.tab).toBe('notifications')
    expect(currentTab()).toBe('通知管理')

    const backFinished = waitForAfterEach(router)
    router.back()
    await backFinished
    await nextTick()
    expect(router.currentRoute.value.query.tab).toBeUndefined()
    expect(currentTab()).toBe('资料设置')
  })

  it('cancels tab switches and route leaves while a child reports a dirty draft', async () => {
    await createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await wrapper.findAll('.settings-tab')[1].trigger('click')
    await flushPromises()
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.fullPath).toBe('/settings')
    expect(beforeUnloadEvent().defaultPrevented).toBe(true)

    await router.push('/discover')
    expect(confirmSpy).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.path).toBe('/settings')
  })

  it('guards browser-back updates and beforeunload while a draft is dirty', async () => {
    await createWrapper()
    await router.push({ path: '/settings', query: { tab: 'account' } })
    await nextTick()
    await wrapper.get('[data-test="account-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const backFinished = waitForAfterEach(router)
    router.back()
    await backFinished
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query.tab).toBe('account')
    expect(beforeUnloadEvent().defaultPrevented).toBe(true)
  })

  it('keeps the draft protected when a confirmed navigation is cancelled by beforeResolve', async () => {
    await createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const beforeResolveStarted = deferred<void>()
    const release = deferred<void>()
    const removeBeforeResolve = router.beforeResolve(async () => {
      beforeResolveStarted.resolve()
      await release.promise
      return false
    })
    const pushSpy = vi.spyOn(router, 'push')
    await wrapper.findAll('.settings-tab')[1].trigger('click')
    await beforeResolveStarted.promise
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    const pushResult = latestPush(pushSpy)
    release.resolve()
    await pushResult
    await flushPromises()
    await nextTick()
    removeBeforeResolve()

    expect(router.currentRoute.value.query.tab).toBeUndefined()
    expect(beforeUnloadEvent().defaultPrevented).toBe(true)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    confirmSpy.mockReturnValue(false)
    await router.push('/discover')
    expect(confirmSpy).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.path).toBe('/settings')
  })

  it('reports rejected navigation, keeps the draft, and allows a retry', async () => {
    await createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const removeBeforeResolve = router.beforeResolve(() => {
      throw new Error('navigation exploded')
    })
    const pushSpy = vi.spyOn(router, 'push')
    await wrapper.findAll('.settings-tab')[1].trigger('click')
    const rejectedPush = latestPush(pushSpy)
    await expect(rejectedPush).rejects.toThrow('navigation exploded')
    await flushPromises()
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query.tab).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('页面切换失败，草稿仍保留')
    expect(beforeUnloadEvent().defaultPrevented).toBe(true)

    removeBeforeResolve()
    await wrapper.findAll('.settings-tab')[1].trigger('click')
    await latestPush(pushSpy)
    await nextTick()
    await flushPromises()
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.query.tab).toBe('notifications')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(beforeUnloadEvent().defaultPrevented).toBe(false)
  })

  it('confirms once on a successful switch, clears only the leaving draft, and tracks the new page', async () => {
    await createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')
    const staleProfileDirtyEvent = lateDirtyEvents.get('profile')
    expect(staleProfileDirtyEvent).toEqual(expect.any(Function))

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const pushSpy = vi.spyOn(router, 'push')
    await wrapper.findAll('.settings-tab')[1].trigger('click')
    await latestPush(pushSpy)
    await nextTick()
    await flushPromises()
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(beforeUnloadEvent().defaultPrevented).toBe(false)
    staleProfileDirtyEvent?.()
    await nextTick()
    expect(beforeUnloadEvent().defaultPrevented).toBe(false)

    await wrapper.get('[data-test="notifications-dirty"]').trigger('click')
    expect(beforeUnloadEvent().defaultPrevented).toBe(true)

    await wrapper.findAll('.settings-tab')[0].trigger('click')
    await latestPush(pushSpy)
    await nextTick()
    await flushPromises()
    await nextTick()

    expect(confirmSpy).toHaveBeenCalledTimes(2)
    expect(beforeUnloadEvent().defaultPrevented).toBe(false)
  })

  it('coalesces repeated button clicks while a navigation guard is pending', async () => {
    await createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const beforeResolveStarted = deferred<void>()
    const release = deferred<void>()
    const removeBeforeResolve = router.beforeResolve(async () => {
      beforeResolveStarted.resolve()
      await release.promise
      return true
    })
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.findAll('.settings-tab')[1].trigger('click')
    await beforeResolveStarted.promise
    await nextTick()
    expect(pushSpy).toHaveBeenCalledTimes(1)
    expect(confirmSpy).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.settings-tab').every((button) => button.attributes('disabled') !== undefined)).toBe(true)

    await wrapper.findAll('.settings-tab')[1].trigger('click')
    expect(pushSpy).toHaveBeenCalledTimes(1)
    expect(confirmSpy).toHaveBeenCalledTimes(1)

    const pushResult = latestPush(pushSpy)
    release.resolve()
    await pushResult
    await flushPromises()
    await nextTick()
    removeBeforeResolve()

    expect(router.currentRoute.value.query.tab).toBe('notifications')
    expect(wrapper.findAll('.settings-tab').every((button) => button.attributes('disabled') === undefined)).toBe(true)
  })

  it('does not prompt or clear a draft for an unrelated query update', async () => {
    await createWrapper()
    await wrapper.get('[data-test="profile-dirty"]').trigger('click')

    const confirmSpy = vi.spyOn(window, 'confirm')
    await router.push({ path: '/settings', query: { tab: 'profile', source: 'dashboard' } })
    await nextTick()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(router.currentRoute.value.query.source).toBe('dashboard')
    expect(beforeUnloadEvent().defaultPrevented).toBe(true)
  })
})

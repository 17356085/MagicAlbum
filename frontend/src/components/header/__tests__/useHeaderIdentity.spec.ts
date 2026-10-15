import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref, type Ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useHeaderIdentity } from '../useHeaderIdentity'
import type { User, UserProfile } from '@/types'

const mocks = vi.hoisted(() => ({
  getMyProfile: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getMyProfile: mocks.getMyProfile,
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function staleProfile(): UserProfile & Partial<User> {
  return {
    id: 1,
    userId: 1,
    username: 'first-user',
    nickname: '过期资料',
    bio: '',
    homepageUrl: '',
    location: '',
    avatarUrl: '/stale-avatar.png',
  }
}

function createHarness(initialUser: User = { id: 1, username: 'first-user' }) {
  const isLoggedIn = ref(true)
  const user = ref<User | null>(initialUser)
  const updateCurrentUser = vi.fn((patch: Partial<User>) => {
    if (user.value) user.value = { ...user.value, ...patch }
  })
  let identity!: ReturnType<typeof useHeaderIdentity>
  const Host = defineComponent({
    setup() {
      identity = useHeaderIdentity({
        isLoggedIn: isLoggedIn as Readonly<Ref<boolean>>,
        user: user as Readonly<Ref<User | null>>,
        updateCurrentUser,
      })
      return () => h('div')
    },
  })
  const wrapper = mount(Host)
  return { wrapper, isLoggedIn, user, updateCurrentUser, identity }
}

describe('useHeaderIdentity', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('ignores a profile response after the account logs out', async () => {
    const response = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValue(response.promise)
    const harness = createHarness()

    const refresh = harness.identity.refreshMyProfile()
    harness.isLoggedIn.value = false
    harness.user.value = null
    response.resolve(staleProfile())
    await refresh
    await flushPromises()

    expect(harness.updateCurrentUser).not.toHaveBeenCalled()
    expect(harness.identity.avatarUrl.value).toBe('')
    harness.wrapper.unmount()
  })

  it('ignores a profile response after switching to another account', async () => {
    const response = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValue(response.promise)
    const harness = createHarness()

    const refresh = harness.identity.refreshMyProfile()
    harness.user.value = { id: 2, username: 'second-user' }
    response.resolve(staleProfile())
    await refresh
    await flushPromises()

    expect(harness.updateCurrentUser).not.toHaveBeenCalled()
    expect(harness.user.value).toEqual({ id: 2, username: 'second-user' })
    harness.wrapper.unmount()
  })

  it('invalidates a profile request when the host unmounts', async () => {
    const response = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValue(response.promise)
    const harness = createHarness()

    const refresh = harness.identity.refreshMyProfile()
    harness.wrapper.unmount()
    response.resolve(staleProfile())
    await refresh

    expect(harness.updateCurrentUser).not.toHaveBeenCalled()
  })
})

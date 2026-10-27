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
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
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

function createHarness(initialUser: User | null = { id: 1, username: 'first-user' }) {
  const isLoggedIn = ref(true)
  const token = ref('token-a')
  const user = ref<User | null>(initialUser)
  const updateCurrentUser = vi.fn((patch: Partial<User>) => {
    if (user.value) user.value = { ...user.value, ...patch }
  })
  const hydrateCurrentUser = vi.fn((patch: Partial<User>, expectedToken: string) => {
    if (expectedToken !== token.value) return false
    const nextUser = { ...(user.value || {}), ...patch }
    if (!nextUser.id || !nextUser.username) return false
    user.value = nextUser as User
    return true
  })
  let identity!: ReturnType<typeof useHeaderIdentity>
  const Host = defineComponent({
    setup() {
      identity = useHeaderIdentity({
        isLoggedIn: isLoggedIn as Readonly<Ref<boolean>>,
        token: token as Readonly<Ref<string>>,
        user: user as Readonly<Ref<User | null>>,
        updateCurrentUser,
        hydrateCurrentUser,
      })
      return () => h('div')
    },
  })
  const wrapper = mount(Host)
  return { wrapper, isLoggedIn, token, user, updateCurrentUser, hydrateCurrentUser, identity }
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
    expect(harness.hydrateCurrentUser).not.toHaveBeenCalled()
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
    expect(harness.hydrateCurrentUser).not.toHaveBeenCalled()
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
    expect(harness.hydrateCurrentUser).not.toHaveBeenCalled()
  })

  it('ignores a response from token A after switching to token B with the same cached id', async () => {
    const oldResponse = deferred<UserProfile & Partial<User>>()
    const newResponse = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const harness = createHarness({ id: 1, username: 'same-user' })

    const oldRefresh = harness.identity.refreshMyProfile()
    harness.token.value = 'token-b'
    const newRefresh = harness.identity.refreshMyProfile()

    oldResponse.resolve(staleProfile())
    await oldRefresh
    expect(harness.hydrateCurrentUser).not.toHaveBeenCalled()

    newResponse.resolve({ ...staleProfile(), username: 'second-token-user', nickname: '新用户' })
    await newRefresh
    expect(harness.hydrateCurrentUser).toHaveBeenCalledTimes(1)
    expect(harness.hydrateCurrentUser).toHaveBeenLastCalledWith(
      expect.objectContaining({ username: 'second-token-user' }),
      'token-b',
    )
    harness.wrapper.unmount()
  })

  it('does not let token A hydrate token B when both sessions start without a user', async () => {
    const oldResponse = deferred<UserProfile & Partial<User>>()
    const newResponse = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const harness = createHarness(null)

    const oldRefresh = harness.identity.refreshMyProfile()
    harness.token.value = 'token-b'
    const newRefresh = harness.identity.refreshMyProfile()

    oldResponse.resolve(staleProfile())
    await oldRefresh
    expect(harness.user.value).toBeNull()

    newResponse.resolve({ ...staleProfile(), id: 2, userId: 2, username: 'second-token-user', nickname: '新用户' })
    await newRefresh
    expect(harness.user.value).toEqual(expect.objectContaining({ id: 2, username: 'second-token-user' }))
    harness.wrapper.unmount()
  })

  it('hydrates a token-only session into a stable server identity', async () => {
    mocks.getMyProfile.mockResolvedValue({
      id: 7,
      userId: 7,
      username: 'server-user',
      nickname: '服务器用户',
      avatarUrl: '/server.png',
      bio: '',
      homepageUrl: '',
      location: '',
    })
    const harness = createHarness(null)

    await harness.identity.refreshMyProfile()

    expect(harness.user.value).toEqual(expect.objectContaining({
      id: 7,
      userId: 7,
      username: 'server-user',
      nickname: '服务器用户',
      avatarUrl: '/server.png',
    }))
    expect(harness.identity.profileHydrated.value).toBe(true)
    await harness.identity.refreshMyProfile()
    expect(mocks.getMyProfile).toHaveBeenCalledTimes(1)
    harness.wrapper.unmount()
  })

  it('coalesces concurrent refreshes for the same token and identity', async () => {
    const response = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValue(response.promise)
    const harness = createHarness()

    const first = harness.identity.refreshMyProfile()
    const second = harness.identity.refreshMyProfile()
    expect(second).toBe(first)
    expect(mocks.getMyProfile).toHaveBeenCalledTimes(1)

    response.resolve(staleProfile())
    await first
    harness.wrapper.unmount()
  })

  it('keeps a newer pending request after an older request finally settles', async () => {
    const oldResponse = deferred<UserProfile & Partial<User>>()
    const newResponse = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const harness = createHarness()

    const oldRefresh = harness.identity.refreshMyProfile()
    harness.token.value = 'token-b'
    const newRefresh = harness.identity.refreshMyProfile()
    oldResponse.resolve(staleProfile())
    await oldRefresh

    const coalesced = harness.identity.refreshMyProfile()
    expect(coalesced).toBe(newRefresh)
    expect(mocks.getMyProfile).toHaveBeenCalledTimes(2)

    newResponse.resolve({ ...staleProfile(), username: 'new-user' })
    await newRefresh
    harness.wrapper.unmount()
  })

  it('does not let a stale failure affect a newer request', async () => {
    const oldResponse = deferred<UserProfile & Partial<User>>()
    const newResponse = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const harness = createHarness()

    const oldRefresh = harness.identity.refreshMyProfile()
    harness.token.value = 'token-b'
    const newRefresh = harness.identity.refreshMyProfile()
    oldResponse.reject(new Error('old request failed'))
    await oldRefresh
    expect(mocks.getMyProfile).toHaveBeenCalledTimes(2)

    newResponse.resolve({ ...staleProfile(), username: 'new-user' })
    await newRefresh
    expect(harness.user.value?.username).toBe('new-user')
    harness.wrapper.unmount()
  })

  it('rejects profile events from a different or unidentified owner', () => {
    const harness = createHarness({ id: 2, username: 'bob', nickname: 'Bob', avatarUrl: '/bob.png' })
    try {
      harness.identity.applyUserIdentity()
      for (const detail of [{ id: 1, nickname: 'Alice', avatarUrl: '/alice.png' }, { nickname: 'Unattributed', avatarUrl: '/unknown.png' }]) {
        harness.identity.onProfileUpdated(new CustomEvent('profile-updated', { detail }))
      }
      expect(harness.identity.displayName.value).toBe('Bob')
      expect(harness.identity.avatarUrl.value).toBe('/bob.png')
      expect(harness.updateCurrentUser).not.toHaveBeenCalled()
    } finally { harness.wrapper.unmount() }
  })

  it('does not let an older profile GET undo a confirmed own-profile save event', async () => {
    const response = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValueOnce(response.promise)
    const harness = createHarness()
    try {
      const pending = harness.identity.refreshMyProfile()
      harness.identity.onProfileUpdated(new CustomEvent('profile-updated', { detail: { id: 1, nickname: 'Saved nickname', avatarUrl: '/saved.png' } }))
      response.resolve(staleProfile())
      await pending
      expect(harness.identity.displayName.value).toBe('Saved nickname')
      expect(harness.identity.avatarUrl.value).toBe('/saved.png')
      expect(harness.hydrateCurrentUser).not.toHaveBeenCalled()
    } finally { harness.wrapper.unmount() }
  })

  it('applies an own-profile avatar removal with username fallback', () => {
    const harness = createHarness({ id: 2, username: 'bob', nickname: 'Bob', avatarUrl: '/bob.png' })
    try {
      harness.identity.onProfileUpdated(new CustomEvent('profile-updated', { detail: { id: 2, nickname: '', avatarUrl: '' } }))
      expect(harness.identity.avatarUrl.value).toBe('')
      expect(harness.identity.displayName.value).toBe('bob')
    } finally { harness.wrapper.unmount() }
  })

  it('updates a saved username without erasing the existing nickname or avatar', async () => {
    const harness = createHarness({ id: 1, username: 'alice', nickname: 'Alice', avatarUrl: '/a.png' })
    try {
      await harness.identity.refreshMyProfile()
      harness.identity.onProfileUpdated(new CustomEvent('profile-updated', { detail: { id: 1, username: 'saved_alice' } }))
      expect(harness.user.value).toMatchObject({ username: 'saved_alice', nickname: 'Alice', avatarUrl: '/a.png' })
      expect(harness.identity.displayName.value).toBe('Alice')
      expect(mocks.getMyProfile).not.toHaveBeenCalled()
    } finally { harness.wrapper.unmount() }
  })

  it('replaces an older profile read after a username-only save while retaining hydration', async () => {
    const oldResponse = deferred<UserProfile & Partial<User>>()
    const newResponse = deferred<UserProfile & Partial<User>>()
    mocks.getMyProfile.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const harness = createHarness()
    try {
      const oldRead = harness.identity.refreshMyProfile()
      harness.identity.onProfileUpdated(new CustomEvent('profile-updated', { detail: { id: 1, username: 'saved_alice' } }))
      expect(harness.identity.displayName.value).toBe('saved_alice')
      expect(mocks.getMyProfile).toHaveBeenCalledTimes(2)
      oldResponse.resolve(staleProfile())
      await oldRead
      expect(harness.user.value?.username).toBe('saved_alice')
      expect(harness.identity.profileHydrated.value).toBe(false)
      newResponse.resolve({ ...staleProfile(), username: 'saved_alice', nickname: 'Fresh Alice' })
      await harness.identity.refreshMyProfile()
      expect(harness.identity.displayName.value).toBe('Fresh Alice')
      expect(harness.user.value?.username).toBe('saved_alice')
    } finally { harness.wrapper.unmount() }
  })
})

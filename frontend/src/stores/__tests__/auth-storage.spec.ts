import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../auth'
import { getStoredCurrentUser, persistAuthState } from '@/utils/authStorage'

const mocks = vi.hoisted(() => ({ getCurrentUser: vi.fn() }))
vi.mock('@/api/users', () => ({ getCurrentUser: mocks.getCurrentUser }))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(next => { resolve = next })
  return { promise, resolve }
}

describe('auth storage ownership', () => {
  afterEach(() => vi.restoreAllMocks())
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.resetAllMocks()
    setActivePinia(createPinia())
  })

  it('does not restore an unbound legacy identity during cold startup', () => {
    localStorage.setItem('accessToken', 'account-b')
    localStorage.setItem('currentUser', JSON.stringify({ id: 1, username: 'alice', nickname: 'Alice', avatarUrl: '/a.png' }))
    localStorage.setItem('thread-draft-v1:1', 'private draft')
    const auth = useAuthStore()
    expect(auth.token).toBe('account-b')
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('thread-draft-v1:1')).toBe('private draft')
  })

  it('rejects a previously paired identity after only the persisted token changes', () => {
    persistAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    localStorage.setItem('accessToken', 'account-b')
    const auth = useAuthStore()
    expect(auth.token).toBe('account-b')
    expect(auth.user).toBeNull()
  })

  it('restores a coherently paired cached identity with its existing fields', () => {
    persistAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice', nickname: 'Alice', avatarUrl: '/a.png' } })
    const auth = useAuthStore()
    expect(auth.token).toBe('account-a')
    expect(auth.user).toMatchObject({ id: 1, username: 'alice', nickname: 'Alice', avatarUrl: '/a.png' })
  })

  it('does not restore another account if the token changes between startup reads', () => {
    persistAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    const realGetItem = Storage.prototype.getItem
    let reads = 0
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (this: Storage, key: string) {
      if (this === localStorage && key === 'accessToken' && ++reads > 1) return 'account-b'
      return realGetItem.call(this, key)
    })
    const auth = useAuthStore()
    expect(auth.token).toBe('account-a')
    expect(auth.user).toBeNull()
    vi.restoreAllMocks()
  })

  it('refuses metadata updates after an external token switch without reverting credentials', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    localStorage.setItem('accessToken', 'account-b')
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    auth.updateCurrentUser({ nickname: 'late Alice' })
    expect(localStorage.getItem('accessToken')).toBe('account-b')
    expect(auth.user?.nickname).toBeUndefined()
    expect(writes).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })

  it('updates current metadata without rewriting its bearer or changing the session', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    const generation = auth.getAuthGeneration()
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    auth.updateCurrentUser({ nickname: 'new Alice' })
    expect(auth.user?.nickname).toBe('new Alice')
    expect(getStoredCurrentUser()).toMatchObject({ id: 1, nickname: 'new Alice' })
    expect(writes.mock.calls.some(([key]) => key === 'accessToken')).toBe(false)
    expect(auth.getAuthGeneration()).toBe(generation)
    vi.restoreAllMocks()
  })

  it('does not let a metadata patch reassign the current owner ID', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    auth.updateCurrentUser({ id: 2, username: 'bob' })
    expect(auth.user).toMatchObject({ id: 1, username: 'alice' })
    expect(getStoredCurrentUser()).toMatchObject({ id: 1, username: 'alice' })
  })

  it('hydrates confirmed identity without rewriting the bearer', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: null })
    const writes = vi.spyOn(Storage.prototype, 'setItem')
    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'account-a')).toBe(true)
    expect(getStoredCurrentUser()).toMatchObject({ id: 1, username: 'alice' })
    expect(writes.mock.calls.some(([key]) => key === 'accessToken')).toBe(false)
    vi.restoreAllMocks()
  })

  it('adopts an external token without attributing cached user metadata or rewriting storage', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    const generation = auth.getAuthGeneration()
    localStorage.setItem('accessToken', 'account-b')
    // Separate storage writes may leave old metadata beside a new token.
    const rawUser = localStorage.getItem('currentUser')
    localStorage.setItem('thread-draft-v1:1', 'draft-a')

    expect(auth.syncAuthFromStorage()).toBe(true)
    expect(auth.token).toBe('account-b')
    expect(auth.user).toBeNull()
    expect(auth.getAuthGeneration()).toBeGreaterThan(generation)
    expect(localStorage.getItem('currentUser')).toBe(rawUser)
    expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft-a')
    const currentGeneration = auth.getAuthGeneration()
    expect(auth.syncAuthFromStorage()).toBe(false)
    expect(auth.getAuthGeneration()).toBe(currentGeneration)
  })

  it('adopts external logout while keeping account drafts', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    localStorage.setItem('thread-draft-v1:1', 'draft-a')
    localStorage.removeItem('accessToken')
    expect(auth.syncAuthFromStorage()).toBe(true)
    expect(auth.isLoggedIn).toBe(false)
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('thread-draft-v1:1')).toBe('draft-a')
  })

  it('hydrates a token-only session without beginning a new auth session', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: null })
    const generation = auth.getAuthGeneration()
    const signal = vi.fn()
    window.addEventListener('auth-state-changed', signal)
    try {
      expect(auth.hydrateCurrentUser({ id: 1, username: 'alice', nickname: 'Alice' }, 'account-a')).toBe(true)
      expect(auth.user).toMatchObject({ id: 1, username: 'alice', nickname: 'Alice' })
      expect(JSON.parse(localStorage.getItem('currentUser')!)).toMatchObject({ id: 1, username: 'alice' })
      expect(auth.getAuthGeneration()).toBe(generation)
      expect(signal).not.toHaveBeenCalled()
    } finally { window.removeEventListener('auth-state-changed', signal) }
  })

  it('rejects profile hydration when either memory or persisted ownership changed', () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: null })
    localStorage.setItem('accessToken', 'account-b')
    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'account-a')).toBe(false)
    expect(auth.user).toBeNull()
    auth.syncAuthFromStorage()
    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'account-a')).toBe(false)
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('accessToken')).toBe('account-b')
  })

  it.each([{ nickname: 'missing identity' }, { id: -1, username: 'invalid' }, { id: 1, username: ' ' }])(
    'does not create a draft owner from an incomplete server identity %j', patch => {
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'account-a', user: null })
      expect(auth.hydrateCurrentUser(patch, 'account-a')).toBe(false)
      expect(auth.user).toBeNull()
    },
  )

  it('refreshes identity using its captured bearer and ignores a stale completion', async () => {
    const pending = deferred<{ id: number; username: string }>()
    mocks.getCurrentUser.mockReturnValueOnce(pending.promise)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: null })
    const operation = auth.refreshCurrentUser()
    expect(mocks.getCurrentUser).toHaveBeenCalledWith('account-a')
    auth.applyAuthState({ accessToken: 'account-b', user: { id: 2, username: 'bob' } })
    pending.resolve({ id: 1, username: 'alice' })
    expect(await operation).toBe(false)
    expect(auth.user).toMatchObject({ id: 2, username: 'bob' })
    expect(localStorage.getItem('accessToken')).toBe('account-b')
  })

  it('coalesces simultaneous identity retries for the same session', async () => {
    const pending = deferred<{ id: number; username: string }>()
    mocks.getCurrentUser.mockReturnValueOnce(pending.promise)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: null })
    const first = auth.refreshCurrentUser()
    const second = auth.refreshCurrentUser()
    expect(mocks.getCurrentUser).toHaveBeenCalledTimes(1)
    pending.resolve({ id: 1, username: 'alice' })
    expect(await first).toBe(true)
    expect(await second).toBe(true)
    expect(auth.user).toMatchObject({ id: 1, username: 'alice' })
  })

  it('does not start an identity read from a stale in-memory session before the storage event', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: null })
    localStorage.setItem('accessToken', 'account-b')
    expect(await auth.refreshCurrentUser()).toBe(false)
    expect(mocks.getCurrentUser).not.toHaveBeenCalled()
    expect(localStorage.getItem('accessToken')).toBe('account-b')
  })

  it('does not let an older identity read undo confirmed metadata and allows a fresh read', async () => {
    const oldResponse = deferred<{ id: number; username: string }>()
    const newResponse = deferred<{ id: number; username: string }>()
    mocks.getCurrentUser.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    const generation = auth.getAuthGeneration()
    const oldRead = auth.refreshCurrentUser()
    auth.updateCurrentUser({ username: 'saved_alice' })
    const freshRead = auth.refreshCurrentUser()
    expect(mocks.getCurrentUser).toHaveBeenCalledTimes(2)

    oldResponse.resolve({ id: 1, username: 'alice' })
    expect(await oldRead).toBe(false)
    expect(auth.user?.username).toBe('saved_alice')
    expect(getStoredCurrentUser()?.username).toBe('saved_alice')
    expect(auth.getAuthGeneration()).toBe(generation)

    newResponse.resolve({ id: 1, username: 'saved_alice' })
    expect(await freshRead).toBe(true)
    expect(auth.user?.username).toBe('saved_alice')
  })

  it('keeps a pending identity read when a metadata patch changes nothing', async () => {
    const response = deferred<{ id: number; username: string }>()
    mocks.getCurrentUser.mockReturnValueOnce(response.promise)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'alice' } })
    const operation = auth.refreshCurrentUser()
    auth.updateCurrentUser({ username: 'alice' })
    const concurrent = auth.refreshCurrentUser()
    expect(mocks.getCurrentUser).toHaveBeenCalledTimes(1)
    response.resolve({ id: 1, username: 'alice' })
    expect(await operation).toBe(true)
    expect(await concurrent).toBe(true)
  })
})

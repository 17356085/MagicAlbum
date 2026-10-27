import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '@/stores/auth'
import ThreadDetailHeader from '../ThreadDetailHeader.vue'
import type { Thread, UserProfile } from '@/types'
import { InteractionResponseError } from '@/api/interactionState'

const mocks = vi.hoisted(() => ({
  getUserProfile: vi.fn(),
  followUser: vi.fn(),
  unfollowUser: vi.fn(),
}))

vi.mock('@/api/users', () => ({
  getUserProfile: mocks.getUserProfile,
  followUser: mocks.followUser,
  unfollowUser: mocks.unfollowUser,
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

function profile(followedByMe = false): UserProfile {
  return {
    id: 'author-a',
    username: 'author-a',
    nickname: '作者 A',
    bio: '',
    homepageUrl: '',
    location: '',
    avatarUrl: '/author-a.png',
    followedByMe,
  }
}

function thread(authorId: string = 'author-a', title = '**帖子标题**'): Thread {
  return {
    id: 'thread-1',
    title,
    authorId,
    authorUsername: authorId,
    authorNickname: authorId === 'author-a' ? '作者 A' : '作者 B',
    authorAvatarUrl: authorId === 'author-a' ? '/author-a.png' : '/author-b.png',
    sectionId: 'section-1',
    sectionName: '讨论区',
    createdAt: '2026-10-24T00:00:00Z',
    tags: ['标签'],
  }
}

const wrappers: VueWrapper[] = []

function loginAs(id: string, token = `token-${id}`): void {
  useAuthStore().loginWithAuthResponse({ accessToken: token, user: { id, username: `viewer-${id}` } })
}

function createWrapper(initialThread = thread()): VueWrapper {
  const wrapper = mount(ThreadDetailHeader, {
    props: { thread: initialThread, displayAuthorAvatarUrl: initialThread.authorAvatarUrl },
    global: {
      stubs: {
        RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

function followButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((button) => ['关注', '已关注', '处理中...', '加载中...'].includes(button.text()))!
}

describe('ThreadDetailHeader author follow ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    mocks.getUserProfile.mockReset().mockResolvedValue(profile(false))
    mocks.followUser.mockReset()
    mocks.unfollowUser.mockReset()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    localStorage.clear()
  })

  it('preserves title, public author metadata, avatar and tags while loading follow state', async () => {
    loginAs('viewer-a')
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.find('h1').html()).toContain('<strong>帖子标题</strong>')
    expect(wrapper.text()).toContain('作者 A')
    expect(wrapper.text()).toContain('讨论区')
    expect(wrapper.text()).toContain('#标签')
    expect(wrapper.find('img').attributes('src')).toBe('/author-a.png')
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)
  })

  it('does not request personalized profile state while anonymous and still renders the public author', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    expect(mocks.getUserProfile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('作者 A')
    await followButton(wrapper).trigger('click')
    expect(wrapper.text()).toContain('请先登录')
  })

  it('accepts same-token unknown-to-known hydration with one coalesced read', async () => {
    loginAs('1', 'token-hydrate')
    useAuthStore().user = null
    localStorage.removeItem('currentUserSession.v1')
    localStorage.removeItem('currentUser')
    const read = deferred<UserProfile>()
    mocks.getUserProfile.mockReset().mockReturnValue(read.promise)
    const wrapper = createWrapper()
    await flushPromises()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)

    useAuthStore().hydrateCurrentUser({ id: '1', username: 'viewer-a' }, 'token-hydrate')
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(1)
    read.resolve(profile(true))
    await flushPromises()
    expect(wrapper.text()).toContain('关注')
    expect(wrapper.text()).toContain('已关注')
  })

  it('ignores author A reads and writes after switching the displayed author to B', async () => {
    loginAs('viewer-a')
    const oldRead = deferred<UserProfile>()
    mocks.getUserProfile.mockReturnValueOnce(oldRead.promise).mockResolvedValueOnce(profile(false))
    const wrapper = createWrapper()
    await wrapper.setProps({ thread: thread('author-b') })
    oldRead.resolve({ ...profile(true), id: 'author-a' })
    await flushPromises()

    expect(wrapper.text()).toContain('作者 B')
    expect(wrapper.text()).not.toContain('已关注')
    expect(mocks.getUserProfile).toHaveBeenCalledWith('author-b')
  })

  it('does not reuse an invalidated author A read after a synchronous A to B to A switch', async () => {
    loginAs('viewer-a')
    const authorAFirst = deferred<UserProfile>()
    const authorB = deferred<UserProfile>()
    const authorASecond = deferred<UserProfile>()
    mocks.getUserProfile.mockReset()
      .mockReturnValueOnce(authorAFirst.promise)
      .mockReturnValueOnce(authorB.promise)
      .mockReturnValueOnce(authorASecond.promise)
    const wrapper = createWrapper()

    await wrapper.setProps({ thread: thread('author-b') })
    await wrapper.setProps({ thread: thread('author-a') })
    await flushPromises()
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(3)

    authorAFirst.resolve(profile(true))
    authorB.resolve(profile(true))
    authorASecond.resolve(profile(false))
    await flushPromises()
    expect(wrapper.text()).not.toContain('已关注')
  })

  it('invalidates A to B to A account responses even when the first account returns', async () => {
    const accountARead = deferred<UserProfile>()
    const accountBRead = deferred<UserProfile>()
    const accountAReread = deferred<UserProfile>()
    loginAs('viewer-a', 'token-a')
    mocks.getUserProfile.mockReset()
      .mockReturnValueOnce(accountARead.promise)
      .mockReturnValueOnce(accountBRead.promise)
      .mockReturnValueOnce(accountAReread.promise)
    const wrapper = createWrapper()

    loginAs('viewer-b', 'token-b')
    loginAs('viewer-a', 'token-a')
    await flushPromises()
    accountARead.resolve(profile(true))
    accountBRead.resolve(profile(false))
    await flushPromises()
    expect(wrapper.text()).not.toContain('已关注')

    accountAReread.resolve(profile(false))
    await flushPromises()
    expect(wrapper.text()).toContain('关注')
    expect(wrapper.text()).not.toContain('已关注')
  })

  it('invalidates a direct same-tick token A to B to A switch without relying on auth events', async () => {
    loginAs('1', 'token-a')
    const accountAFirst = deferred<UserProfile>()
    const accountASecond = deferred<UserProfile>()
    mocks.getUserProfile.mockReset()
      .mockReturnValueOnce(accountAFirst.promise)
      .mockReturnValueOnce(accountASecond.promise)
    const wrapper = createWrapper()
    const auth = useAuthStore()
    auth.token = 'token-b'
    auth.user = { id: '2', username: 'viewer-b' }
    auth.token = 'token-a'
    auth.user = { id: '1', username: 'viewer-a' }
    await flushPromises()

    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
    accountAFirst.resolve(profile(true))
    accountASecond.resolve(profile(false))
    await flushPromises()
    expect(wrapper.text()).not.toContain('已关注')
  })

  it('reports a failed read and retries without converting the state to false', async () => {
    loginAs('viewer-a')
    mocks.getUserProfile.mockReset().mockRejectedValueOnce(new Error('profile unavailable')).mockResolvedValueOnce(profile(true))
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('profile unavailable')
    expect(wrapper.text()).toContain('重试')
    await wrapper.findAll('button').find((button) => button.text() === '重试')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已关注')
    expect(wrapper.text()).not.toContain('profile unavailable')
    expect(mocks.getUserProfile).toHaveBeenCalledTimes(2)
  })

  it('performs one write for duplicate clicks and preserves valid state for a retry after failure', async () => {
    loginAs('viewer-a')
    const write = deferred<{ following: boolean }>()
    mocks.followUser.mockReturnValue(write.promise)
    const wrapper = createWrapper()
    await flushPromises()

    await followButton(wrapper).trigger('click')
    await followButton(wrapper).trigger('click')
    expect(mocks.followUser).toHaveBeenCalledTimes(1)
    write.reject(new Error('write failed'))
    await flushPromises()
    expect(wrapper.text()).toContain('write failed')
    expect(wrapper.text()).toContain('关注')

    mocks.followUser.mockResolvedValueOnce({ following: true })
    await followButton(wrapper).trigger('click')
    await flushPromises()
    expect(mocks.followUser).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已关注')
  })

  it('rejects storage-only drift before writing and does not change the displayed state', async () => {
    loginAs('viewer-a', 'token-a')
    const wrapper = createWrapper()
    await flushPromises()
    localStorage.setItem('accessToken', 'token-drift')

    await followButton(wrapper).trigger('click')

    expect(mocks.followUser).not.toHaveBeenCalled()
    expect(mocks.unfollowUser).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('登录状态已变化')
    expect(wrapper.text()).not.toContain('已关注')
  })

  it.each(['payload', 'decoder'] as const)('requires a state retry when the %s cannot confirm the follow result', async source => {
    loginAs('viewer-a')
    if (source === 'payload') mocks.followUser.mockResolvedValueOnce({})
    else mocks.followUser.mockRejectedValueOnce(new InteractionResponseError('/users/author-a/follow', 'following'))
    const signal = vi.fn()
    window.addEventListener('follow-state-updated', signal)
    try {
      const wrapper = createWrapper()
      await flushPromises()
      await followButton(wrapper).trigger('click')
      await flushPromises()
      expect(signal).not.toHaveBeenCalled()
      expect(wrapper.text()).not.toContain('已关注')
      expect(followButton(wrapper).attributes('disabled')).toBeDefined()
      mocks.getUserProfile.mockResolvedValueOnce(profile(true))
      await wrapper.findAll('button').find(button => button.text() === '重试')!.trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('已关注')
      expect(mocks.followUser).toHaveBeenCalledTimes(1)
    } finally { window.removeEventListener('follow-state-updated', signal) }
  })

  it('rejects a write response after storage-only drift and clears only its own busy state', async () => {
    loginAs('viewer-a', 'token-a')
    const write = deferred<{ following: boolean }>()
    mocks.followUser.mockReturnValue(write.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await followButton(wrapper).trigger('click')
    localStorage.setItem('accessToken', 'token-drift')
    write.resolve({ following: true })
    await flushPromises()

    expect(wrapper.text()).toContain('登录状态已变化')
    expect(wrapper.text()).not.toContain('已关注')
    expect(wrapper.text()).not.toContain('处理中...')
  })

  it('does not accept a pending read after a confirmed write', async () => {
    loginAs('viewer-a')
    const firstRead = deferred<UserProfile>()
    mocks.getUserProfile.mockReturnValueOnce(firstRead.promise)
    const wrapper = createWrapper()
    // The read is private and unavailable until the initial response resolves.
    firstRead.resolve(profile(false))
    await flushPromises()

    const followRead = deferred<UserProfile>()
    mocks.getUserProfile.mockReturnValueOnce(followRead.promise)
    await wrapper.setProps({ thread: thread('author-b') })
    await flushPromises()
    // Author B's read is pending; switching back to A invalidates it before it can overwrite A.
    await wrapper.setProps({ thread: thread('author-a') })
    const write = deferred<{ following: boolean }>()
    mocks.followUser.mockReturnValue(write.promise)
    await flushPromises()
    await followButton(wrapper).trigger('click')
    write.resolve({ following: true })
    followRead.resolve(profile(false))
    await flushPromises()

    expect(wrapper.text()).toContain('已关注')
  })

  it('emits the captured author only after an accepted write and emits nothing after unmount', async () => {
    loginAs('viewer-a')
    const write = deferred<{ following: boolean }>()
    mocks.followUser.mockReturnValue(write.promise)
    const signal = vi.fn()
    window.addEventListener('follow-state-updated', signal)
    const wrapper = createWrapper()
    await flushPromises()
    await followButton(wrapper).trigger('click')
    await wrapper.setProps({ thread: thread('author-b') })
    write.resolve({ following: true })
    await flushPromises()
    expect(signal).not.toHaveBeenCalled()

    wrapper.unmount()
    mocks.followUser.mockResolvedValueOnce({ following: true })
    const acceptedWrapper = createWrapper()
    await flushPromises()
    await followButton(acceptedWrapper).trigger('click')
    await flushPromises()
    expect(signal).toHaveBeenCalledTimes(1)
    expect(signal.mock.calls[0][0]).toMatchObject({ detail: { userId: 'author-a', following: true } })
    window.removeEventListener('follow-state-updated', signal)
  })
})

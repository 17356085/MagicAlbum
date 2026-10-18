import { createPinia, setActivePinia } from 'pinia'
import { effectScope, reactive } from 'vue'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getThread } from '@/api/threads'
import { getUserProfile } from '@/api/users'
import { addVisit, getRecentVisits } from '@/composables/useRecentVisits'
import { useAuthStore } from '@/stores/auth'
import { useThreadDetailPage } from '../useThreadDetailPage'
import type { Thread } from '@/types'

vi.mock('@/api/threads', () => ({ getThread: vi.fn() }))
vi.mock('@/api/users', () => ({ getUserProfile: vi.fn() }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function thread(id: number): Thread {
  return { id, title: `帖子 ${id}`, sectionId: id, sectionName: `分区 ${id}`, authorAvatarUrl: '/avatar.png' }
}

const scopes: ReturnType<typeof effectScope>[] = []

function setup() {
  const route = reactive({
    name: 'thread-detail', path: '/threads/1', fullPath: '/threads/1',
    params: { id: '1' }, hash: '', query: {}, matched: [], meta: {}, redirectedFrom: undefined,
  }) as RouteLocationNormalizedLoaded
  const scope = effectScope()
  scopes.push(scope)
  const state = scope.run(() => useThreadDetailPage(route))!
  return {
    state, scope,
    navigate(id: number) {
      route.params.id = String(id)
      route.path = `/threads/${id}`
      route.fullPath = route.path
    },
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'account-a', user: { id: 1, username: 'a' } })
  document.title = '原页面'
  addVisit({ id: 1, path: '/threads/1', title: '初始一' })
  addVisit({ id: 2, path: '/threads/2', title: '初始二' })
})

afterEach(() => {
  scopes.splice(0).forEach((scope) => scope.stop())
})

describe('thread detail request ownership', () => {
  it('keeps the latest thread and persisted history when requests finish in reverse order', async () => {
    const a = deferred<Thread>()
    const b = deferred<Thread>()
    vi.mocked(getThread).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { state, navigate } = setup()
    const first = state.load()
    navigate(2)
    const second = state.load()
    b.resolve(thread(2))
    await second
    a.resolve(thread(1))
    await first

    expect(state.thread.value?.id).toBe(2)
    expect(state.loading.value).toBe(false)
    expect(getRecentVisits().find((visit) => visit.id === 2)).toMatchObject({ title: '帖子 2', sectionId: 2 })
    expect(getRecentVisits().find((visit) => visit.id === 1)?.title).toBe('初始一')
    expect(document.title).toContain('帖子 2')
  })

  it('ignores an old error and finally while a newer request is still loading', async () => {
    const a = deferred<Thread>()
    const b = deferred<Thread>()
    vi.mocked(getThread).mockReturnValueOnce(a.promise).mockReturnValueOnce(b.promise)
    const { state, navigate } = setup()
    const first = state.load()
    navigate(2)
    const second = state.load()
    a.reject(new Error('old request failed'))
    await first
    expect(state.error.value).toBe('')
    expect(state.loading.value).toBe(true)
    b.resolve(thread(2))
    await second
    expect(state.thread.value?.id).toBe(2)
  })

  it('guards the second await for author metadata before updating history', async () => {
    const profile = deferred<Awaited<ReturnType<typeof getUserProfile>>>()
    vi.mocked(getThread).mockResolvedValueOnce({ ...thread(1), authorAvatarUrl: '', authorId: 10 })
      .mockResolvedValueOnce(thread(2))
    vi.mocked(getUserProfile).mockReturnValue(profile.promise)
    const { state, navigate } = setup()
    const first = state.load()
    await Promise.resolve()
    expect(getUserProfile).toHaveBeenCalledWith(10)
    navigate(2)
    await state.load()
    profile.resolve({ avatarUrl: '/old-avatar.png', nickname: '', bio: '', homepageUrl: '', location: '' })
    await first
    expect(state.displayAuthorAvatarUrl.value).not.toContain('old-avatar')
    expect(getRecentVisits().find((visit) => visit.id === 2)?.title).toBe('帖子 2')
    expect(getRecentVisits().find((visit) => visit.id === 1)?.title).toBe('初始一')
  })

  it('does not mutate refs, document title or persisted history after disposal', async () => {
    const pending = deferred<Thread>()
    vi.mocked(getThread).mockReturnValue(pending.promise)
    const { state, scope } = setup()
    const request = state.load()
    scope.stop()
    pending.resolve(thread(1))
    await request
    expect(state.thread.value).toBeNull()
    expect(getRecentVisits().find((visit) => visit.id === 1)?.title).toBe('初始一')
    expect(document.title).toBe('原页面')
  })

  it('never writes an earlier account response into the new account history', async () => {
    const pending = deferred<Thread>()
    vi.mocked(getThread).mockReturnValue(pending.promise)
    const { state } = setup()
    const request = state.load()
    useAuthStore().applyAuthState({ accessToken: 'account-b', user: { id: 2, username: 'b' } })
    addVisit({ id: 1, path: '/threads/1', title: '账号二记录' })
    pending.resolve(thread(1))
    await request
    expect(getRecentVisits()[0].title).toBe('账号二记录')
    expect(document.title).toBe('原页面')
    expect(state.thread.value).toBeNull()
  })
})

import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MyBookmarks from '../MyBookmarks.vue'
import { useAuthStore } from '@/stores/auth'
import type { BookmarkItem, BookmarkPage } from '@/api/bookmarks'
import type { Id } from '@/types'

const listBookmarksMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/bookmarkPages', () => ({
  listBookmarks: listBookmarksMock,
}))

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function bookmark(threadId: Id, title: string): BookmarkItem {
  return {
    threadId,
    title,
    sectionName: '测试',
    bookmarkedAt: '2026-10-07T00:00:00Z',
  }
}

function result(items: BookmarkItem[], page = 1, total = items.length, size = 10): BookmarkPage {
  return { items, page, size, total }
}

let pinia: ReturnType<typeof createPinia>
const wrappers: VueWrapper[] = []

function authState(token = 'token-a', id: Id = 1, username = 'alice'): void {
  useAuthStore(pinia).applyAuthState({ accessToken: token, user: { id, username } })
}

function createWrapper(): VueWrapper {
  const wrapper = mount(MyBookmarks, {
    global: {
      plugins: [pinia],
      stubs: {
        RouterLink: {
          props: ['to'],
          template: '<a :href="String(to)"><slot /></a>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function resetMocks(): void {
  localStorage.clear()
  sessionStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  authState()
  listBookmarksMock.mockReset()
  listBookmarksMock.mockResolvedValue(result([]))
}

beforeEach(resetMocks)

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  localStorage.clear()
  sessionStorage.clear()
})

describe('MyBookmarks', () => {
  it('shows loading then an explicit empty state', async () => {
    const pending = deferred<BookmarkPage>()
    listBookmarksMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()

    expect(wrapper.get('[role="status"]').text()).toContain('正在加载收藏')
    pending.resolve(result([]))
    await settle()

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('暂无收藏')
    expect(listBookmarksMock).toHaveBeenCalledWith({ page: 1, size: 10 })
  })

  it('shows a retryable failure and loads the page after retry', async () => {
    listBookmarksMock
      .mockRejectedValueOnce(new Error('network failure'))
      .mockResolvedValueOnce(result([bookmark(42, '重试后显示')], 1, 1))
    const wrapper = createWrapper()
    await settle()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('收藏加载失败')
    await alert.get('button').trigger('click')
    await settle()

    expect(listBookmarksMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('重试后显示')
  })

  it('links titles to the thread and requests the next page', async () => {
    listBookmarksMock
      .mockResolvedValueOnce(result([bookmark(42, '第一页帖子')], 1, 11))
      .mockResolvedValueOnce(result([bookmark(43, '第二页帖子')], 2, 11))
    const wrapper = createWrapper()
    await settle()

    expect(wrapper.get('a[href="/threads/42"]').text()).toContain('第一页帖子')
    const nextButton = wrapper.findAll('button').find((button) => button.text() === '下一页')
    expect(nextButton).toBeDefined()
    await nextButton!.trigger('click')
    await settle()

    expect(listBookmarksMock).toHaveBeenLastCalledWith({ page: 2, size: 10 })
    expect(wrapper.get('a[href="/threads/43"]').text()).toContain('第二页帖子')
  })

  it('ignores a pending response after unmount', async () => {
    const pending = deferred<BookmarkPage>()
    listBookmarksMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()
    await settle()
    const vm = wrapper.vm as unknown as { bookmarks: BookmarkPage; loading: boolean }

    wrapper.unmount()
    pending.resolve(result([bookmark(1, '卸载后收藏')]))
    await settle()

    expect(vm.bookmarks.items).toEqual([])
    expect(vm.loading).toBe(true)
  })

  it('keeps only the new account after an account switch before the old request completes', async () => {
    const oldRequest = deferred<BookmarkPage>()
    const newRequest = deferred<BookmarkPage>()
    listBookmarksMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)
    const wrapper = createWrapper()
    await settle()

    authState('token-b', 2, 'bob')
    await settle()
    expect(listBookmarksMock).toHaveBeenCalledTimes(2)

    oldRequest.resolve(result([bookmark(1, '旧账号收藏')]))
    await settle()
    expect(wrapper.text()).not.toContain('旧账号收藏')

    newRequest.resolve(result([bookmark(2, '新账号收藏')]))
    await settle()
    expect(wrapper.text()).toContain('新账号收藏')
  })

  it('invalidates every ABA epoch and explicit same-token auth event', async () => {
    const first = deferred<BookmarkPage>()
    const second = deferred<BookmarkPage>()
    const third = deferred<BookmarkPage>()
    listBookmarksMock
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise)
      .mockReturnValueOnce(third.promise)
    const wrapper = createWrapper()
    await settle()

    authState('token-b', 2, 'bob')
    authState('token-a', 1, 'alice')
    await settle()
    expect(listBookmarksMock).toHaveBeenCalledTimes(2)

    first.resolve(result([bookmark(1, '旧 ABA 响应')]))
    await settle()
    expect(wrapper.text()).not.toContain('旧 ABA 响应')
    second.resolve(result([bookmark(3, '恢复后的 A 收藏')]))
    await settle()
    expect(wrapper.text()).toContain('恢复后的 A 收藏')

    window.dispatchEvent(new CustomEvent('auth-state-changed', { detail: { loggedIn: true } }))
    await settle()
    expect(listBookmarksMock).toHaveBeenCalledTimes(3)
    third.resolve(result([bookmark(4, '显式事件后的收藏')]))
    await settle()
    expect(wrapper.text()).toContain('显式事件后的收藏')
  })

  it('keeps a valid page and request during same-token null-to-known owner hydration', async () => {
    const auth = useAuthStore(pinia)
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const second = deferred<BookmarkPage>()
    listBookmarksMock.mockReset()
    listBookmarksMock
      .mockResolvedValueOnce(result([bookmark(1, '第一页')], 1, 21))
      .mockReturnValueOnce(second.promise)
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    expect(listBookmarksMock).toHaveBeenLastCalledWith({ page: 2, size: 10 })
    expect((wrapper.vm as unknown as { page: number }).page).toBe(2)

    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')).toBe(true)
    await settle()
    expect(listBookmarksMock).toHaveBeenCalledTimes(2)
    expect((wrapper.vm as unknown as { page: number }).page).toBe(2)

    second.resolve(result([bookmark(2, '第二页')], 2, 21))
    await settle()
    expect(wrapper.text()).toContain('第二页')
    expect(wrapper.text()).not.toContain('第一页')
  })

  it('does not invalidate a request when only a failed login generation changes', async () => {
    const pending = deferred<BookmarkPage>()
    listBookmarksMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()
    await settle()

    useAuthStore(pinia).invalidateAuthOperations()
    pending.resolve(result([bookmark(7, '登录失败期间的收藏')]))
    await settle()

    expect(wrapper.text()).toContain('登录失败期间的收藏')
  })

  it('clears old rows and shows a session error when storage drifts before a response', async () => {
    const pending = deferred<BookmarkPage>()
    listBookmarksMock.mockReturnValueOnce(pending.promise)
    const wrapper = createWrapper()
    await settle()

    localStorage.setItem('accessToken', 'token-b')
    pending.resolve(result([bookmark(7, '漂移后的旧收藏')]))
    await settle()

    const vm = wrapper.vm as unknown as { bookmarks: BookmarkPage; loading: boolean }
    expect(vm.bookmarks.items).toEqual([])
    expect(vm.loading).toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变更，请刷新页面后重试')
    expect(wrapper.text()).not.toContain('漂移后的旧收藏')
  })

  it('shows a login-required error when a stored session disappears before retry', async () => {
    listBookmarksMock.mockResolvedValueOnce(result([bookmark(7, '已有收藏')]))
    const wrapper = createWrapper()
    await settle()

    useAuthStore(pinia).logout()
    await settle()

    expect(wrapper.get('[role="alert"]').text()).toContain('请登录后再加载收藏')
    expect(wrapper.text()).not.toContain('已有收藏')
  })

  it('refetches a shrunken last page once and renders the valid fallback contents', async () => {
    listBookmarksMock
      .mockResolvedValueOnce(result([bookmark(1, '第一页')], 1, 11))
      .mockResolvedValueOnce(result([], 2, 10))
      .mockResolvedValueOnce(result([bookmark(2, '收缩后的末页')], 1, 10))
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    await settle()

    expect(listBookmarksMock).toHaveBeenNthCalledWith(2, { page: 2, size: 10 })
    expect(listBookmarksMock).toHaveBeenNthCalledWith(3, { page: 1, size: 10 })
    expect(listBookmarksMock).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('收缩后的末页')
    expect(wrapper.text()).not.toContain('暂无收藏')
    expect(wrapper.text()).toContain('第')
    expect((wrapper.vm as unknown as { page: number }).page).toBe(1)
  })

  it('shows a retryable error for an inconsistent fallback response without repeating the request', async () => {
    listBookmarksMock
      .mockResolvedValueOnce(result([bookmark(1, '第一页')], 1, 11))
      .mockResolvedValueOnce(result([], 2, 0))
      .mockResolvedValueOnce(result([bookmark(2, '不应发布的错误页')], 2, 0))
    const wrapper = createWrapper()
    await settle()

    await wrapper.findAll('button').find((button) => button.text() === '下一页')!.trigger('click')
    await settle()

    expect(listBookmarksMock).toHaveBeenCalledTimes(3)
    expect(wrapper.get('[role="alert"]').text()).toContain('收藏加载失败，请稍后重试')
    expect(wrapper.text()).not.toContain('不应发布的错误页')
  })
})

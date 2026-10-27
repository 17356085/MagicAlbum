import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Comments from '../Comments.vue'
import CommentsComposer from '../comments/CommentsComposer.vue'
import CommentsList from '../comments/CommentsList.vue'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({ listPosts: vi.fn(), createPost: vi.fn(), likePost: vi.fn(), unlikePost: vi.fn(), uploadImage: vi.fn() }))
vi.mock('@/api/posts', () => mocks)
vi.mock('@/api/uploads', () => ({ uploadImage: mocks.uploadImage }))
let wrapper: VueWrapper | undefined

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function page(content = 'visible comment', liked = false) {
  return { items: [{ id: 7, threadId: 1, authorId: 1, authorUsername: 'alice', content, liked, likeCount: liked ? 1 : 0, createdAt: '2026-10-24T00:00:00Z' }], page: 1, size: 15, total: 1 }
}

async function open() {
  wrapper = mount(Comments, { props: { threadId: 1 }, global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
  await flushPromises()
  return wrapper
}

function emitSubmit(dialog: VueWrapper, text: string) {
  const composer = dialog.findComponent(CommentsComposer)
  composer.vm.$emit('update:modelValue', text)
  composer.vm.$emit('submit')
}

async function upload(dialog: VueWrapper) {
  const input = dialog.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['image'], 'photo.png', { type: 'image/png' })] })
  await input.trigger('change')
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
  for (const mock of Object.values(mocks)) mock.mockReset()
  mocks.listPosts.mockResolvedValue(page())
  mocks.createPost.mockResolvedValue(page().items[0])
  mocks.likePost.mockResolvedValue({ liked: true, likeCount: 1 })
  mocks.uploadImage.mockResolvedValue({ url: 'https://cdn.example/photo.png' })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('Comments session ownership', () => {
  it('clears personalized rows and ignores an old account list while the new list is pending', async () => {
    const older = deferred<ReturnType<typeof page>>()
    const newer = deferred<ReturnType<typeof page>>()
    mocks.listPosts.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    const dialog = await open()
    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await flushPromises()
    expect(mocks.listPosts).toHaveBeenCalledTimes(2)
    older.resolve(page('old account personalized row', true))
    await flushPromises()
    expect(dialog.text()).not.toContain('old account personalized row')
    newer.resolve(page('new account row'))
    await flushPromises()
    expect(dialog.text()).toContain('new account row')
  })

  it.each(['submit', 'like', 'upload'] as const)('blocks %s while stored and in-memory sessions disagree', async action => {
    const dialog = await open()
    localStorage.setItem('accessToken', 'token-b')
    if (action === 'submit') emitSubmit(dialog, 'private draft')
    if (action === 'like') dialog.findComponent(CommentsList).vm.$emit('toggle-like', page().items[0])
    if (action === 'upload') await upload(dialog)
    await flushPromises()
    expect(mocks.createPost).not.toHaveBeenCalled()
    expect(mocks.likePost).not.toHaveBeenCalled()
    expect(mocks.uploadImage).not.toHaveBeenCalled()
    expect(dialog.text()).toContain('账号状态已变更')
  })

  it('keeps the new account draft and avoids an old submit-triggered reload', async () => {
    const pending = deferred<ReturnType<typeof page>['items'][number]>()
    mocks.createPost.mockReturnValueOnce(pending.promise)
    const dialog = await open()
    emitSubmit(dialog, 'old private draft')
    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await flushPromises()
    dialog.findComponent(CommentsComposer).vm.$emit('update:modelValue', 'new private draft')
    const loadCount = mocks.listPosts.mock.calls.length
    pending.resolve({ ...page().items[0], content: 'old private draft' })
    await flushPromises()
    expect(dialog.findComponent(CommentsComposer).props('modelValue')).toBe('new private draft')
    expect(dialog.text()).not.toContain('old private draft')
    expect(mocks.listPosts).toHaveBeenCalledTimes(loadCount)
  })

  it('coalesces duplicate likes and rejects their late result after an account switch', async () => {
    const pending = deferred<{ liked: boolean; likeCount: number }>()
    mocks.likePost.mockReturnValueOnce(pending.promise)
    const dialog = await open()
    const list = dialog.findComponent(CommentsList)
    list.vm.$emit('toggle-like', page().items[0])
    list.vm.$emit('toggle-like', page().items[0])
    expect(mocks.likePost).toHaveBeenCalledTimes(1)
    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await flushPromises()
    pending.resolve({ liked: true, likeCount: 1 })
    await flushPromises()
    expect(dialog.findComponent(CommentsList).props('pagedGroups')[0].root.liked).toBe(false)
  })

  it('keeps a newer page load alive when an older-page like completes', async () => {
    const likePending = deferred<{ liked: boolean; likeCount: number }>()
    const pageTwoPending = deferred<ReturnType<typeof page>>()
    mocks.listPosts.mockResolvedValueOnce({ ...page(), total: 30 }).mockReturnValueOnce(pageTwoPending.promise)
    mocks.likePost.mockReturnValueOnce(likePending.promise)
    const dialog = await open()

    dialog.findComponent(CommentsList).vm.$emit('toggle-like', page().items[0])
    await flushPromises()
    ;(dialog.vm as unknown as { page: number }).page = 2
    await flushPromises()
    expect(mocks.listPosts).toHaveBeenCalledTimes(2)

    likePending.resolve({ liked: true, likeCount: 2 })
    await flushPromises()
    expect(mocks.listPosts).toHaveBeenCalledTimes(2)

    pageTwoPending.resolve({ ...page('newer page row'), page: 2, total: 30 })
    await flushPromises()
    expect(dialog.text()).toContain('newer page row')
    expect(dialog.findComponent(CommentsList).props('page')).toBe(2)
  })

  it('does not append an old account upload into the new draft', async () => {
    const pending = deferred<{ url: string }>()
    mocks.uploadImage.mockReturnValueOnce(pending.promise)
    const dialog = await open()
    await upload(dialog)
    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await flushPromises()
    dialog.findComponent(CommentsComposer).vm.$emit('update:modelValue', 'new draft')
    pending.resolve({ url: 'https://cdn.example/private-old.png' })
    await flushPromises()
    expect(dialog.findComponent(CommentsComposer).props('modelValue')).toBe('new draft')
    expect(dialog.findComponent(CommentsComposer).props('isUploading')).toBe(false)
  })

  it('preserves a valid upload across identity hydration and an unrelated failed login generation', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const pending = deferred<{ url: string }>()
    mocks.uploadImage.mockReturnValueOnce(pending.promise)
    const dialog = await open()
    await upload(dialog)
    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')).toBe(true)
    auth.invalidateAuthOperations()
    pending.resolve({ url: 'https://cdn.example/valid.png' })
    await flushPromises()
    expect(dialog.findComponent(CommentsComposer).props('modelValue')).toContain('https://cdn.example/valid.png')
    expect(mocks.listPosts).toHaveBeenCalledTimes(1)
  })

  it('coalesces a token A to B to A boundary and keeps only the final list', async () => {
    const oldList = deferred<ReturnType<typeof page>>()
    const finalList = deferred<ReturnType<typeof page>>()
    mocks.listPosts.mockReturnValueOnce(oldList.promise).mockReturnValueOnce(finalList.promise)
    const dialog = await open()

    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    await flushPromises()

    expect(mocks.listPosts).toHaveBeenCalledTimes(2)
    oldList.resolve(page('old account row'))
    await flushPromises()
    expect(dialog.text()).not.toContain('old account row')

    finalList.resolve(page('final account row'))
    await flushPromises()
    expect(dialog.text()).toContain('final account row')
  })

  it('invalidates a direct same-tick memory token ABA without a storage event', async () => {
    const oldList = deferred<ReturnType<typeof page>>()
    const finalList = deferred<ReturnType<typeof page>>()
    mocks.listPosts.mockReturnValueOnce(oldList.promise).mockReturnValueOnce(finalList.promise)
    const dialog = await open()

    const auth = useAuthStore()
    auth.token = 'token-b'
    auth.token = 'token-a'
    await flushPromises()

    expect(localStorage.getItem('accessToken')).toBe('token-a')
    expect(mocks.listPosts).toHaveBeenCalledTimes(2)
    oldList.resolve(page('stale same-tick ABA row'))
    await flushPromises()
    expect(dialog.text()).not.toContain('stale same-tick ABA row')

    finalList.resolve(page('final same-tick ABA row'))
    await flushPromises()
    expect(dialog.text()).toContain('final same-tick ABA row')
  })

  it('invalidates a same-tick known-owner ABA while preserving the same user object', async () => {
    const oldList = deferred<ReturnType<typeof page>>()
    const finalList = deferred<ReturnType<typeof page>>()
    mocks.listPosts.mockReturnValueOnce(oldList.promise).mockReturnValueOnce(finalList.promise)
    const dialog = await open()

    const auth = useAuthStore()
    const currentUser = auth.user
    expect(currentUser).not.toBeNull()
    currentUser!.id = 2
    currentUser!.id = 1
    await flushPromises()

    expect(mocks.listPosts).toHaveBeenCalledTimes(2)
    oldList.resolve(page('stale same-tick owner ABA row'))
    await flushPromises()
    expect(dialog.text()).not.toContain('stale same-tick owner ABA row')

    finalList.resolve(page('final same-tick owner ABA row'))
    await flushPromises()
    expect(dialog.text()).toContain('final same-tick owner ABA row')
  })

  it('settles a late submit after storage drift without clearing a newer busy state', async () => {
    const pending = deferred<ReturnType<typeof page>['items'][number]>()
    mocks.createPost.mockReturnValueOnce(pending.promise)
    const dialog = await open()
    emitSubmit(dialog, 'private draft')

    localStorage.setItem('accessToken', 'token-b')
    pending.resolve({ ...page().items[0], content: 'old private draft' })
    await flushPromises()

    expect(dialog.findComponent(CommentsComposer).props('isSubmitting')).toBe(false)
    expect(dialog.findComponent(CommentsComposer).props('modelValue')).toBe('')
    expect(dialog.text()).toContain('账号状态已变更')
  })

  it('rejects a late upload result and progress callback after storage drift', async () => {
    const pending = deferred<{ url: string }>()
    mocks.uploadImage.mockReturnValueOnce(pending.promise)
    const dialog = await open()
    const progress = vi.fn()
    const file = new File(['image'], 'photo.png', { type: 'image/png' })
    void (dialog.vm as unknown as { handleUploadImage: (file: File, onProgress: (percent: number) => void) => Promise<void> }).handleUploadImage(file, progress)
    await flushPromises()

    const requestProgress = mocks.uploadImage.mock.calls[0]?.[2] as ((percent: number) => void) | undefined
    localStorage.setItem('accessToken', 'token-b')
    requestProgress?.(80)
    pending.resolve({ url: 'https://cdn.example/late.png' })
    await flushPromises()

    expect(progress).not.toHaveBeenCalled()
    expect(dialog.findComponent(CommentsComposer).props('isUploading')).toBe(false)
    expect(dialog.findComponent(CommentsComposer).props('modelValue')).toBe('')
    expect(dialog.text()).toContain('账号状态已变更')
  })
})

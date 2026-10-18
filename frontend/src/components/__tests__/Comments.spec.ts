import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Comments from '../Comments.vue'
import { createPost, likePost, listPosts } from '@/api/posts'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import type { PageResult, Post, PostLikeState } from '@/types'

vi.mock('@/api/posts', () => ({
  listPosts: vi.fn(),
  createPost: vi.fn(),
  likePost: vi.fn(),
  unlikePost: vi.fn(),
}))

vi.mock('@/api/uploads', () => ({
  uploadImage: vi.fn(),
}))

const listPostsMock = vi.mocked(listPosts)
const createPostMock = vi.mocked(createPost)
const likePostMock = vi.mocked(likePost)
const uploadImageMock = vi.mocked(uploadImage)

function createWrapper(): VueWrapper {
  return mount(Comments, {
    props: { threadId: 1 },
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
}

async function selectFile(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.get('input[type="file"]')
  Object.defineProperty(input.element, 'files', {
    configurable: true,
    value: [file],
  })
  await input.trigger('change')
  await flushPromises()
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function result(id: number, content: string, threadId = 1, page = 1, total = 1): PageResult<Post> {
  return {
    items: [{
      id,
      threadId,
      content,
      authorId: 1,
      authorUsername: 'tester',
      authorNickname: 'tester',
      createdAt: '2026-10-07T00:00:00Z',
    }],
    page,
    size: 15,
    total,
  }
}

function resultWithItems(items: Post[], page = 1, total = items.length): PageResult<Post> {
  return { items, page, size: 15, total }
}

function post(id: number, content: string, createdAt: string): Post {
  return {
    id,
    threadId: 1,
    content,
    authorId: 1,
    authorUsername: 'tester',
    authorNickname: 'tester',
    createdAt,
  }
}

describe('Comments image upload feedback', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    listPostsMock.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
  })

  it('blocks sending during image upload and includes the image once upload finishes', async () => {
    const upload = deferred<Awaited<ReturnType<typeof uploadImage>>>()
    uploadImageMock.mockReturnValueOnce(upload.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('textarea').setValue('正文')
    await selectFile(wrapper, new File(['png'], 'test.png'))
    const send = wrapper.findAll('button').find((button) => button.text() === '上传中...' && button.classes().includes('ui-button-primary'))!
    expect(send.attributes('disabled')).toBeDefined()
    await send.trigger('click')
    expect(createPostMock).not.toHaveBeenCalled()
    upload.resolve({ url: '/uploads/image.png' })
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toContain('/uploads/image.png')
    createPostMock.mockResolvedValueOnce({ id: 71, threadId: 1, content: '正文', authorId: 1 })
    await wrapper.findAll('button').find((button) => button.text() === '发送评论')!.trigger('click')
    expect(createPostMock).toHaveBeenCalledWith(1, expect.objectContaining({ contentMd: expect.stringContaining('/uploads/image.png') }))
    await flushPromises()
    wrapper.unmount()
  })

  it('does not let an old upload clear the busy state for a new thread upload', async () => {
    const old = deferred<Awaited<ReturnType<typeof uploadImage>>>()
    const fresh = deferred<Awaited<ReturnType<typeof uploadImage>>>()
    uploadImageMock.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await selectFile(wrapper, new File(['old'], 'old.png'))
    await wrapper.setProps({ threadId: 2 })
    await flushPromises()
    await selectFile(wrapper, new File(['fresh'], 'fresh.png'))
    old.resolve({ url: '/uploads/old.png' })
    await flushPromises()
    expect(wrapper.get('input[type="file"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('textarea').element.value).not.toContain('/uploads/old.png')
    fresh.resolve({ url: '/uploads/fresh.png' })
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toContain('/uploads/fresh.png')
    wrapper.unmount()
  })

  it('uses one in-flight submit, disables the button, and rolls back while preserving input on failure', async () => {
    let rejectCreate!: (reason?: unknown) => void
    createPostMock.mockReturnValueOnce(new Promise<Post>((_resolve, reject) => {
      rejectCreate = reject
    }))

    const wrapper = createWrapper()
    await flushPromises()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('待发送评论')
    const sendButton = wrapper.findAll('button').find((button) => button.text() === '发送评论')
    expect(sendButton).toBeDefined()

    await sendButton!.trigger('click')
    expect(createPostMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('发送中...')
    expect(sendButton!.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('ul > li')).toHaveLength(1)

    await sendButton!.trigger('click')
    expect(createPostMock).toHaveBeenCalledTimes(1)

    rejectCreate(new Error('network failure'))
    await flushPromises()

    expect(wrapper.text()).toContain('发布失败')
    expect(wrapper.findAll('ul > li')).toHaveLength(0)
    expect((textarea.element as HTMLTextAreaElement).value).toBe('待发送评论')
    expect(wrapper.text()).toContain('发送评论')
    expect(sendButton!.attributes('disabled')).toBeUndefined()
  })

  it('replaces the optimistic comment once after a successful submit and clears the input', async () => {
    listPostsMock.mockReset()
    listPostsMock
      .mockResolvedValueOnce({ items: [], page: 1, size: 20, total: 0 })
      .mockResolvedValueOnce(result(101, '已发送评论'))
    createPostMock.mockResolvedValueOnce({
      id: 101,
      threadId: 1,
      authorId: 1,
      authorUsername: 'tester',
      authorNickname: 'tester',
      content: '已发送评论',
      createdAt: '2026-10-07T00:00:00Z',
      updatedAt: '2026-10-07T00:00:00Z',
    })

    const wrapper = createWrapper()
    await flushPromises()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('待发送评论')
    const sendButton = wrapper.findAll('button').find((button) => button.text() === '发送评论')
    expect(sendButton).toBeDefined()

    await sendButton!.trigger('click')
    await flushPromises()

    expect(createPostMock).toHaveBeenCalledTimes(1)
    expect(listPostsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('ul > li')).toHaveLength(1)
    expect(wrapper.text()).toContain('已发送评论')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.text()).toContain('发送评论')
  })

  it.each([
    ['failure', { response: { status: 500 } }, '图片上传失败，请稍后重试'],
    ['timeout', { code: 'ECONNABORTED', message: 'timeout of 10000ms exceeded' }, '图片上传超时，请稍后重试'],
    ['unauthorized', { response: { status: 401 } }, '登录已过期，请重新登录后再上传图片'],
  ])('shows a readable $0 message without unhandled upload rejection', async (_name, reason, message) => {
    const wrapper = createWrapper()
    await flushPromises()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('已有内容')
    uploadImageMock.mockRejectedValueOnce(reason)

    await selectFile(wrapper, new File(['image'], 'photo.png', { type: 'image/png' }))

    expect(wrapper.text()).toContain(message)
    expect((textarea.element as HTMLTextAreaElement).value).toBe('已有内容')
    expect((textarea.element as HTMLTextAreaElement).value).not.toContain('![]()')
  })

  it('does not insert an empty image and allows retrying the same file after failure', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('已有内容')
    const file = new File(['image'], 'photo.png', { type: 'image/png' })
    uploadImageMock
      .mockResolvedValueOnce({})
      .mockResolvedValueOnce({ url: 'https://cdn.example/photo.png' })

    await selectFile(wrapper, file)
    expect(wrapper.text()).toContain('服务器未返回有效图片地址')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('已有内容')

    await selectFile(wrapper, file)
    expect(uploadImageMock).toHaveBeenCalledTimes(2)
    expect((textarea.element as HTMLTextAreaElement).value).toContain('![](https://cdn.example/photo.png)')
  })
})

describe('Comments request ordering', () => {
  beforeEach(() => {
    listPostsMock.mockReset()
    createPostMock.mockReset()
    likePostMock.mockReset()
    listPostsMock.mockResolvedValue({ items: [], page: 1, size: 15, total: 0 })
  })

  it('keeps the newest thread result when an older response arrives later', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    listPostsMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      items: Post[]
      total: number
      replyToPostId: number | null
      groupPageMap: Record<string, number>
      collapsedMap: Record<string, boolean>
    }
    vm.items = [result(9, '旧页残留').items[0]]
    vm.total = 1
    vm.replyToPostId = 9
    vm.groupPageMap = { '9': 2 }
    vm.collapsedMap = { '9': true }
    await wrapper.setProps({ threadId: 2 })
    expect(listPostsMock).toHaveBeenCalledTimes(2)
    expect(vm.items).toEqual([])
    expect(vm.total).toBe(0)
    expect(vm.replyToPostId).toBeNull()
    expect(vm.groupPageMap).toEqual({})
    expect(vm.collapsedMap).toEqual({})

    newRequest.resolve(result(2, '新线程评论', 2))
    await flushPromises()
    oldRequest.resolve(result(1, '旧线程评论'))
    await flushPromises()

    expect(wrapper.text()).toContain('新线程评论')
    expect(wrapper.text()).not.toContain('旧线程评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps loading while an older request fails and its finally runs', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    listPostsMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.setProps({ threadId: 2 })

    oldRequest.reject(new Error('stale failure'))
    await flushPromises()
    expect(wrapper.text()).toContain('正在加载...')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)

    newRequest.resolve(result(2, '最新线程评论', 2))
    await flushPromises()
    expect(wrapper.text()).toContain('最新线程评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('ignores an older pagination failure before the newer load starts', async () => {
    const oldRequest = deferred<PageResult<Post>>()
    const newRequest = deferred<PageResult<Post>>()
    listPostsMock.mockReturnValueOnce(oldRequest.promise).mockReturnValueOnce(newRequest.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { page: number }
    vm.page = 2
    oldRequest.reject(new Error('stale page failure'))
    await flushPromises()

    expect(listPostsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('正在加载...')

    newRequest.resolve(result(2, '第2页评论', 1, 2, 30))
    await flushPromises()
    expect(wrapper.text()).toContain('第2页评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps the newest pagination result when pages are requested quickly', async () => {
    const pageTwo = deferred<PageResult<Post>>()
    const pageThree = deferred<PageResult<Post>>()
    listPostsMock
      .mockResolvedValueOnce(result(1, '第1页评论', 1, 1, 45))
      .mockReturnValueOnce(pageTwo.promise)
      .mockReturnValueOnce(pageThree.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { page: number }
    vm.page = 2
    await flushPromises()
    vm.page = 3
    await flushPromises()
    expect(listPostsMock).toHaveBeenCalledTimes(3)

    pageThree.resolve(result(3, '第3页评论', 1, 3, 45))
    await flushPromises()
    pageTwo.resolve(result(2, '第2页评论', 1, 2, 45))
    await flushPromises()

    expect(wrapper.text()).toContain('第3页评论')
    expect(wrapper.text()).not.toContain('第2页评论')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('keeps the newest sort result when sort changes quickly', async () => {
    const oldSort = deferred<PageResult<Post>>()
    const newSort = deferred<PageResult<Post>>()
    listPostsMock
      .mockResolvedValueOnce(result(1, '初始排序'))
      .mockReturnValueOnce(oldSort.promise)
      .mockReturnValueOnce(newSort.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      selectLikesOrder: (order: 'asc' | 'desc') => void
      selectTimeOrder: (order: 'asc' | 'desc') => void
    }
    vm.selectLikesOrder('desc')
    await flushPromises()
    vm.selectTimeOrder('asc')
    await flushPromises()
    expect(listPostsMock).toHaveBeenCalledTimes(3)

    newSort.resolve(result(3, '时间升序结果'))
    await flushPromises()
    oldSort.resolve(result(2, '最热旧结果'))
    await flushPromises()

    expect(wrapper.text()).toContain('时间升序结果')
    expect(wrapper.text()).not.toContain('最热旧结果')
    expect(wrapper.text()).not.toContain('正在加载...')
  })

  it('accepts concurrent likes for different comments', async () => {
    const firstLike = deferred<PostLikeState>()
    const secondLike = deferred<PostLikeState>()
    listPostsMock.mockResolvedValueOnce(resultWithItems([
      post(1, '第一条评论', '2026-10-07T00:00:00Z'),
      post(2, '第二条评论', '2026-10-06T00:00:00Z'),
    ]))
    likePostMock.mockReturnValueOnce(firstLike.promise).mockReturnValueOnce(secondLike.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const likeButtons = wrapper.findAll('button').filter((button) => button.text().includes('点赞'))
    expect(likeButtons).toHaveLength(2)
    await likeButtons[0].trigger('click')
    await likeButtons[1].trigger('click')

    secondLike.resolve({ postId: 2, liked: true, likeCount: 2 })
    await flushPromises()
    firstLike.resolve({ postId: 1, liked: true, likeCount: 1 })
    await flushPromises()

    expect(wrapper.text()).toContain('♥ 已赞 1')
    expect(wrapper.text()).toContain('♥ 已赞 2')
  })

  it('keeps the newest like result for the same comment', async () => {
    const firstLike = deferred<PostLikeState>()
    const secondLike = deferred<PostLikeState>()
    listPostsMock.mockResolvedValueOnce(result(1, '同一条评论'))
    likePostMock.mockReturnValueOnce(firstLike.promise).mockReturnValueOnce(secondLike.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const likeButton = wrapper.findAll('button').find((button) => button.text().includes('点赞'))
    expect(likeButton).toBeDefined()
    await likeButton!.trigger('click')
    await likeButton!.trigger('click')

    secondLike.resolve({ postId: 1, liked: true, likeCount: 2 })
    await flushPromises()
    firstLike.resolve({ postId: 1, liked: true, likeCount: 1 })
    await flushPromises()

    expect(wrapper.text()).toContain('♥ 已赞 2')
    expect(wrapper.text()).not.toContain('♥ 已赞 1')
  })

  it('ignores an in-flight response after unmount', async () => {
    const request = deferred<PageResult<Post>>()
    listPostsMock.mockReturnValueOnce(request.promise)

    const wrapper = createWrapper()
    await flushPromises()
    const vm = wrapper.vm as unknown as { items: Post[]; loading: boolean }
    wrapper.unmount()

    request.resolve(result(1, '卸载后评论'))
    await flushPromises()

    expect(vm.items).toEqual([])
    expect(vm.loading).toBe(true)
  })

  it('reloads the list after a successful comment while retaining the created item', async () => {
    listPostsMock
      .mockResolvedValueOnce({ items: [], page: 1, size: 15, total: 0 })
      .mockResolvedValueOnce(result(101, '服务器确认评论'))
    createPostMock.mockResolvedValueOnce({
      id: 101,
      threadId: 1,
      authorId: 1,
      authorUsername: 'tester',
      authorNickname: 'tester',
      content: '服务器确认评论',
      createdAt: '2026-10-07T00:00:00Z',
      updatedAt: '2026-10-07T00:00:00Z',
    })

    const wrapper = createWrapper()
    await flushPromises()
    const textarea = wrapper.get('textarea')
    await textarea.setValue('待发送评论')
    await wrapper.findAll('button').find((button) => button.text() === '发送评论')!.trigger('click')
    await flushPromises()

    expect(listPostsMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('服务器确认评论')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('')
  })
})

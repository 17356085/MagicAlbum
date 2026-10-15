import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Comments from '../Comments.vue'
import { createPost, listPosts } from '@/api/posts'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import type { Post } from '@/types'

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

describe('Comments image upload feedback', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    listPostsMock.mockResolvedValue({ items: [], page: 1, size: 20, total: 0 })
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

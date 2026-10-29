import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Comments from '../Comments.vue'
import CommentsList from '../comments/CommentsList.vue'
import CommentsComposer from '../comments/CommentsComposer.vue'
import { useAuthStore } from '@/stores/auth'
import { InteractionResponseError } from '@/api/interactionState'
import { UploadResponseUnconfirmedError } from '@/api/uploadResponse'
import { createBaseMarkdownRenderer, renderMarkdown } from '@/utils/markdownBase'

const mocks = vi.hoisted(() => ({ listPosts: vi.fn(), createPost: vi.fn(), likePost: vi.fn(), unlikePost: vi.fn(), uploadImage: vi.fn() }))
vi.mock('@/api/posts', () => mocks)
vi.mock('@/api/uploads', () => ({ uploadImage: mocks.uploadImage }))
let wrapper: VueWrapper | undefined
const row = { id: 7, threadId: 1, authorId: 1, authorUsername: 'alice', content: '已有评论仍应可读', liked: false, likeCount: 0, createdAt: '2026-10-24T00:00:00Z' }
const result = { items: [row], page: 1, size: 15, total: 1 }

function parse(markup: string): DocumentFragment {
  const template = document.createElement('template')
  template.innerHTML = markup
  return template.content
}

async function open() {
  wrapper = mount(Comments, { props: { threadId: 1 }, global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
  for (const mock of Object.values(mocks)) mock.mockReset()
  mocks.listPosts.mockResolvedValue(result)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  localStorage.clear()
})

describe('Comments independent load and operation feedback', () => {
  it('keeps loaded comments visible after a failed like and clears the feedback on retry', async () => {
    mocks.likePost.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({ postId: 7, liked: true, likeCount: 1 })
    const dialog = await open()
    dialog.findComponent(CommentsList).vm.$emit('toggle-like', row)
    await flushPromises()
    expect(dialog.text()).toContain('点赞操作失败')
    expect(dialog.text()).toContain(row.content)
    expect(dialog.find('[role="alert"]').exists()).toBe(true)
    dialog.findComponent(CommentsList).vm.$emit('toggle-like', row)
    await flushPromises()
    expect(dialog.text()).not.toContain('点赞操作失败')
    expect(dialog.text()).toContain('已赞')
    expect(mocks.listPosts).toHaveBeenCalledTimes(1)
  })

  it('keeps the draft and existing rows after upload failure', async () => {
    mocks.uploadImage.mockRejectedValueOnce(new Error('upload failed'))
    const dialog = await open()
    dialog.findComponent(CommentsComposer).vm.$emit('update:modelValue', '保留我的输入')
    const input = dialog.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['image'], 'photo.png', { type: 'image/png' })] })
    await input.trigger('change')
    await flushPromises()
    expect(dialog.text()).toContain('图片上传失败')
    expect(dialog.text()).toContain(row.content)
    expect(dialog.findComponent(CommentsComposer).props('modelValue')).toBe('保留我的输入')
  })

  it('renders an uploaded delimiter-containing URL as one complete comment image', async () => {
    const url = 'https://cdn.example/image)![](https://evil.example/x.png'
    mocks.uploadImage.mockResolvedValueOnce({ url })
    const dialog = await open()
    const composer = dialog.findComponent(CommentsComposer)
    const input = composer.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['image'], 'photo.png', { type: 'image/png' })] })

    await input.trigger('change')
    await flushPromises()

    const markdown = String(composer.props('modelValue') || '')
    const fragment = parse(renderMarkdown(createBaseMarkdownRenderer(), markdown))
    const images = fragment.querySelectorAll('img')

    expect(mocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(images).toHaveLength(1)
    expect(fragment.querySelectorAll('a')).toHaveLength(0)
    expect(images[0]?.getAttribute('src')).toBe('https://cdn.example/image)!%5B%5D(https://evil.example/x.png')
    expect(images[0]?.getAttribute('alt')).toBe('')
  })

  it('shows an unconfirmed upload result without retrying or submitting the preserved draft', async () => {
    mocks.uploadImage.mockRejectedValueOnce(new UploadResponseUnconfirmedError())
    const dialog = await open()
    const composer = dialog.findComponent(CommentsComposer)
    composer.vm.$emit('update:modelValue', '保留我的输入')
    const input = composer.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['image'], 'photo.png', { type: 'image/png' })] })
    await input.trigger('change')
    await flushPromises()
    expect(dialog.get('[role="alert"]').text()).toBe('图片上传结果未确认，重复上传可能产生多份图片')
    expect(dialog.text()).not.toContain('图片上传失败')
    expect(dialog.text()).not.toContain('请稍后重试')
    expect(dialog.text()).toContain(row.content)
    expect(composer.props('modelValue')).toBe('保留我的输入')
    expect(composer.props('isUploading')).toBe(false)
    expect(mocks.uploadImage).toHaveBeenCalledTimes(1)
    expect(mocks.createPost).not.toHaveBeenCalled()
  })

  it('offers a read-only state refresh after an invalid interaction response', async () => {
    mocks.likePost.mockRejectedValueOnce(new InteractionResponseError('/posts/7/like', 'liked'))
    const dialog = await open()
    dialog.findComponent(CommentsList).vm.$emit('toggle-like', row)
    await flushPromises()
    expect(dialog.text()).toContain('点赞结果暂未确认')
    expect(dialog.text()).toContain(row.content)
    const refresh = dialog.findAll('button').find(button => button.text() === '刷新评论状态')
    expect(refresh).toBeDefined()
    await refresh!.trigger('click')
    await flushPromises()
    expect(mocks.listPosts).toHaveBeenCalledTimes(2)
    expect(mocks.likePost).toHaveBeenCalledTimes(1)
    expect(mocks.unlikePost).not.toHaveBeenCalled()
    expect(dialog.text()).not.toContain('点赞结果暂未确认')
  })

  it('rolls back a failed optimistic submission without hiding existing comments or losing the draft', async () => {
    mocks.createPost.mockRejectedValueOnce(new Error('network'))
    const dialog = await open()
    const composer = dialog.findComponent(CommentsComposer)
    composer.vm.$emit('update:modelValue', '未成功的新评论')
    composer.vm.$emit('submit')
    await flushPromises()
    expect(dialog.text()).toContain('发布失败')
    expect(dialog.text()).toContain(row.content)
    expect(dialog.findComponent(CommentsList).props('pagedGroups')).toHaveLength(1)
    expect(composer.props('modelValue')).toBe('未成功的新评论')
    expect(composer.props('isSubmitting')).toBe(false)
  })

  it('offers one load retry and restores the list after a failed page read', async () => {
    mocks.listPosts.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce(result)
    const dialog = await open()
    expect(dialog.text()).toContain('加载评论失败')
    const retry = dialog.findAll('button').find(button => button.text() === '重试加载评论')
    expect(retry).toBeDefined()
    await retry!.trigger('click')
    await flushPromises()
    expect(dialog.text()).toContain(row.content)
    expect(dialog.text()).not.toContain('加载评论失败')
    expect(mocks.listPosts).toHaveBeenCalledTimes(2)
  })
})

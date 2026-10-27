import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Comments from '../Comments.vue'
import CommentsList from '../comments/CommentsList.vue'
import CommentsComposer from '../comments/CommentsComposer.vue'
import { useAuthStore } from '@/stores/auth'
import type { User } from '@/types'

const api = vi.hoisted(() => ({ listPosts: vi.fn(), createPost: vi.fn(), likePost: vi.fn(), unlikePost: vi.fn(), uploadImage: vi.fn() }))
vi.mock('@/api/posts', () => api)
vi.mock('@/api/uploads', () => ({ uploadImage: api.uploadImage }))
let wrapper: VueWrapper | undefined
let rejectWrite: ((error: Error) => void) | undefined
let finishUpload: ((value: unknown) => void) | undefined
const existing = { id: 7, threadId: 42, authorId: 9, authorUsername: 'another', content: '已有评论', createdAt: '2026-10-24T00:00:00Z' }

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'optimistic-writer', user: { id: 1, username: 'writer' } })
  for (const mock of Object.values(api)) mock.mockReset()
  api.listPosts.mockResolvedValue({ items: [existing], page: 1, size: 15, total: 1 })
  api.createPost.mockImplementation(() => new Promise((_, reject) => { rejectWrite = reject }))
  rejectWrite = undefined
  finishUpload = undefined
})
afterEach(async () => {
  wrapper?.unmount()
  wrapper = undefined
  rejectWrite?.(new Error('test cleanup'))
  finishUpload?.({ url: '/uploads/completed.png' })
  await flushPromises()
  localStorage.clear()
})
async function open(user?: User | null) {
  if (user !== undefined) useAuthStore().applyAuthState({ accessToken: 'optimistic-writer', user })
  wrapper = mount(Comments, { props: { threadId: 42 }, global: { stubs: { RouterLink: { props: ['to'], template: '<a :href="String(to)"><slot /></a>' } } } })
  await flushPromises()
  return wrapper
}
async function send() {
  const composer = wrapper!.findComponent(CommentsComposer)
  composer.vm.$emit('update:modelValue', '发送中保留的输入')
  composer.vm.$emit('submit')
  await flushPromises()
}
function pendingRow() { return wrapper!.get('[id^="post-temp_"]') }

describe('optimistic comment author and reply controls', () => {
  it('preserves a large string account id in the rendered pending author link', async () => {
    await open({ id: '9007199254740993123', username: 'writer' })
    await send()
    expect(pendingRow().get('a').attributes('href')).toBe('/users/9007199254740993123')
  })

  it('uses the established userId fallback without coercing it to zero', async () => {
    await open({ userId: '9007199254740993123', username: 'legacy' } as User)
    await send()
    expect(pendingRow().get('a').attributes('href')).toBe('/users/9007199254740993123')
  })

  it('shows the current nickname and avatar while waiting for the server DTO', async () => {
    await open({ id: 1, username: 'writer', nickname: '当前昵称', avatarUrl: 'https://avatars.example.test/current.png' })
    await send()
    expect(pendingRow().get('a').text()).toBe('当前昵称')
    expect(pendingRow().get('img').attributes('src')).toBe('https://avatars.example.test/current.png')
  })

  it('keeps the username and standard avatar fallback when no profile summary is available', async () => {
    await open({ id: 1, username: 'writer' })
    await send()
    expect(pendingRow().get('a').text()).toBe('writer')
    expect(pendingRow().get('img').attributes('src')).toContain('seed=writer')
  })

  it('does not invent a profile id while the token identity is unknown', async () => {
    await open(null)
    await send()
    const root = wrapper!.findComponent(CommentsList).props('pagedGroups').find(group => group.root._optimistic)?.root
    expect(root?.authorId).toBeUndefined()
    expect(pendingRow().get('a').attributes('href')).toBe('/users')
    expect(pendingRow().get('a').text()).toBe('我')
  })

  it('keeps the composer reachable after clicking reply on a pending comment and then failing the write', async () => {
    const view = await open()
    await send()
    const reply = pendingRow().findAll('button').find(button => button.text() === '回复')!
    await reply.trigger('click')
    rejectWrite?.(new Error('write rejected'))
    await flushPromises()
    expect(view.findAllComponents(CommentsComposer)).toHaveLength(1)
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('发送中保留的输入')
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBeNull()
    expect(api.createPost).toHaveBeenCalledOnce()
  })

  it('disables reply controls during a send and guards the target even if a child emits directly', async () => {
    const view = await open()
    await send()
    expect(pendingRow().findAll('button').find(button => button.text() === '回复')!.attributes('disabled')).toBeDefined()
    expect(pendingRow().findAll('button').find(button => button.text().includes('点赞'))!.attributes('disabled')).toBeDefined()
    const existingReply = view.get('#post-7').findAll('button').find(button => button.text() === '回复')!
    expect(existingReply.attributes('disabled')).toBeDefined()
    view.findComponent(CommentsList).vm.$emit('reply', 7)
    await flushPromises()
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBeNull()
  })

  it('keeps the reply target fixed during upload, then restores normal reply after it settles', async () => {
    api.uploadImage.mockReturnValue(new Promise(resolve => { finishUpload = resolve }))
    const view = await open()
    const input = view.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['image'], 'a.png', { type: 'image/png' })] })
    await input.trigger('change')
    await flushPromises()
    const reply = () => view.get('#post-7').findAll('button').find(button => button.text() === '回复')!
    expect(reply().attributes('disabled')).toBeDefined()
    view.findComponent(CommentsList).vm.$emit('reply', 7)
    await flushPromises()
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBeNull()
    finishUpload?.({ url: '/uploads/completed.png' })
    await flushPromises()
    expect(reply().attributes('disabled')).toBeUndefined()
    await reply().trigger('click')
    await flushPromises()
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBe(7)
    expect(view.findComponent(CommentsComposer).props('modelValue')).toContain('/uploads/completed.png')
  })

  it('locks child reply targets during a send and restores them after rejection', async () => {
    api.listPosts.mockResolvedValue({ items: [existing, { ...existing, id: 8, replyToPostId: 7, content: '子评论' }], page: 1, size: 15, total: 2 })
    const view = await open()
    const rootReply = view.get('#post-7').findAll('button').find(button => button.text() === '回复')!
    await rootReply.trigger('click')
    await send()
    const childReply = () => view.get('#post-8').findAll('button').find(button => button.text() === '回复')!
    expect(childReply().attributes('disabled')).toBeDefined()
    view.findComponent(CommentsList).vm.$emit('reply', 8, 7)
    await flushPromises()
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBe(7)
    rejectWrite?.(new Error('write rejected'))
    await flushPromises()
    expect(view.findAllComponents(CommentsComposer)).toHaveLength(1)
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('发送中保留的输入')
    expect(childReply().attributes('disabled')).toBeUndefined()
    await childReply().trigger('click')
    await flushPromises()
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBe(8)
    expect(api.createPost).toHaveBeenCalledOnce()
  })
})

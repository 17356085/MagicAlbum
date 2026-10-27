import type { AxiosAdapter, AxiosResponse } from 'axios'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Comments from '../Comments.vue'
import CommentsList from '../comments/CommentsList.vue'
import CommentsComposer from '../comments/CommentsComposer.vue'
import { useAuthStore } from '@/stores/auth'

const transport = vi.hoisted(() => ({
  body: null as unknown,
  failure: undefined as unknown,
  pending: false,
  readFailure: false,
  finish: undefined as ((body: unknown) => void) | undefined,
  fail: undefined as ((error: unknown) => void) | undefined,
  calls: [] as Array<{ method?: string; url?: string; data?: unknown }>,
}))
vi.mock('@/api/client', async () => {
  const { default: axios } = await vi.importActual<typeof import('axios')>('axios')
  const adapter: AxiosAdapter = async config => {
    transport.calls.push({ method: config.method, url: config.url, data: config.data })
    const response = (data: unknown): AxiosResponse => ({ data, status: config.method === 'post' ? 201 : 200, statusText: 'OK', headers: {}, config })
    if (config.method === 'get') {
      if (transport.readFailure) throw Object.assign(new Error('list unavailable'), { isAxiosError: true, response: { status: 503 } })
      return response({ items: [{ id: 7, threadId: 42, authorId: 7, authorUsername: 'writer', content: '原评论', createdAt: '2026-10-24T00:00:00Z', liked: false, likeCount: 0 }], page: 1, size: 15, total: 16 })
    }
    if (transport.failure) throw transport.failure
    if (transport.pending) return await new Promise((resolve, reject) => {
      transport.finish = body => resolve(response(body))
      transport.fail = reject
    })
    return response(transport.body)
  }
  return { default: axios.create({ baseURL: 'http://offline.invalid/api/v1', adapter }) }
})

let wrapper: VueWrapper | undefined
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'comment-writer', user: { id: 7, username: 'writer' } })
  transport.body = { id: 17, threadId: 99, content: '错误目标' }
  transport.failure = undefined
  transport.pending = false
  transport.readFailure = false
  transport.finish = undefined
  transport.fail = undefined
  transport.calls.length = 0
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; localStorage.clear() })
async function open() {
  wrapper = mount(Comments, { props: { threadId: 42 }, global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } } })
  await flushPromises()
  return wrapper
}
async function submit(text: string) {
  const composer = wrapper!.findComponent(CommentsComposer)
  composer.vm.$emit('update:modelValue', text)
  await flushPromises()
  wrapper!.findComponent(CommentsComposer).vm.$emit('submit')
  await flushPromises()
}
const writes = () => transport.calls.filter(call => call.method === 'post')
const reads = () => transport.calls.filter(call => call.method === 'get')

describe('comment creation through the actual API transport', () => {
  it('keeps input, reply target and real rows after an unconfirmed 201, and verifies with a GET only', async () => {
    const view = await open()
    view.findComponent(CommentsList).vm.$emit('reply', 7)
    await flushPromises()
    await submit('保留回复')
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('保留回复')
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBe(7)
    expect(view.text()).toContain('原评论')
    expect(view.text()).not.toContain('错误目标')
    expect(view.text()).toContain('评论发送结果暂未确认')
    expect(view.text()).toContain('避免重复发送')
    expect(view.findComponent(CommentsList).props('hasPagination')).toBe(true)
    expect(writes()).toHaveLength(1)
    expect(reads()).toHaveLength(1)
    const refresh = view.findAll('button').find(button => button.text() === '刷新评论核对')
    expect(refresh).toBeDefined()
    await refresh!.trigger('click')
    await flushPromises()
    expect(reads()).toHaveLength(2)
    expect(writes()).toHaveLength(1)
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('保留回复')
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBe(7)
    expect(view.text()).toContain('评论发送结果暂未确认')
    transport.body = { id: 17, threadId: 42, replyToPostId: 7, content: '保留回复' }
    view.findComponent(CommentsComposer).vm.$emit('submit')
    await flushPromises()
    expect(writes()).toHaveLength(2)
    expect(writes()[0]?.data).toBe(writes()[1]?.data)
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('')
    expect(view.findComponent(CommentsList).props('replyToPostId')).toBeNull()
    expect(view.text()).not.toContain('评论发送结果暂未确认')
  })

  it('keeps input after a lost response without automatically refreshing or sending again', async () => {
    transport.failure = { isAxiosError: true, code: 'ECONNABORTED' }
    const view = await open()
    await submit('超时输入')
    expect(view.text()).toContain('评论发送结果暂未确认')
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('超时输入')
    expect(writes()).toHaveLength(1)
    expect(reads()).toHaveLength(1)
  })

  it('does not subtract from a refreshed server count when the optimistic row was already replaced', async () => {
    transport.pending = true
    const view = await open()
    await submit('待确认评论')
    view.findComponent(CommentsList).vm.$emit('select-likes-order', 'desc')
    await flushPromises()
    expect(reads()).toHaveLength(2)
    expect(view.findComponent(CommentsList).props('hasPagination')).toBe(true)
    transport.fail?.({ isAxiosError: true, response: { status: 403, data: { message: '无权限' } } })
    await flushPromises()
    expect(view.text()).toContain('无权限')
    expect(view.findComponent(CommentsList).props('hasPagination')).toBe(true)
    expect(view.findComponent(CommentsList).props('pageCount')).toBe(2)
  })

  it('does not apply a late unconfirmed result after another account takes over', async () => {
    transport.pending = true
    const view = await open()
    await submit('旧账号内容')
    useAuthStore().applyAuthState({ accessToken: 'next-comment-writer', user: { id: 8, username: 'next' } })
    await flushPromises()
    transport.finish?.({ id: 17, threadId: 99, content: '迟到内容' })
    await flushPromises()
    expect(view.text()).not.toContain('评论发送结果暂未确认')
    expect(view.text()).not.toContain('迟到内容')
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('')
    expect(writes()).toHaveLength(1)
  })

  it('keeps the uncertainty notice and input when the read-only verification fails, then lets the read recover', async () => {
    const view = await open()
    await submit('核对期间保留')
    transport.readFailure = true
    const refresh = () => view.findAll('button').find(button => button.text() === '刷新评论核对')!
    await refresh().trigger('click')
    await flushPromises()
    expect(view.text()).toContain('加载评论失败')
    expect(view.text()).toContain('评论发送结果暂未确认')
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('核对期间保留')
    transport.readFailure = false
    await refresh().trigger('click')
    await flushPromises()
    expect(view.text()).not.toContain('加载评论失败')
    expect(view.text()).toContain('评论发送结果暂未确认')
    expect(view.text()).toContain('原评论')
    expect(writes()).toHaveLength(1)
    expect(reads()).toHaveLength(3)
  })

  it('clears the old uncertainty notice and input on a thread change without resending', async () => {
    const view = await open()
    await submit('旧帖输入')
    expect(view.text()).toContain('评论发送结果暂未确认')
    await view.setProps({ threadId: 43 })
    await flushPromises()
    expect(view.text()).not.toContain('评论发送结果暂未确认')
    expect(view.findComponent(CommentsComposer).props('modelValue')).toBe('')
    expect(writes()).toHaveLength(1)
  })
})

import type { AxiosAdapter, AxiosResponse } from 'axios'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import ThreadCreate from '../ThreadCreate.vue'
import ThreadEdit from '../ThreadEdit.vue'
import { useAuthStore } from '@/stores/auth'

const transport = vi.hoisted(() => ({
  body: null as unknown,
  delayed: false,
  networkFailure: false,
  finish: undefined as ((body: unknown) => void) | undefined,
  calls: [] as Array<{ method?: string; url?: string; data?: unknown }>,
}))
vi.mock('@/api/client', async () => {
  const { default: axios } = await vi.importActual<typeof import('axios')>('axios')
  const adapter: AxiosAdapter = async config => {
    transport.calls.push({ method: config.method, url: config.url, data: config.data })
    const response = (data: unknown): AxiosResponse => ({ data, status: config.method === 'post' ? 201 : 200, statusText: 'OK', headers: {}, config })
    if (config.method === 'get') {
      if (config.url === '/sections') return response({ items: [{ id: 3, name: '分区' }], page: 1, size: 100, total: 1 })
      if (config.url === '/threads/42') return response({ id: 42, title: '原帖', content: '原文', sectionId: 3, authorId: 7 })
      throw new Error('Unexpected read ' + config.url)
    }
    if (transport.networkFailure) throw Object.assign(new Error('timeout'), { isAxiosError: true, code: 'ECONNABORTED', config })
    if (transport.delayed) return await new Promise(resolve => { transport.finish = body => resolve(response(body)) })
    return response(transport.body)
  }
  return { default: axios.create({ baseURL: 'http://offline.invalid/api/v1', adapter }) }
})

const editor = { props: ['modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
let wrapper: VueWrapper | undefined
const changed = vi.fn()
const tagged = vi.fn()

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  useAuthStore().applyAuthState({ accessToken: 'writer-token', user: { id: 7, username: 'writer' } })
  transport.body = null
  transport.delayed = false
  transport.networkFailure = false
  transport.finish = undefined
  transport.calls.length = 0
  changed.mockClear()
  tagged.mockClear()
  window.addEventListener('threads-updated', changed)
  window.addEventListener('thread-tags-updated', tagged)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  window.removeEventListener('threads-updated', changed)
  window.removeEventListener('thread-tags-updated', tagged)
  vi.useRealTimers()
  localStorage.clear()
  sessionStorage.clear()
})

async function mountPage(mode: 'create' | 'edit') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/threads/new', component: { template: '<div />' } },
    { path: '/threads/:id/edit', component: { template: '<div />' } },
    { path: '/threads/:id', component: { template: '<div />' } },
    { path: '/my/threads', component: { template: '<div />' } },
  ] })
  await router.push(mode === 'create' ? '/threads/new' : '/threads/42/edit')
  wrapper = mount(mode === 'create' ? ThreadCreate : ThreadEdit, { global: { plugins: [router], stubs: { MarkdownTextareaEditor: editor } } })
  await flushPromises()
  return router
}
async function click(label: string): Promise<void> {
  const button = wrapper!.findAll('button').find(item => item.text() === label)
  if (!button) throw new Error('Missing button ' + label)
  await button.trigger('click')
  await flushPromises()
}

describe('real thread write transport and editor state', () => {
  it('keeps the creation form and saved draft on an unconfirmed 201, with no automatic repeat', async () => {
    transport.body = '<html>gateway error</html>'
    await mountPage('create')
    await wrapper!.get('#thread-create-section').setValue('3')
    await wrapper!.get('#thread-create-title').setValue('保留标题')
    await wrapper!.get('textarea').setValue('保留正文')
    await click('保存到本机')
    const draft = localStorage.getItem('thread-draft-v1:7')
    expect(draft).not.toBeNull()
    await click('发布')
    expect((wrapper!.get('#thread-create-title').element as HTMLInputElement).value).toBe('保留标题')
    expect((wrapper!.get('textarea').element as HTMLTextAreaElement).value).toBe('保留正文')
    expect(localStorage.getItem('thread-draft-v1:7')).toBe(draft)
    expect(wrapper!.text()).toContain('发布结果暂未确认')
    expect(wrapper!.text()).toContain('避免重复发布')
    expect(wrapper!.text()).not.toContain('发布成功')
    const links = wrapper!.findAll('[data-testid="unconfirmed-create-link"]')
    expect(links).toHaveLength(2)
    expect(links[0].attributes()).toMatchObject({ href: '/my/threads', target: '_blank', rel: 'noopener noreferrer' })
    expect(changed).not.toHaveBeenCalled()
    expect(tagged).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(500)
    expect(transport.calls.filter(call => call.method === 'post')).toHaveLength(1)
    transport.body = { id: 77, title: '保留标题' }
    await click('再次发布')
    expect(transport.calls.filter(call => call.method === 'post')).toHaveLength(2)
    const writes = transport.calls.filter(call => call.method === 'post')
    expect(writes[0]?.data).toBe(writes[1]?.data)
    expect(wrapper!.text()).toContain('发布成功')
    expect(localStorage.getItem('thread-draft-v1:7')).toBeNull()
    expect(changed).toHaveBeenCalledOnce()
    expect(tagged).toHaveBeenCalledOnce()
  })

  it('keeps edit content and prevents success navigation when the response targets another thread', async () => {
    transport.body = { id: 99, title: '别的帖子' }
    const router = await mountPage('edit')
    await wrapper!.get('#thread-edit-title').setValue('未确认修改')
    await click('保存修改')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/threads/42/edit')
    expect((wrapper!.get('#thread-edit-title').element as HTMLInputElement).value).toBe('未确认修改')
    expect(wrapper!.text()).toContain('保存结果暂未确认')
    expect(wrapper!.text()).not.toContain('保存成功')
    expect(wrapper!.findAll('[data-testid="unconfirmed-edit-link"]')[0]?.attributes()).toMatchObject({ href: '/threads/42', target: '_blank' })
    expect(changed).not.toHaveBeenCalled()
    expect(tagged).not.toHaveBeenCalled()
    expect(transport.calls.filter(call => call.method === 'patch')).toHaveLength(1)
    transport.body = { id: 42, title: '未确认修改' }
    await click('再次保存')
    await vi.advanceTimersByTimeAsync(500)
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/threads/42')
    expect(transport.calls.filter(call => call.method === 'patch')).toHaveLength(2)
    expect(changed).toHaveBeenCalledOnce()
  })

  it('preserves creation content and asks for verification after a lost network response', async () => {
    transport.networkFailure = true
    await mountPage('create')
    await wrapper!.get('#thread-create-section').setValue('3')
    await wrapper!.get('#thread-create-title').setValue('超时内容')
    await wrapper!.get('textarea').setValue('正文不丢失')
    await click('发布')
    expect(wrapper!.text()).toContain('发布结果暂未确认')
    expect((wrapper!.get('textarea').element as HTMLTextAreaElement).value).toBe('正文不丢失')
    expect(wrapper!.findAll('[data-testid="unconfirmed-create-link"]')).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(500)
    expect(transport.calls.filter(call => call.method === 'post')).toHaveLength(1)
    expect(changed).not.toHaveBeenCalled()
  })

  it('does not apply a delayed unconfirmed result after another account takes over', async () => {
    transport.delayed = true
    await mountPage('create')
    await wrapper!.get('#thread-create-section').setValue('3')
    await wrapper!.get('#thread-create-title').setValue('旧账号')
    await wrapper!.get('textarea').setValue('旧正文')
    await click('发布')
    useAuthStore().applyAuthState({ accessToken: 'next-writer', user: { id: 8, username: 'next' } })
    await flushPromises()
    transport.finish?.('<html>late error</html>')
    await flushPromises()
    expect(wrapper!.find('[data-testid="unconfirmed-create-link"]').exists()).toBe(false)
    expect(wrapper!.text()).not.toContain('发布结果暂未确认')
    expect(wrapper!.text()).not.toContain('发布成功')
  })
})

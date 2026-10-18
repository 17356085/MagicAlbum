import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ThreadEdit from '../ThreadEdit.vue'

const mocks = vi.hoisted(() => ({ getThread: vi.fn(), updateThread: vi.fn(), push: vi.fn() }))
const route = reactive({ params: { id: '42' } })
vi.mock('@/api/threads', () => ({ getThread: mocks.getThread }))
vi.mock('@/api/my', () => ({ updateThread: mocks.updateThread }))
vi.mock('@/api/sections', () => ({
  listSections: vi.fn().mockResolvedValue({ items: [], page: 1, size: 100, total: 0 }),
}))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: mocks.push }) }))

const editorStub = {
  props: ['modelValue', 'maxLength'],
  template: '<textarea :value="modelValue" :maxlength="maxLength" @input="$emit(\'update:modelValue\', $event.target.value)" />',
}

function createWrapper() {
  return mount(ThreadEdit, {
    global: {
      stubs: { MarkdownTextareaEditor: editorStub },
    },
  })
}

describe('ThreadEdit content boundaries', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers()
    localStorage.clear()
    route.params.id = '42'
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('preserves oversized legacy content and allows saving once reduced to the server limit', async () => {
    mocks.getThread.mockResolvedValue({ id: 42, title: '原有帖子', content: '文'.repeat(10_001) })
    mocks.updateThread.mockResolvedValue({ id: 42 })
    const wrapper = createWrapper()
    await flushPromises()
    const editor = wrapper.get('textarea')
    expect(editor.attributes('maxlength')).toBe('10000')
    const save = wrapper.findAll('button').find((button) => button.text() === '保存修改')!
    await save.trigger('click')
    expect(mocks.updateThread).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('正文不能超过 10000 字符')
    expect(editor.element.value).toHaveLength(10_001)
    await editor.setValue('文'.repeat(10_000))
    await save.trigger('click')
    await flushPromises()
    expect(mocks.updateThread).toHaveBeenCalledWith('42', expect.objectContaining({ content: '文'.repeat(10_000) }))
    expect(wrapper.text()).toContain('保存成功')
    wrapper.unmount()
  })

  it('ignores a late load from the previous route id', async () => {
    let resolveOld!: (value: unknown) => void
    let resolveNew!: (value: unknown) => void
    mocks.getThread
      .mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveNew = resolve }))

    const wrapper = createWrapper()
    await flushPromises()
    route.params.id = '43'
    await flushPromises()
    resolveNew({ id: 43, title: '新帖子', content: '新内容' })
    await flushPromises()
    resolveOld({ id: 42, title: '旧帖子', content: '旧内容' })
    await flushPromises()

    expect((wrapper.get('#thread-edit-title').element as HTMLInputElement).value).toBe('新帖子')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('新内容')
    wrapper.unmount()
  })

  it('does not navigate after a save resolves after unmount', async () => {
    mocks.getThread.mockResolvedValue({ id: 42, title: '原有帖子', content: '原有内容' })
    let resolveSave!: (value: unknown) => void
    mocks.updateThread.mockReturnValue(new Promise((resolve) => { resolveSave = resolve }))

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#thread-edit-title').setValue('修改后的帖子')
    await wrapper.get('button.ui-button-primary').trigger('click')
    wrapper.unmount()
    resolveSave({ id: 42 })
    await flushPromises()
    vi.advanceTimersByTime(500)

    expect(mocks.push).not.toHaveBeenCalled()
  })

  it('keeps a newer auth context from receiving a late save result', async () => {
    mocks.getThread.mockResolvedValue({ id: 42, title: '原有帖子', content: '原有内容' })
    let resolveSave!: (value: unknown) => void
    mocks.updateThread.mockReturnValue(new Promise((resolve) => { resolveSave = resolve }))

    const wrapper = createWrapper()
    await flushPromises()
    const piniaAuth = (await import('@/stores/auth')).useAuthStore()
    piniaAuth.applyAuthState({ accessToken: 'token-old', user: { id: 1, username: 'old' } })
    await flushPromises()
    await wrapper.get('#thread-edit-title').setValue('修改后的帖子')
    await wrapper.get('button.ui-button-primary').trigger('click')
    piniaAuth.applyAuthState({ accessToken: 'token-new', user: { id: 2, username: 'new' } })
    resolveSave({ id: 42 })
    await flushPromises()
    vi.advanceTimersByTime(500)

    expect(wrapper.text()).not.toContain('保存成功')
    expect(mocks.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('navigates to the original thread after a current save succeeds', async () => {
    mocks.getThread.mockResolvedValue({ id: 42, title: '原有帖子', content: '原有内容' })
    mocks.updateThread.mockResolvedValue({ id: 42 })

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#thread-edit-title').setValue('修改后的帖子')
    await wrapper.get('button.ui-button-primary').trigger('click')
    await flushPromises()
    vi.advanceTimersByTime(400)
    await flushPromises()

    expect(mocks.push).toHaveBeenCalledWith('/threads/42')
    wrapper.unmount()
  })
})

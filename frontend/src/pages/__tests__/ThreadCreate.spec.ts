import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ThreadCreate from '../ThreadCreate.vue'
import { createThread } from '@/api/threads'
import { listSections } from '@/api/sections'
import { useAuthStore } from '@/stores/auth'
import type { Thread } from '@/types'

const mocks = vi.hoisted(() => ({
  createThread: vi.fn(),
  listSections: vi.fn(),
}))

vi.mock('@/api/threads', () => ({ createThread: mocks.createThread }))
vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))

const createThreadMock = vi.mocked(createThread)
const listSectionsMock = vi.mocked(listSections)

function createWrapper() {
  return mount(ThreadCreate, {
    global: {
      stubs: {
        MarkdownTextareaEditor: {
          props: ['modelValue'],
          template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        RouterLink: {
          props: ['to'],
          template: '<a :href="String(to)"><slot /></a>',
        },
      },
    },
  })
}

describe('ThreadCreate', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'real-token' })
    listSectionsMock.mockResolvedValue({
      items: [{ id: 3, name: '编程' }],
      page: 1,
      size: 100,
      total: 1,
    })
  })

  it('rejects oversized content restored or inserted programmatically without posting it', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.find('select').setValue('3')
    await wrapper.findAll('input[type="text"]')[0].setValue('过长的帖子')
    await wrapper.find('textarea').setValue('文'.repeat(10_001))
    await wrapper.findAll('button').find((button) => button.text() === '发布')!.trigger('click')
    expect(createThreadMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('正文不能超过 10000 字符')
    expect(wrapper.find('textarea').element.value).toHaveLength(10_001)
    wrapper.unmount()
  })

  it('prevents duplicate direct submits, keeps an empty form, and links to the created thread', async () => {
    let resolveCreate!: (value: Thread) => void
    createThreadMock.mockReturnValueOnce(new Promise<Thread>((resolve) => {
      resolveCreate = resolve
    }))

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.find('select').setValue('3')
    const textInputs = wrapper.findAll('input[type="text"]')
    await textInputs[0].setValue('新帖子标题')
    await textInputs[1].setValue('标签')
    await wrapper.find('textarea').setValue('新帖子正文')

    const publishButton = wrapper.findAll('button').find((button) => button.text() === '发布')
    expect(publishButton).toBeDefined()
    await publishButton!.trigger('click')
    await publishButton!.trigger('click')

    expect(createThreadMock).toHaveBeenCalledTimes(1)
    expect(publishButton!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('发布中...')

    resolveCreate({ id: 42, title: '新帖子标题' })
    await flushPromises()

    expect(wrapper.text()).toContain('发布成功')
    expect(wrapper.get('a').attributes('href')).toBe('/threads/42')
    expect(wrapper.find('select').element.value).toBe('')
    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('')
    expect((wrapper.findAll('input[type="text"]')[1].element as HTMLInputElement).value).toBe('')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('')
    expect(wrapper.text()).toContain('发布')
  })

  it('restores separate drafts for account A, account B, and guest', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
    localStorage.setItem('thread-draft-v1:1', JSON.stringify({ sectionId: '3', title: '账号 A 草稿', content: 'A', tags: '' }))
    localStorage.setItem('thread-draft-v1:2', JSON.stringify({ sectionId: '3', title: '账号 B 草稿', content: 'B', tags: '' }))
    localStorage.setItem('thread-draft-v1:guest', JSON.stringify({ sectionId: '3', title: '访客草稿', content: 'G', tags: '' }))

    const wrapper = createWrapper()
    await flushPromises()
    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('账号 A 草稿')

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'account-b' } })
    await nextTick()
    await flushPromises()
    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('账号 B 草稿')

    auth.clearAuthState()
    await nextTick()
    await flushPromises()
    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('访客草稿')
    expect(localStorage.getItem('thread-draft-v1:1')).toContain('账号 A 草稿')
    expect(localStorage.getItem('thread-draft-v1:2')).toContain('账号 B 草稿')
    wrapper.unmount()
  })

  it('keeps the legacy unowned draft without restoring or migrating it', async () => {
    const legacy = JSON.stringify({ sectionId: '3', title: '旧无归属草稿', content: 'legacy', tags: '' })
    localStorage.setItem('thread-draft-v1', legacy)
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })

    const wrapper = createWrapper()
    await flushPromises()

    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('')
    expect(localStorage.getItem('thread-draft-v1')).toBe(legacy)
    expect(localStorage.getItem('thread-draft-v1:1')).toBeNull()
    wrapper.unmount()
  })

  it('does not restore or persist a shared pending draft when the user ID is unknown', async () => {
    vi.useFakeTimers()
    try {
      const pendingDraft = JSON.stringify({ sectionId: '3', title: '不应共享', content: 'pending', tags: '' })
      localStorage.setItem('thread-draft-v1:pending', pendingDraft)
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-without-user' })

      const wrapper = createWrapper()
      await flushPromises()
      expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('')

      await wrapper.find('select').setValue('3')
      const textInputs = wrapper.findAll('input[type="text"]')
      await textInputs[0].setValue('未知账号内容')
      await wrapper.find('textarea').setValue('不会落盘')
      vi.advanceTimersByTime(1000)
      expect(localStorage.getItem('thread-draft-v1:pending')).toBe(pendingDraft)

      auth.applyAuthState({ accessToken: 'another-unknown-token' })
      await nextTick()
      await flushPromises()
      expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('')
      expect(Object.keys(localStorage).some((key) => key.includes('another-unknown-token'))).toBe(false)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not let a stale publish response clear or reset the newly selected account', async () => {
    let resolveCreate!: (value: Thread) => void
    createThreadMock.mockReturnValueOnce(new Promise<Thread>((resolve) => {
      resolveCreate = resolve
    }))
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
    localStorage.setItem('thread-draft-v1:2', JSON.stringify({ sectionId: '3', title: '账号 B 草稿', content: 'B', tags: '' }))

    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.find('select').setValue('3')
    const textInputs = wrapper.findAll('input[type="text"]')
    await textInputs[0].setValue('账号 A 待发布')
    await wrapper.find('textarea').setValue('A')
    await wrapper.findAll('button').find((button) => button.text() === '发布')!.trigger('click')
    expect(createThreadMock).toHaveBeenCalledTimes(1)

    auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'account-b' } })
    await nextTick()
    await flushPromises()
    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('账号 B 草稿')

    resolveCreate({ id: 77, title: '账号 A 待发布' })
    await flushPromises()

    expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('账号 B 草稿')
    expect(localStorage.getItem('thread-draft-v1:2')).toContain('账号 B 草稿')
    expect(wrapper.text()).not.toContain('发布成功')
    wrapper.unmount()
  })

  it('does not recreate an empty draft after a successful publish', async () => {
    vi.useFakeTimers()
    try {
      createThreadMock.mockResolvedValueOnce({ id: 88, title: '已发布' })
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })

      const wrapper = createWrapper()
      await flushPromises()
      await wrapper.find('select').setValue('3')
      const textInputs = wrapper.findAll('input[type="text"]')
      await textInputs[0].setValue('待发布')
      await wrapper.find('textarea').setValue('正文')
      await wrapper.findAll('button').find((button) => button.text() === '发布')!.trigger('click')
      await flushPromises()
      vi.advanceTimersByTime(1000)

      expect(localStorage.getItem('thread-draft-v1:1')).toBeNull()
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})

import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ThreadCreate from '../ThreadCreate.vue'
import { createThread } from '@/api/threads'
import { listSections } from '@/api/sections'
import { useAuthStore } from '@/stores/auth'
import type { Thread } from '@/types'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'

const mocks = vi.hoisted(() => ({
  createThread: vi.fn(),
  listSections: vi.fn(),
  uploadImage: vi.fn(),
  beautifyMarkdown: vi.fn(),
}))

vi.mock('@/api/threads', () => ({ createThread: mocks.createThread }))
vi.mock('@/api/sections', () => ({ listSections: mocks.listSections }))
vi.mock('@/api/uploads', () => ({ uploadImage: mocks.uploadImage }))
vi.mock('@/composables/useMarkdownTools', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/composables/useMarkdownTools')>(),
  beautifyMarkdown: mocks.beautifyMarkdown,
}))

const createThreadMock = vi.mocked(createThread)
const listSectionsMock = vi.mocked(listSections)

function createWrapper() {
  return mount(ThreadCreate, {
    global: {
      stubs: {
        MarkdownTextareaEditor: {
          props: ['modelValue', 'uploadImage', 'disabled', 'previewDefaultCollapsed', 'mobilePreviewExclusive'],
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

  it('starts the post editor in mobile-first edit mode', async () => {
    const wrapper = createWrapper()
    await flushPromises()
    expect(wrapper.findComponent(MarkdownTextareaEditor).props()).toMatchObject({
      previewDefaultCollapsed: true,
      mobilePreviewExclusive: true,
    })
    wrapper.unmount()
  })

  it('blocks publishing during upload and preserves the body after upload failure', async () => {
    let rejectUpload!: (reason: Error) => void
    mocks.uploadImage.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectUpload = reject }))
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.find('textarea').setValue('保留这段正文')
    const upload = wrapper.findComponent(MarkdownTextareaEditor).props('uploadImage')!
    const pending = upload(new File(['image'], 'image.png', { type: 'image/png' }))
    await nextTick()
    const publish = wrapper.findAll('button').find((button) => button.text() === '发布')!
    expect(publish.attributes('disabled')).toBeDefined()
    await publish.trigger('click')
    expect(createThreadMock).not.toHaveBeenCalled()
    rejectUpload(new Error('网络失败'))
    expect(await pending).toBe('')
    await nextTick()
    expect(publish.attributes('disabled')).toBeUndefined()
    expect(wrapper.get('textarea').element.value).toBe('保留这段正文')
    expect(wrapper.text()).toContain('网络失败')
    wrapper.unmount()
  })

  it('does not replace newer typing with an old beautification result', async () => {
    let resolveFormat!: (value: string) => void
    mocks.beautifyMarkdown.mockReturnValueOnce(new Promise<string>((resolve) => { resolveFormat = resolve }))
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.find('textarea').setValue('旧正文')
    await wrapper.findAll('button').find((button) => button.text() === '美化内容')!.trigger('click')
    expect(wrapper.findAll('button').find((button) => button.text() === '发布')!.attributes('disabled')).toBeDefined()
    await wrapper.find('textarea').setValue('继续输入的新正文')
    resolveFormat('格式化后的旧正文')
    await flushPromises()
    expect(wrapper.find('textarea').element.value).toBe('继续输入的新正文')
    expect(wrapper.findAll('button').find((button) => button.text() === '发布')!.attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })

  it('does not create new message timers when beautification finishes after leaving', async () => {
    vi.useFakeTimers()
    try {
      let resolve!: (value: string) => void
      mocks.beautifyMarkdown.mockReturnValueOnce(new Promise<string>((yes) => { resolve = yes }))
      const wrapper = createWrapper()
      await flushPromises()
      await wrapper.find('textarea').setValue('正文')
      await wrapper.findAll('button').find((button) => button.text() === '美化内容')!.trigger('click')
      wrapper.unmount()
      expect(vi.getTimerCount()).toBe(0)
      resolve('已美化')
      await flushPromises()
      expect(vi.getTimerCount()).toBe(0)
    } finally { vi.useRealTimers() }
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

  it('flushes the latest input when leaving before the debounce elapses', async () => {
    vi.useFakeTimers()
    try {
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
      const wrapper = createWrapper()
      await flushPromises()
      await wrapper.find('select').setValue('3')
      const textInputs = wrapper.findAll('input[type="text"]')
      await textInputs[0].setValue('离页前最后标题')
      await wrapper.find('textarea').setValue('离页前最后正文')

      wrapper.unmount()

      expect(JSON.parse(localStorage.getItem('thread-draft-v1:1') || '{}')).toMatchObject({
        sectionId: 3,
        title: '离页前最后标题',
        content: '离页前最后正文',
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('flushes account A before switching to account B', async () => {
    vi.useFakeTimers()
    try {
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
      const accountBDraft = JSON.stringify({ sectionId: '3', title: '账号 B 草稿', content: 'B', tags: '' })
      localStorage.setItem('thread-draft-v1:2', accountBDraft)
      const wrapper = createWrapper()
      await flushPromises()
      await wrapper.find('select').setValue('3')
      await wrapper.findAll('input[type="text"]')[0].setValue('账号 A 最后输入')
      await wrapper.find('textarea').setValue('A')

      auth.applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'account-b' } })
      await nextTick()
      await flushPromises()

      expect(JSON.parse(localStorage.getItem('thread-draft-v1:1') || '{}')).toMatchObject({
        title: '账号 A 最后输入',
        content: 'A',
      })
      expect(localStorage.getItem('thread-draft-v1:2')).toBe(accountBDraft)
      expect((wrapper.findAll('input[type="text"]')[0].element as HTMLInputElement).value).toBe('账号 B 草稿')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('flushes a pending draft on pagehide and leaves no empty draft after restore', async () => {
    vi.useFakeTimers()
    try {
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
      const restored = JSON.stringify({ sectionId: '3', title: '恢复标题', content: '恢复正文', tags: '' })
      localStorage.setItem('thread-draft-v1:1', restored)
      const wrapper = createWrapper()
      await flushPromises()

      expect(localStorage.getItem('thread-draft-v1:1')).toBe(restored)
      await wrapper.find('textarea').setValue('离页前更新')
      window.dispatchEvent(new Event('pagehide'))

      expect(JSON.parse(localStorage.getItem('thread-draft-v1:1') || '{}')).toMatchObject({ content: '离页前更新' })
      wrapper.unmount()
      expect(JSON.parse(localStorage.getItem('thread-draft-v1:1') || '{}')).toMatchObject({ content: '离页前更新' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not claim manual save success when storage rejects the write', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'account-a' } })
    const wrapper = createWrapper()
    await flushPromises()
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })

    await wrapper.findAll('button').find((button) => button.text() === '保存到本机')!.trigger('click')

    expect(wrapper.text()).toContain('草稿保存失败')
    expect(wrapper.text()).not.toContain('草稿已保存到本机浏览器')
    setItem.mockRestore()
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

import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ThreadCreate from '../ThreadCreate.vue'
import { createThread } from '@/api/threads'
import { listSections } from '@/api/sections'
import { useAuthStore } from '@/stores/auth'

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

  it('prevents duplicate direct submits, keeps an empty form, and links to the created thread', async () => {
    let resolveCreate!: (value: any) => void
    createThreadMock.mockReturnValueOnce(new Promise<any>((resolve) => {
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
})

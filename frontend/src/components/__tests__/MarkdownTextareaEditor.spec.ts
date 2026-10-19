import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MarkdownTextareaEditor from '../MarkdownTextareaEditor.vue'

vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: () => ({}),
  renderMarkdown: (_renderer: unknown, value: string) => value || '',
}))

describe('MarkdownTextareaEditor preview defaults', () => {
  function deferred() {
    let resolve!: (url: string) => void
    let reject!: (error: Error) => void
    const promise = new Promise<string>((yes, no) => { resolve = yes; reject = no })
    return { promise, resolve, reject }
  }

  async function beginUpload(wrapper: ReturnType<typeof mount>) {
    await wrapper.findAll('button').find((button) => button.text() === '图片')!.trigger('click')
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['png'], 'image.png')] })
    await input.trigger('change')
  }

  it('ignores an upload completing after its image panel was cancelled', async () => {
    const upload = deferred()
    const wrapper = mount(MarkdownTextareaEditor, { props: { modelValue: '正文', uploadImage: () => upload.promise } })
    await beginUpload(wrapper)
    await wrapper.findAll('button').find((button) => button.text() === '取消')!.trigger('click')
    upload.resolve('/uploads/image.png')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.text()).not.toContain('插入图片')
    wrapper.unmount()
  })

  it('keeps new typing and waits for confirmation at a fresh selection after upload', async () => {
    const upload = deferred()
    const wrapper = mount(MarkdownTextareaEditor, { props: { modelValue: '正文', uploadImage: () => upload.promise } })
    await beginUpload(wrapper)
    await wrapper.setProps({ modelValue: '保留新输入' })
    wrapper.get('textarea').element.setSelectionRange(5, 5)
    upload.resolve('/uploads/image.png')
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.text()).toContain('正文已变化')
    await wrapper.findAll('button').find((button) => button.text() === '插入图片')!.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toContain('保留新输入')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toContain('/uploads/image.png')
    wrapper.unmount()
  })

  it.each(['resolve', 'reject'] as const)('does not emit after unmount and late upload %s', async (outcome) => {
    const upload = deferred()
    const wrapper = mount(MarkdownTextareaEditor, { props: { modelValue: '正文', uploadImage: () => upload.promise } })
    await beginUpload(wrapper)
    wrapper.unmount()
    if (outcome === 'resolve') upload.resolve('/uploads/image.png')
    else upload.reject(new Error('late failure'))
    await flushPromises()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('blocks input and upload while disabled', async () => {
    const upload = vi.fn()
    const wrapper = mount(MarkdownTextareaEditor, { props: { modelValue: '正文', disabled: true, uploadImage: upload } })
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)
    await wrapper.get('textarea').trigger('input')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(upload).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('keeps the existing open preview default for other editor instances', () => {
    const wrapper = mount(MarkdownTextareaEditor, { props: { modelValue: '# 内容' } })

    expect(wrapper.find('.prose').exists()).toBe(true)
  })

  it('supports an initially collapsed preview that can be toggled open', async () => {
    const wrapper = mount(MarkdownTextareaEditor, {
      props: { modelValue: '# 简介', previewDefaultCollapsed: true },
    })

    expect(wrapper.find('.prose').exists()).toBe(false)
    await wrapper.findAll('button').find((button) => button.text() === '预览')!.trigger('click')
    expect(wrapper.find('.prose').exists()).toBe(true)
  })

  it('keeps the primary mobile tools compact and progressively reveals other formats', async () => {
    const wrapper = mount(MarkdownTextareaEditor, { props: { modelValue: '正文' } })

    expect(wrapper.findAll('button').find((button) => button.text() === '粗体')).toBeDefined()
    expect(wrapper.findAll('button').find((button) => button.text() === '链接')).toBeDefined()
    expect(wrapper.findAll('button').find((button) => button.text() === '图片')).toBeDefined()
    expect(wrapper.find('.markdown-editor-more-tools').classes()).toContain('hidden')
    const moreButton = wrapper.findAll('button').find((button) => button.text() === '更多')!
    expect(moreButton.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.markdown-editor-more-tools').attributes('id')).toBe(moreButton.attributes('aria-controls'))

    await moreButton.trigger('click')
    expect(moreButton.attributes('aria-expanded')).toBe('true')

    expect(wrapper.find('.markdown-editor-more-tools').classes()).toContain('flex')
    expect(wrapper.text()).toContain('斜体')
    wrapper.unmount()
  })

  it('can make the editor and preview mutually exclusive on mobile when opted in', async () => {
    const wrapper = mount(MarkdownTextareaEditor, {
      props: { modelValue: '# 简介', previewDefaultCollapsed: true, mobilePreviewExclusive: true },
    })

    expect(wrapper.find('textarea').element.parentElement?.className).not.toContain('hidden')
    const previewToggle = wrapper.findAll('button').find((button) => button.text() === '预览')!
    await previewToggle.trigger('click')
    expect(wrapper.find('textarea').element.parentElement?.className).toContain('hidden')
    expect(wrapper.find('.prose').element.parentElement?.className).toContain('lg:block')
    expect(wrapper.findAll('button').find((button) => button.text().includes('返回编辑'))).toBeDefined()

    await wrapper.findAll('button').find((button) => button.text().includes('返回编辑'))!.trigger('click')
    expect(wrapper.find('textarea').element.parentElement?.className).not.toContain('hidden')
    expect(wrapper.find('.prose').exists()).toBe(false)
  })
})

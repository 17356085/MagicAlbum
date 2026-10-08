import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MarkdownTextareaEditor from '../MarkdownTextareaEditor.vue'

vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: () => ({}),
  renderMarkdown: (_renderer: unknown, value: string) => value || '',
}))

describe('MarkdownTextareaEditor preview defaults', () => {
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

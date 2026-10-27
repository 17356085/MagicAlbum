import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CommentBody from '../CommentBody.vue'

const mocks = vi.hoisted(() => {
  const renderer = { name: 'comment-renderer' }
  return {
    createMarkdownRenderer: vi.fn(() => renderer),
    renderMarkdown: vi.fn(),
    renderer,
  }
})

vi.mock('@/utils/markdown', () => ({
  createMarkdownRenderer: mocks.createMarkdownRenderer,
  renderMarkdown: mocks.renderMarkdown,
}))

const renderer = mocks.renderer

const Host = defineComponent({
  components: { CommentBody },
  props: {
    content: { type: String, default: '' },
    tick: { type: Number, default: 0 },
  },
  template: '<div><span>{{ tick }}</span><CommentBody :content="content" /></div>',
})

const PairHost = defineComponent({
  components: { CommentBody },
  props: {
    first: { type: String, default: '' },
    second: { type: String, default: '' },
  },
  template: '<div><CommentBody :content="first" /><CommentBody :content="second" /></div>',
})

describe('CommentBody', () => {
  beforeEach(() => {
    mocks.renderMarkdown.mockReset()
    mocks.renderMarkdown.mockImplementation((_renderer: unknown, content: string | undefined | null) => `<p>${content || ''}</p>`)
  })

  it('renders through the configured safe Markdown renderer and keeps it cached for unrelated parent updates', async () => {
    mocks.renderMarkdown.mockReturnValue('<p>安全输出</p>')
    const wrapper = mount(Host, { props: { content: '<script>alert(1)</script>', tick: 0 } })

    expect(mocks.createMarkdownRenderer).toHaveBeenCalledWith({ katex: true, normalizeImages: true })
    expect(mocks.renderMarkdown).toHaveBeenCalledTimes(1)
    expect(mocks.renderMarkdown).toHaveBeenCalledWith(renderer, '<script>alert(1)</script>')
    expect(wrapper.find('.prose').html()).toContain('<p>安全输出</p>')
    expect(wrapper.find('.prose').html()).not.toContain('<script>')

    await wrapper.setProps({ tick: 1 })
    await nextTick()

    expect(mocks.renderMarkdown).toHaveBeenCalledTimes(1)
  })

  it('re-renders only when the body changes', async () => {
    const wrapper = mount(Host, { props: { content: '第一版', tick: 0 } })

    await wrapper.setProps({ content: '第二版' })
    await nextTick()

    expect(mocks.renderMarkdown).toHaveBeenCalledTimes(2)
    expect(mocks.renderMarkdown).toHaveBeenLastCalledWith(renderer, '第二版')
    expect(wrapper.find('.prose').html()).toContain('<p>第二版</p>')
  })

  it('keeps different comment bodies independent', async () => {
    const wrapper = mount(PairHost, { props: { first: '根评论', second: '子评论' } })

    expect(mocks.renderMarkdown).toHaveBeenCalledTimes(2)
    await wrapper.setProps({ first: '更新后的根评论' })
    await nextTick()

    expect(mocks.renderMarkdown).toHaveBeenCalledTimes(3)
    expect(mocks.renderMarkdown).toHaveBeenLastCalledWith(renderer, '更新后的根评论')
    expect(wrapper.findAll('.prose')[1].html()).toContain('<p>子评论</p>')
  })
})

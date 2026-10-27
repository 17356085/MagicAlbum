import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import hljs from 'highlight.js/lib/common'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Thread } from '@/types'
import ThreadDetailContent from '../ThreadDetailContent.vue'

enableAutoUnmount(afterEach)
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function thread(content: string, contentHtml?: string): Thread {
  return { id: 1, title: '正文验证', content, contentHtml, sectionId: 1, createdAt: '2026-10-23T00:00:00Z' }
}

describe('ThreadDetailContent rendering boundary', () => {
  it('cleans backend HTML and highlights its code once through the mounted component', async () => {
    const highlight = vi.spyOn(hljs, 'highlightElement')
    const wrapper = mount(ThreadDetailContent, { props: { thread: thread('',
      '<script>alert(1)</script><a href="javascript:alert(2)">链接</a>' +
      '<img src="/uploads/image.png" onerror="alert(3)">' +
      '<pre><code class="language-js">const value = 1</code></pre>') } })
    await flushPromises()

    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.find('[onerror]').exists()).toBe(false)
    expect(wrapper.get('a').attributes('href')).toBeUndefined()
    expect(wrapper.get('img').attributes('loading')).toBe('lazy')
    expect(wrapper.get('code').text()).toBe('const value = 1')
    expect(highlight).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('button[aria-label="复制代码"]')).toHaveLength(1)

    await wrapper.setProps({ thread: { ...wrapper.props('thread'), title: '更新标题' } })
    await flushPromises()
    expect(highlight).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.code-copy-btn')).toHaveLength(1)
  })

  it('uses current Markdown over stale backend HTML and keeps math, safe images and highlighted code', async () => {
    const highlight = vi.spyOn(hljs, 'highlightElement')
    const wrapper = mount(ThreadDetailContent, { props: { thread: thread(
      '$x^2$\n\n```js\nconst current = 2\n```\n\n![图](/uploads/current.png)\n\n<script>alert(1)</script>',
      '<p>旧版本正文</p>') } })
    await flushPromises()

    expect(wrapper.find('.katex').exists()).toBe(true)
    expect(wrapper.get('code').text()).toBe('const current = 2')
    expect(wrapper.get('img').attributes('loading')).toBe('lazy')
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('旧版本正文')
    expect(highlight).not.toHaveBeenCalled()
    expect(wrapper.findAll('.code-copy-btn')).toHaveLength(1)
  })

  it('copies the current code after content replacement without duplicating controls', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mount(ThreadDetailContent, { props: { thread: thread('```js\nconst old = 1\n```') } })
    await flushPromises()
    await wrapper.setProps({ thread: thread('```js\nconst current = 2\n```') })
    await flushPromises()

    expect(wrapper.findAll('.code-copy-btn')).toHaveLength(1)
    vi.useFakeTimers()
    await wrapper.get('.code-copy-btn').trigger('click')
    expect(writeText).toHaveBeenCalledExactlyOnceWith('const current = 2\n')
    expect(wrapper.get('.code-copy-btn').text()).toBe('已复制')
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.get('.code-copy-btn').text()).toBe('复制')
  })
})

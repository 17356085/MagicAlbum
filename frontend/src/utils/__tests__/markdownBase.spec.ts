import { describe, expect, it } from 'vitest'
import { createBaseMarkdownRenderer, renderInlineMarkdown, renderMarkdown } from '../markdownBase'

function parse(markup: string): DocumentFragment {
  const template = document.createElement('template')
  template.innerHTML = markup
  return template.content
}

describe('lightweight Markdown rendering', () => {
  it('preserves title formatting and links without treating titles as HTML', () => {
    const renderer = createBaseMarkdownRenderer({ html: false, breaks: false })
    const fragment = parse(renderInlineMarkdown(renderer, '**讨论** ~~旧版~~ `代码` [链接](https://example.com) <img src=x>'))

    expect(fragment.querySelector('strong')?.textContent).toBe('讨论')
    expect(fragment.querySelector('s')?.textContent).toBe('旧版')
    expect(fragment.querySelector('code')?.textContent).toBe('代码')
    expect(fragment.querySelector('a')?.getAttribute('href')).toBe('https://example.com')
    expect(fragment.querySelector('img')).toBeNull()
    expect(fragment.textContent).toContain('<img src=x>')
  })

  it('sanitizes executable HTML in both rendering paths', () => {
    const renderer = createBaseMarkdownRenderer()
    for (const render of [renderMarkdown, renderInlineMarkdown]) {
      const fragment = parse(render(renderer, '<img src=x onerror="alert(1)"><script>alert(2)</script>'))
      expect(fragment.querySelector('[onerror]')).toBeNull()
      expect(fragment.querySelector('script')).toBeNull()
    }
  })

  it('keeps math and fenced code readable without enabling heavy plugins', () => {
    const renderer = createBaseMarkdownRenderer()
    const fragment = parse(renderMarkdown(renderer, '$x^2$\n\n```js\nconst value = 1\n```'))
    expect(fragment.textContent).toContain('$x^2$')
    expect(fragment.querySelector('.katex')).toBeNull()
    expect(fragment.querySelector('pre code')?.textContent).toContain('const value = 1')
  })
})

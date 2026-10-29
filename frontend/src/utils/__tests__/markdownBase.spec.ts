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

  it('preserves escaped punctuation and literal entities in image alt text', () => {
    const renderer = createBaseMarkdownRenderer()
    const fragment = parse(renderMarkdown(renderer, String.raw`![封面 \] &amp;copy; \\ 示例](https://example.com/image.png)`))
    expect(fragment.querySelector('img')?.getAttribute('alt')).toBe('封面 ] &copy; \\ 示例')
  })

  it('preserves escaped text inside nested image labels', () => {
    const renderer = createBaseMarkdownRenderer()
    const markdown = String.raw`![外 ![内\]文](/uploads/inner.png)](/uploads/outer.png)`
    const fragment = parse(renderMarkdown(renderer, markdown))
    expect(fragment.querySelectorAll('img')).toHaveLength(1)
    expect(fragment.querySelector('img')?.getAttribute('alt')).toBe('外 内]文')
  })

  it('keeps math and fenced code readable without enabling heavy plugins', () => {
    const renderer = createBaseMarkdownRenderer()
    const fragment = parse(renderMarkdown(renderer, '$x^2$\n\n```js\nconst value = 1\n```'))
    expect(fragment.textContent).toContain('$x^2$')
    expect(fragment.querySelector('.katex')).toBeNull()
    expect(fragment.querySelector('pre code')?.textContent).toContain('const value = 1')
  })
})

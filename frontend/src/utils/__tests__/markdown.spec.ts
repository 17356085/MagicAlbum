import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMarkdownRenderer, renderInlineMarkdown, renderMarkdown } from '../markdown'

function parsed(markup: string): DocumentFragment {
  const template = document.createElement('template')
  template.innerHTML = markup
  return template.content
}

afterEach(() => vi.unstubAllEnvs())

describe('Markdown rendering boundaries', () => {
  it('removes executable user HTML from block and inline rendering', () => {
    const renderer = createMarkdownRenderer()
    const input = '<script>alert(1)</script><img src="/image.png" onerror="alert(2)"><a href="javascript:alert(3)">link</a>'
    for (const render of [renderMarkdown, renderInlineMarkdown]) {
      const fragment = parsed(render(renderer, input))
      expect(fragment.querySelector('script')).toBeNull()
      expect(fragment.querySelector('[onerror]')).toBeNull()
      expect(fragment.querySelector('a')?.getAttribute('href')).toBeNull()
    }
  })

  it('keeps normalized Markdown images lazy and responsive', () => {
    vi.stubEnv('VITE_API_BASE_URL', '/api/v1')
    const renderer = createMarkdownRenderer({ normalizeImages: true })
    const image = parsed(renderMarkdown(renderer, '![测试图片](/uploads/test.png)')).querySelector('img')!
    expect(image.getAttribute('src')).toBe('/uploads/test.png')
    expect(image.getAttribute('loading')).toBe('lazy')
    expect(image.classList.contains('max-w-full')).toBe(true)
    expect(image.classList.contains('h-auto')).toBe(true)
    expect(image.getAttribute('alt')).toBe('测试图片')
  })

  it('preserves KaTeX and highlighted code through the safe rendering path', () => {
    const renderer = createMarkdownRenderer({ katex: true })
    const fragment = parsed(renderMarkdown(renderer, '$x^2$\n\n```js\nconst value = 1\n```'))
    expect(fragment.querySelector('.katex')).not.toBeNull()
    expect(fragment.querySelector('pre code')?.classList.contains('hljs')).toBe(true)
    expect(fragment.querySelector('pre code')?.textContent).toContain('const value = 1')
  })

  it('escapes invalid math and prevents trusted HTML commands from injecting elements', () => {
    const renderer = createMarkdownRenderer({ katex: true })
    const fragment = parsed(renderMarkdown(renderer,
      '$\\unknowncommand{<img src=x onerror=alert(1)>}$\n\n$$\\href{javascript:alert(1)}{click}$$'))
    expect(fragment.querySelector('img')).toBeNull()
    expect(fragment.querySelector('[onerror]')).toBeNull()
    expect(fragment.querySelector('a[href^="javascript:"]')).toBeNull()
    expect(fragment.textContent).toContain('unknowncommand')
  })

  it('renders soft-broken emails and unknown URI schemes without losing their content', () => {
    const renderer = createMarkdownRenderer({ highlight: false })
    const fragment = parsed(renderMarkdown(renderer, 'a@b.co\n'.repeat(2000) + '\n\n' + 'a://'.repeat(2000)))
    expect(fragment.querySelectorAll('a[href="mailto:a@b.co"]')).toHaveLength(2000)
    expect(fragment.textContent).toContain('a://'.repeat(2000))
  })
})

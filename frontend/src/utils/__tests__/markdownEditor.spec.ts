import { describe, expect, it } from 'vitest'
import { createMarkdownImage, createMarkdownLink, replaceSelection } from '../markdownEditor'
import { createBaseMarkdownRenderer, renderMarkdown } from '../markdownBase'

function parse(markup: string): DocumentFragment {
  const template = document.createElement('template')
  template.innerHTML = markup
  return template.content
}

function renderImage(markdown: string): DocumentFragment {
  return parse(renderMarkdown(createBaseMarkdownRenderer(), markdown))
}

describe('markdown editor replacements', () => {
  it('replaces selected text with a markdown link', () => {
    const result = replaceSelection('查看文档', createMarkdownLink('https://example.com', '文档'), { start: 2, end: 4 })

    expect(result.value).toBe('查看[文档](https://example.com)')
    expect(result.selection).toEqual({ start: 2, end: 27 })
  })

  it('inserts a markdown link at the cursor when there is no selection', () => {
    const result = replaceSelection('前后', createMarkdownLink('https://example.com', '链接'), { start: 1, end: 1 })

    expect(result.value).toBe('前[链接](https://example.com)后')
    expect(result.selection).toEqual({ start: 1, end: 26 })
  })

  it('replaces selected text with a markdown image', () => {
    const result = replaceSelection('封面图', createMarkdownImage('https://example.com/cover.png', '封面'), { start: 0, end: 3 })

    expect(result.value).toBe('![封面](https://example.com/cover.png)')
    expect(result.selection).toEqual({ start: 0, end: 36 })
  })

  it('inserts a markdown image at the cursor when there is no selection', () => {
    const result = replaceSelection('前后', createMarkdownImage('https://example.com/cover.png', '图片'), { start: 1, end: 1 })

    expect(result.value).toBe('前![图片](https://example.com/cover.png)后')
    expect(result.selection).toEqual({ start: 1, end: 37 })
  })

  it('keeps ordinary image construction unchanged', () => {
    expect(createMarkdownImage('https://example.com/cover.png', '封面')).toBe('![封面](https://example.com/cover.png)')
  })

  it.each([
    ['https://cdn.example/image(variant).png', 'https://cdn.example/image(variant).png'],
    ['https://cdn.example/image).png', 'https://cdn.example/image).png'],
    // markdown-it normalizes literal square brackets in a URL to their percent-encoded form.
    ['https://cdn.example/image)![](https://evil.example/x.png', 'https://cdn.example/image)!%5B%5D(https://evil.example/x.png'],
    ['https://cdn.example/image.png?x=1&part=two#section', 'https://cdn.example/image.png?x=1&part=two#section'],
    ['https://cdn.example/image%20already%2Fencoded.png?x=%26#frag%20ment', 'https://cdn.example/image%20already%2Fencoded.png?x=%26#frag%20ment'],
    ['https://cdn.example/image with spaces.png', 'https://cdn.example/image%20with%20spaces.png'],
    ['https://cdn.example/a&copy;foo', 'https://cdn.example/a&copy;foo'],
    ['https://cdn.example/a&amp;foo', 'https://cdn.example/a&amp;foo'],
  ])('renders one complete image for URL %s', (url, expectedSrc) => {
    const fragment = renderImage(createMarkdownImage(url, 'image'))
    const images = fragment.querySelectorAll('img')

    expect(images).toHaveLength(1)
    expect(fragment.querySelectorAll('a')).toHaveLength(0)
    expect(images[0]?.getAttribute('src')).toBe(expectedSrc)
    expect(images[0]?.getAttribute('alt')).toBe('image')
  })

  it('preserves backslashes and Markdown delimiters in image alt text', () => {
    const alt = String.raw`alt [text] with a \ slash &copy; &amp;`
    const fragment = renderImage(createMarkdownImage('https://example.com/cover.png', alt))
    const image = fragment.querySelector('img')

    expect(fragment.querySelectorAll('img')).toHaveLength(1)
    expect(image?.getAttribute('alt')).toBe(alt)
  })

  it('keeps an alt label containing link syntax inside one image', () => {
    const alt = 'a](https://evil.example)![other'
    const fragment = renderImage(createMarkdownImage('/uploads/image.png', alt))
    expect(fragment.querySelectorAll('img')).toHaveLength(1)
    expect(fragment.querySelectorAll('a')).toHaveLength(0)
    expect(fragment.querySelector('img')?.getAttribute('alt')).toBe(alt)
    expect(fragment.querySelector('img')?.getAttribute('src')).toBe('/uploads/image.png')
  })
})

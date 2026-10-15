import { describe, expect, it } from 'vitest'
import { createMarkdownImage, createMarkdownLink, replaceSelection } from '../markdownEditor'

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
})

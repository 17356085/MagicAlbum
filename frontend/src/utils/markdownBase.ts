import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

export interface MarkdownBaseOptions {
  html?: boolean
  breaks?: boolean
}

/** Shared safe rendering without code highlighting or mathematical typesetting. */
export function createBaseMarkdownRenderer(options: MarkdownBaseOptions = {}): MarkdownIt {
  return new MarkdownIt({
    html: options.html ?? true,
    linkify: true,
    breaks: options.breaks ?? true,
    langPrefix: 'language-',
  })
}

export function sanitizeHtml(html: string | undefined | null): string {
  return DOMPurify.sanitize(String(html || ''))
}

export function renderMarkdown(md: MarkdownIt, raw: string | undefined | null): string {
  return sanitizeHtml(md.render(String(raw || '')))
}

export function renderInlineMarkdown(md: MarkdownIt, raw: string | undefined | null): string {
  return sanitizeHtml(md.renderInline(String(raw || '')))
}

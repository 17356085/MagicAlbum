import DOMPurify from 'dompurify'
import MarkdownIt from 'markdown-it'

export interface MarkdownBaseOptions {
  html?: boolean
  breaks?: boolean
}

interface MarkdownToken {
  type: string
  children: MarkdownToken[] | null
}

function normalizeImageAltTokens(tokens: readonly MarkdownToken[]): void {
  for (const token of tokens) {
    if (token.type === 'text_special') token.type = 'text'
    if (token.type === 'image' && token.children) normalizeImageAltTokens(token.children)
  }
}

/** Shared safe rendering without code highlighting or mathematical typesetting. */
export function createBaseMarkdownRenderer(options: MarkdownBaseOptions = {}): MarkdownIt {
  const renderer = new MarkdownIt({
    html: options.html ?? true,
    linkify: true,
    breaks: options.breaks ?? true,
    langPrefix: 'language-',
  })
  // markdown-it's text_join normalizes inline tokens but does not descend into
  // image children. Its default alt renderer otherwise drops escaped text.
  renderer.core.ruler.after('text_join', 'image_alt_text', (state) => {
    for (const block of state.tokens) {
      if (block.type !== 'inline' || !block.children) continue
      for (const token of block.children) {
        if (token.type === 'image' && token.children) normalizeImageAltTokens(token.children)
      }
    }
  })
  return renderer
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

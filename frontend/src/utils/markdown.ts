import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import MarkdownIt from 'markdown-it'
import markdownItKatex from 'markdown-it-katex'
import 'katex/dist/katex.min.css'
import { normalizeImageUrl } from '@/utils/image'

interface MarkdownRendererOptions {
  html?: boolean
  breaks?: boolean
  katex?: boolean
  highlight?: boolean
  normalizeImages?: boolean
}

function renderHighlightedCode(md: MarkdownIt, str: string, lang: string): string {
  if (lang && hljs.getLanguage(lang)) {
    try {
      const out = hljs.highlight(str, { language: lang, ignoreIllegals: true }).value
      return `<pre><code class="hljs language-${lang}">${out}</code></pre>`
    } catch (_) {}
  } else {
    try {
      const auto = hljs.highlightAuto(str)
      const langGuess = auto.language ? ` language-${auto.language}` : ''
      return `<pre><code class="hljs${langGuess}">${auto.value}</code></pre>`
    } catch (_) {}
  }

  return `<pre><code class="hljs">${md.utils.escapeHtml(str)}</code></pre>`
}

function installImageRule(md: MarkdownIt): void {
  const defaultImageRule = md.renderer.rules.image || function(tokens, idx, options, env, self) {
    return self.renderToken(tokens, idx, options)
  }

  md.renderer.rules.image = function(tokens, idx, options, env, self) {
    const token = tokens[idx]
    const loadingIdx = token.attrIndex('loading')
    if (loadingIdx < 0) token.attrPush(['loading', 'lazy'])

    const classIdx = token.attrIndex('class')
    if (classIdx < 0) token.attrPush(['class', 'max-w-full h-auto'])
    else token.attrs[classIdx][1] += ' max-w-full h-auto'

    const srcIdx = token.attrIndex('src')
    if (srcIdx >= 0) {
      token.attrs[srcIdx][1] = normalizeImageUrl(token.attrs[srcIdx][1])
    }

    return defaultImageRule(tokens, idx, options, env, self)
  }
}

export function sanitizeHtml(html: string | undefined | null): string {
  return DOMPurify.sanitize(String(html || ''))
}

export function createMarkdownRenderer(options: MarkdownRendererOptions = {}): MarkdownIt {
  const markdownOptions: ConstructorParameters<typeof MarkdownIt>[0] = {
    html: options.html ?? true,
    linkify: true,
    breaks: options.breaks ?? true,
    langPrefix: 'language-',
  }

  const md = new MarkdownIt(markdownOptions)

  if (options.highlight !== false) {
    md.options.highlight = (str, lang) => renderHighlightedCode(md, str, lang)
  }

  try {
    md.enable(['strikethrough'])
  } catch (_) {}

  if (options.katex) {
    md.use(markdownItKatex)
  }

  if (options.normalizeImages) {
    installImageRule(md)
  }

  return md
}

export function renderMarkdown(md: MarkdownIt, raw: string | undefined | null): string {
  return sanitizeHtml(md.render(String(raw || '')))
}

export function renderInlineMarkdown(md: MarkdownIt, raw: string | undefined | null): string {
  return sanitizeHtml(md.renderInline(String(raw || '')))
}

export function applyRuntimeHighlight(root: HTMLElement | null): void {
  if (!root) return

  const codeNodes = root.querySelectorAll<HTMLElement>('pre code')
  codeNodes.forEach((node) => {
    try {
      hljs.highlightElement(node)
    } catch (_) {}
  })

  const images = root.querySelectorAll<HTMLImageElement>('img')
  images.forEach((img) => {
    try {
      const src = img.getAttribute('src') || ''
      const fixed = normalizeImageUrl(src)
      if (fixed && fixed !== src) img.setAttribute('src', fixed)
      img.classList.add('max-w-full', 'h-auto')
      img.setAttribute('loading', img.getAttribute('loading') || 'lazy')
    } catch (_) {}
  })
}

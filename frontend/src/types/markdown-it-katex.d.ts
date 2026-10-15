declare module 'markdown-it-katex' {
  import type MarkdownIt from 'markdown-it'
  import type { KatexOptions } from 'katex'

  const markdownItKatex: MarkdownIt.PluginWithOptions<KatexOptions>

  export default markdownItKatex
}

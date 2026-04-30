<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { applyRuntimeHighlight, createMarkdownRenderer, renderMarkdown, sanitizeHtml } from '@/utils/markdown'
import type { Thread } from '@/types'

const props = defineProps<{
  thread: Thread
}>()

const markdownRenderer = createMarkdownRenderer({ katex: true, normalizeImages: true })
const contentRef = ref<HTMLElement | null>(null)

const contentMarkdownHtml = computed(() => {
  const raw = props.thread.content
  if (typeof raw === 'string' && raw.length > 0) {
    return renderMarkdown(markdownRenderer, raw)
  }
  const serverHtml = props.thread.contentHtml
  if (typeof serverHtml === 'string' && serverHtml.length > 0) {
    return sanitizeHtml(serverHtml)
  }
  return ''
})

function enhanceRenderedContent(): void {
  const element = contentRef.value
  applyRuntimeHighlight(element)
  if (!element) return

  const nodes = element.querySelectorAll<HTMLElement>('pre code')
  nodes.forEach((node) => {
    const pre = node.closest('pre')
    if (pre && !pre.querySelector('.code-copy-btn')) {
      const button = document.createElement('button')
      button.type = 'button'
      button.className = 'code-copy-btn'
      button.setAttribute('aria-label', '复制代码')
      button.textContent = '复制'
      button.addEventListener('click', async (event) => {
        event.preventDefault()
        try {
          const text = node.innerText || node.textContent || ''
          await navigator.clipboard.writeText(text)
          button.textContent = '已复制'
          setTimeout(() => {
            button.textContent = '复制'
          }, 1500)
        } catch (_) {
          button.textContent = '复制失败'
          setTimeout(() => {
            button.textContent = '复制'
          }, 1500)
        }
      })
      pre.style.position = 'relative'
      pre.appendChild(button)
    }
  })
}

onMounted(async () => {
  await nextTick()
  enhanceRenderedContent()
})

watch(contentMarkdownHtml, async () => {
  await nextTick()
  enhanceRenderedContent()
})
</script>

<template>
  <div class="p-5 sm:p-8">
    <div
      ref="contentRef"
      class="prose prose-lg max-w-none prose-a:text-brandDay-600 prose-headings:font-bold prose-img:rounded-xl prose-img:shadow-sm dark:prose-a:text-brandNight-400 dark:prose-invert"
      v-html="contentMarkdownHtml"
    />
  </div>
</template>

<style scoped>
.prose :deep(pre) { position: relative; }
.prose :deep(.code-copy-btn) {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  border-radius: 0.25rem;
  background: rgba(255, 255, 255, 0.8);
  color: #111827;
  border: 1px solid #e5e7eb;
}
.dark :deep(.code-copy-btn) {
  background: rgba(31, 41, 55, 0.7);
  color: #e5e7eb;
  border-color: #374151;
}
</style>

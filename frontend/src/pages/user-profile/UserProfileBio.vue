<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'

const props = defineProps<{ bio: string }>()
type BioRenderer = (raw: string) => string

const renderBio = shallowRef<BioRenderer | null>(null)

onMounted(async () => {
  try {
    const { createMarkdownRenderer, renderMarkdown } = await import('@/utils/markdown')
    const markdownRenderer = createMarkdownRenderer({ katex: true, normalizeImages: true })
    renderBio.value = raw => renderMarkdown(markdownRenderer, raw)
  } catch (_) {}
})

const renderedHtml = computed<string | null>(() => {
  if (!renderBio.value) return null
  try {
    return renderBio.value(props.bio)
  } catch (_) {
    return null
  }
})
</script>

<template>
  <div
    v-if="renderedHtml !== null"
    class="prose mt-2 max-w-none text-[var(--ui-ink)] dark:prose-invert"
    v-html="renderedHtml"
  ></div>
  <div
    v-else
    class="prose mt-2 max-w-none whitespace-pre-wrap text-[var(--ui-ink)] dark:prose-invert"
  >{{ bio }}</div>
</template>

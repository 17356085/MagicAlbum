<script lang="ts">
import { computed, defineComponent, type PropType } from 'vue'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'

const markdownRenderer = createMarkdownRenderer({ katex: true, normalizeImages: true })

export default defineComponent({
  name: 'CommentBody',
  props: {
    content: {
      type: String as PropType<string | null>,
      default: '',
    },
  },
  setup(props) {
    const renderedHtml = computed(() => renderMarkdown(markdownRenderer, props.content))
    return { renderedHtml }
  },
})
</script>

<template>
  <div class="prose prose-base mt-3 max-w-3xl leading-6 dark:prose-invert" v-html="renderedHtml" />
</template>

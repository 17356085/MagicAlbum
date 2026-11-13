<script setup lang="ts">
import { computed } from 'vue'
import { parseThreadTags, THREAD_TAG_LIMIT } from '@/utils/threadTags'

defineProps<{ id: string; disabled?: boolean }>()
const model = defineModel<string>({ required: true })

const parsedTags = computed(() => parseThreadTags(model.value))
const placeholder = `用空格或逗号分隔，最多 ${THREAD_TAG_LIMIT} 个`
</script>

<template>
  <details class="rounded-xl border border-[var(--ui-border)] px-3">
    <summary class="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium">
      <span>标签（可选）</span>
      <span v-if="parsedTags.length" class="ui-muted text-xs">已设置 {{ parsedTags.length }} 个</span>
    </summary>
    <div class="pb-3">
      <label :for="id" class="sr-only">标签</label>
      <input
        :id="id"
        v-model="model" :disabled="disabled"
        type="text"
        class="ui-field min-h-11 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
        :placeholder="placeholder"
      />
      <div v-if="parsedTags.length" class="mt-2 flex flex-wrap gap-2">
        <span v-for="tag in parsedTags" :key="tag" class="ui-tag">
          #{{ tag }}
        </span>
      </div>
    </div>
  </details>
</template>

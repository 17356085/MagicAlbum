<script setup lang="ts">
import { computed } from 'vue'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'

const props = withDefaults(defineProps<{
  isLoggedIn: boolean
  modelValue: string
  previewMode: boolean
  replyToPostId?: string | number | null
  widthClass?: string
}>(), {
  replyToPostId: null,
  widthClass: 'w-full',
})

const emit = defineEmits<{
  'cancel-reply': []
  'submit': []
  'update:modelValue': [value: string]
  'update:previewMode': [value: boolean]
  'upload-image': [event: Event]
}>()

const markdownRenderer = createMarkdownRenderer({ katex: true, normalizeImages: true })
const previewHtml = computed(() => renderMarkdown(markdownRenderer, props.modelValue))
const inputClass = computed(() =>
  `${props.widthClass} h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400`,
)
</script>

<template>
  <div>
    <div v-if="!isLoggedIn" class="text-xs text-gray-500">登录后可发表评论</div>
    <div v-else>
      <textarea
        v-if="!previewMode"
        :value="modelValue"
        :class="inputClass"
        placeholder="支持基础 Markdown（图片请使用上方上传功能）"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      />
      <div
        v-else
        :class="`${widthClass} h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm prose max-w-none dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:prose-invert`"
        v-html="previewHtml"
      />
      <div class="mt-2 flex items-center gap-2">
        <label class="inline-flex cursor-pointer items-center gap-2 text-xs">
          <input type="file" accept="image/*" class="hidden" @change="emit('upload-image', $event)" />
          <span class="rounded border px-2 py-1 dark:border-gray-700">添加图片</span>
        </label>
        <button class="rounded border px-2 py-1 dark:border-gray-700" @click="emit('update:previewMode', !previewMode)">
          {{ previewMode ? '退出预览' : '预览' }}
        </button>
        <button
          class="rounded bg-brandDay-600 px-3 py-1 text-xs text-white shadow-sm hover:bg-brandDay-700 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 motion-safe:transition-shadow motion-safe:duration-200 dark:bg-brandNight-600 dark:hover:bg-brandNight-700 dark:focus:ring-accentCyan-400"
          @click="emit('submit')"
        >
          发送评论
        </button>
        <span v-if="replyToPostId != null" class="text-xs text-gray-500">
          回复：#{{ replyToPostId }}
          <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('cancel-reply')">取消</button>
        </span>
      </div>
    </div>
  </div>
</template>

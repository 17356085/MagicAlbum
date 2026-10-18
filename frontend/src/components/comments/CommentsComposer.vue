<script setup lang="ts">
import { computed, inject, ref, type InjectionKey, type Ref } from 'vue'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'

const props = withDefaults(defineProps<{
  isLoggedIn: boolean
  isSubmitting?: boolean
  isUploading?: boolean
  modelValue: string
  previewMode: boolean
  replyToPostId?: string | number | null
  widthClass?: string
}>(), {
  replyToPostId: null,
  widthClass: 'w-full',
  isUploading: false,
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
const commentSubmissionKey = Symbol.for('bluealbum.comments.submitting') as InjectionKey<Ref<boolean>>
const inheritedSubmitting = inject(commentSubmissionKey, ref(false))
const isSubmitting = computed(() => props.isSubmitting || inheritedSubmitting.value)
const isBusy = computed(() => isSubmitting.value || props.isUploading)
const inputClass = computed(() =>
  `${props.widthClass} ui-field h-[140px] resize-none text-sm leading-6`,
)
</script>

<template>
  <div>
    <div v-if="!isLoggedIn" class="ui-muted text-sm">登录后可发表评论</div>
    <div v-else>
      <textarea
        v-if="!previewMode"
        :value="modelValue"
        :class="inputClass"
        aria-label="评论内容"
        placeholder="支持基础 Markdown（图片请使用上方上传功能）"
        :disabled="isBusy"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)"
      />
      <div
        v-else
        :class="`${widthClass} ui-field ui-scroll h-[140px] overflow-auto px-3 py-2 text-sm prose max-w-none dark:prose-invert`"
        role="region"
        aria-label="评论预览"
        v-html="previewHtml"
      />
      <div class="mt-3 flex flex-wrap items-center gap-2">
        <label class="inline-flex cursor-pointer items-center gap-2 rounded-lg text-xs focus-within:ring-2 focus-within:ring-[var(--ui-accent)] focus-within:ring-offset-2">
          <input type="file" accept="image/*" class="sr-only" aria-label="添加评论图片" :disabled="isBusy" @change="emit('upload-image', $event)" />
          <span class="ui-button min-h-9 px-3 py-1 text-xs">添加图片</span>
        </label>
        <button type="button" class="ui-button min-h-9 px-3 py-1 text-xs" :disabled="isBusy" :aria-pressed="previewMode" @click="emit('update:previewMode', !previewMode)">
          {{ isUploading ? '上传中...' : (previewMode ? '退出预览' : '预览') }}
        </button>
        <button
          type="button"
          :disabled="isBusy"
          class="ui-button-primary min-h-9 px-3 py-1 text-xs disabled:opacity-60"
          @click="emit('submit')"
        >
          {{ isSubmitting ? '发送中...' : (isUploading ? '上传中...' : '发送评论') }}
        </button>
        <span v-if="replyToPostId != null" class="ui-muted text-xs">
          回复：#{{ replyToPostId }}
          <button type="button" class="ui-button min-h-8 px-2 py-1 text-xs" :disabled="isBusy" @click="emit('cancel-reply')">取消</button>
        </span>
      </div>
    </div>
  </div>
</template>

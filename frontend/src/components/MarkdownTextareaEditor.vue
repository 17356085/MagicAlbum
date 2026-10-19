<script setup lang="ts">
import { ref, useId } from 'vue'
import { useMarkdownTextareaState } from '@/components/markdown/useMarkdownTextareaState'

const props = withDefaults(defineProps<{
  modelValue: string
  rows?: number
  maxLength?: number
  placeholder?: string
  previewLabel?: string
  uploading?: boolean
  uploadProgress?: number
  uploadImage?: ((file: File) => Promise<string>) | null
  previewDefaultCollapsed?: boolean
  mobilePreviewExclusive?: boolean
  disabled?: boolean
}>(), {
  rows: 12,
  maxLength: undefined,
  placeholder: '',
  previewLabel: '预览',
  uploading: false,
  uploadProgress: 0,
  uploadImage: null,
  previewDefaultCollapsed: false,
  mobilePreviewExclusive: false,
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()
const showMoreFormats = ref(false)
const moreFormatsId = useId()
const {
  closeImagePanel,
  closeLinkPanel,
  contentLength,
  fileInputRef,
  imageAlt,
  imageAltInputRef,
  imageError,
  imagePanelRef,
  imageUploadInFlight,
  imageUrl,
  insertMarkdown,
  linkError,
  linkLabel,
  linkPanelRef,
  linkTextInputRef,
  linkUrl,
  onSelectImage,
  onTextareaInput,
  onTextareaKeydown,
  previewHtml,
  rootRef,
  showImagePanel,
  showLinkPanel,
  showPreview,
  submitImagePanel,
  submitLinkPanel,
  textareaRef,
} = useMarkdownTextareaState(props, emit)
</script>

<template>
  <div ref="rootRef" class="space-y-4">
    <div class="space-y-2">
      <div
        :class="mobilePreviewExclusive && showPreview ? 'hidden lg:flex' : 'grid grid-cols-4 gap-2 lg:flex lg:flex-nowrap'"
        class="min-w-0"
      >
        <button type="button" class="ui-button min-h-12 min-w-[48px] w-full px-3 py-2 text-sm lg:w-auto" :disabled="disabled" @click="insertMarkdown('bold')">粗体</button>
        <button type="button" class="ui-button min-h-12 min-w-[48px] w-full px-3 py-2 text-sm lg:w-auto" :disabled="disabled" @click="insertMarkdown('link')">链接</button>
        <button type="button" class="ui-button min-h-12 min-w-[48px] w-full px-3 py-2 text-sm lg:w-auto" :disabled="disabled" @click="insertMarkdown('image')">图片</button>
        <button
          type="button"
          class="ui-button min-h-12 min-w-[48px] w-full px-3 py-2 text-sm lg:hidden"
          :disabled="disabled"
          :aria-expanded="showMoreFormats"
          :aria-controls="moreFormatsId"
          @click="showMoreFormats = !showMoreFormats"
        >
          {{ showMoreFormats ? '收起' : '更多' }}
        </button>
      </div>
      <div
        :id="moreFormatsId"
        class="markdown-editor-more-tools flex-wrap items-center gap-2"
        :class="mobilePreviewExclusive && showPreview ? 'hidden lg:flex' : (showMoreFormats ? 'flex' : 'hidden lg:flex')"
      >
        <button type="button" class="ui-button min-h-11 min-w-[48px] px-3 py-2 text-sm" :disabled="disabled" @click="insertMarkdown('italic')">斜体</button>
        <button type="button" class="ui-button min-h-11 min-w-[48px] px-3 py-2 text-sm" :disabled="disabled" @click="insertMarkdown('heading')">标题</button>
        <button type="button" class="ui-button min-h-11 min-w-[48px] px-3 py-2 text-sm" :disabled="disabled" @click="insertMarkdown('quote')">引用</button>
        <button type="button" class="ui-button min-h-11 min-w-[48px] px-3 py-2 text-sm" :disabled="disabled" @click="insertMarkdown('code')">代码块</button>
        <button type="button" class="ui-button min-h-11 min-w-[48px] px-3 py-2 text-sm" :disabled="disabled" @click="insertMarkdown('list')">列表</button>
        <button type="button" class="ui-button min-h-11 min-w-[48px] px-3 py-2 text-sm" :disabled="disabled" @click="insertMarkdown('ordered-list')">有序列表</button>
      </div>
      <div class="flex min-h-11 items-center gap-2">
        <button type="button" class="ui-button min-h-11 flex-1 px-3 py-2 text-sm lg:flex-none" :disabled="disabled" :aria-pressed="showPreview" @click="showPreview = !showPreview">
          <template v-if="showPreview">
            <span v-if="mobilePreviewExclusive" class="lg:hidden">返回编辑</span>
            <span :class="mobilePreviewExclusive ? 'hidden lg:inline' : ''">隐藏预览</span>
          </template>
          <template v-else>{{ previewLabel }}</template>
        </button>
        <span class="ui-muted ml-auto shrink-0 whitespace-nowrap text-xs">字数：{{ contentLength }}<template v-if="typeof maxLength === 'number'">/{{ maxLength }}</template></span>
      </div>
    </div>

    <div v-if="showLinkPanel" ref="linkPanelRef" class="ui-panel p-4">
      <div class="mb-2 text-sm font-medium dark:text-gray-100">插入链接</div>
      <div class="grid gap-3 lg:grid-cols-2">
        <label class="text-sm font-medium">
          链接文本
          <input
            ref="linkTextInputRef"
            v-model="linkLabel"
            type="text"
            class="ui-field mt-1 min-h-11 px-3 py-2 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
            placeholder="请输入链接文本"
            :disabled="disabled"
            @keyup.enter="submitLinkPanel"
            @keyup.esc="closeLinkPanel"
          />
        </label>
        <label class="text-sm font-medium">
          链接地址
          <input
            v-model="linkUrl"
            type="url"
            class="ui-field mt-1 min-h-11 px-3 py-2 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
            placeholder="https://"
            :disabled="disabled"
            @keyup.enter="submitLinkPanel"
            @keyup.esc="closeLinkPanel"
          />
        </label>
      </div>
      <div class="mt-2 flex items-center justify-between gap-2">
        <div v-if="linkError" class="text-xs text-red-600 dark:text-red-400" role="alert">{{ linkError }}</div>
        <div v-else class="ui-muted text-xs">按 Enter 确认，Esc 取消</div>
        <div class="flex items-center gap-2">
          <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm" :disabled="disabled" @click="closeLinkPanel">取消</button>
          <button type="button" class="ui-button-primary min-h-12 px-3 py-2 text-sm" :disabled="disabled" @click="submitLinkPanel">插入链接</button>
        </div>
      </div>
    </div>

    <div v-if="showImagePanel" ref="imagePanelRef" class="ui-panel p-4">
      <div class="mb-2 text-sm font-medium dark:text-gray-100">插入图片</div>
      <div class="grid gap-3 lg:grid-cols-2">
        <label class="text-sm font-medium">
          图片说明
          <input
            ref="imageAltInputRef"
            v-model="imageAlt"
            type="text"
            class="ui-field mt-1 min-h-11 px-3 py-2 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
            placeholder="请输入图片说明"
            :disabled="disabled"
            @keyup.enter="submitImagePanel"
            @keyup.esc="closeImagePanel"
          />
        </label>
        <label class="text-sm font-medium">
          图片地址
          <input
            v-model="imageUrl"
            type="url"
            class="ui-field mt-1 min-h-11 px-3 py-2 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
            placeholder="https://"
            :disabled="disabled"
            @keyup.enter="submitImagePanel"
            @keyup.esc="closeImagePanel"
          />
        </label>
      </div>
      <div class="mt-3 flex flex-wrap items-center justify-between gap-2">
        <div v-if="imageError" class="text-xs text-red-600 dark:text-red-400" role="alert">{{ imageError }}</div>
        <div v-else class="ui-muted text-xs">可直接填图片 URL，也可选择本地图片上传后插入</div>
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="ui-button min-h-11 px-3 py-2 text-sm"
            :disabled="disabled || !uploadImage || uploading || imageUploadInFlight"
            @click="fileInputRef?.click()"
          >
            {{ uploading ? '上传中...' : '上传图片' }}
          </button>
          <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm" :disabled="disabled" @click="closeImagePanel">取消</button>
          <button type="button" class="ui-button-primary min-h-12 px-3 py-2 text-sm" :disabled="disabled" @click="submitImagePanel">插入图片</button>
        </div>
      </div>
    </div>

    <input ref="fileInputRef" type="file" accept="image/*" class="hidden" :disabled="disabled || uploading || imageUploadInFlight" @change="onSelectImage" />

    <div class="grid gap-3 lg:grid-cols-2">
      <div :class="['relative', mobilePreviewExclusive && showPreview ? 'hidden lg:block' : '']">
        <textarea
          ref="textareaRef"
          :value="modelValue"
          :rows="mobilePreviewExclusive ? Math.min(rows, 7) : rows"
          :maxlength="maxLength"
          :placeholder="placeholder"
          :disabled="disabled"
          :class="mobilePreviewExclusive ? 'h-[40dvh] min-h-[220px] max-h-[480px] lg:h-auto lg:max-h-none lg:min-h-[260px]' : 'min-h-[260px]'"
          class="ui-field w-full resize-none overflow-y-auto px-3 py-3 text-base leading-6 focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
          @input="onTextareaInput"
          @keydown="onTextareaKeydown"
        />
        <div v-if="uploading" class="ui-panel absolute right-3 top-2 px-2 py-1 text-xs">
          上传中 {{ uploadProgress }}%
        </div>
      </div>

      <div v-if="showPreview" :class="['h-[40dvh] min-h-[220px] max-h-[480px] overflow-y-auto border border-gray-200 bg-[var(--ui-subtle)] p-4 dark:border-gray-700 lg:h-auto lg:max-h-none lg:min-h-[260px]', mobilePreviewExclusive ? 'lg:block' : '']">
        <div class="ui-muted mb-2 text-xs font-medium">{{ previewLabel }}</div>
        <div class="prose max-w-none break-words dark:prose-invert" v-html="previewHtml" />
      </div>
    </div>
  </div>
</template>

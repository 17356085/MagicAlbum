<script setup lang="ts">
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
    <div class="flex flex-wrap items-center gap-2">
      <div :class="mobilePreviewExclusive && showPreview ? 'hidden lg:flex' : 'flex'" class="flex-wrap items-center gap-2">
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('bold')">粗体</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('italic')">斜体</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('heading')">标题</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('quote')">引用</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('code')">代码块</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('list')">列表</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('ordered-list')">有序列表</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('link')">链接</button>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" @click="insertMarkdown('image')">图片</button>
      </div>
      <button type="button" class="ui-button min-h-9 px-2.5 py-1 text-xs" :disabled="disabled" :aria-pressed="showPreview" @click="showPreview = !showPreview">
        <template v-if="showPreview">
          <span v-if="mobilePreviewExclusive" class="lg:hidden">返回编辑</span>
          <span :class="mobilePreviewExclusive ? 'hidden lg:inline' : ''">隐藏预览</span>
        </template>
        <template v-else>{{ previewLabel }}</template>
      </button>
      <span class="ui-muted ml-auto text-xs">字数：{{ contentLength }}<template v-if="typeof maxLength === 'number'">/{{ maxLength }}</template></span>
    </div>

    <div v-if="showLinkPanel" ref="linkPanelRef" class="ui-panel p-4">
      <div class="mb-2 text-sm font-medium dark:text-gray-100">插入链接</div>
      <div class="grid gap-3 md:grid-cols-2">
        <label class="text-sm font-medium">
          链接文本
          <input
            ref="linkTextInputRef"
            v-model="linkLabel"
            type="text"
            class="ui-field mt-1 min-h-10 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
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
            class="ui-field mt-1 min-h-10 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
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
          <button type="button" class="ui-button px-2.5 py-1 text-xs" :disabled="disabled" @click="closeLinkPanel">取消</button>
          <button type="button" class="ui-button-primary px-2.5 py-1 text-xs" :disabled="disabled" @click="submitLinkPanel">插入链接</button>
        </div>
      </div>
    </div>

    <div v-if="showImagePanel" ref="imagePanelRef" class="ui-panel p-4">
      <div class="mb-2 text-sm font-medium dark:text-gray-100">插入图片</div>
      <div class="grid gap-3 md:grid-cols-2">
        <label class="text-sm font-medium">
          图片说明
          <input
            ref="imageAltInputRef"
            v-model="imageAlt"
            type="text"
            class="ui-field mt-1 min-h-10 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
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
            class="ui-field mt-1 min-h-10 px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
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
            class="ui-button px-2.5 py-1 text-xs"
            :disabled="disabled || !uploadImage || uploading || imageUploadInFlight"
            @click="fileInputRef?.click()"
          >
            {{ uploading ? '上传中...' : '上传图片' }}
          </button>
          <button type="button" class="ui-button px-2.5 py-1 text-xs" :disabled="disabled" @click="closeImagePanel">取消</button>
          <button type="button" class="ui-button-primary px-2.5 py-1 text-xs" :disabled="disabled" @click="submitImagePanel">插入图片</button>
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
          :class="mobilePreviewExclusive ? 'min-h-[180px] lg:min-h-[260px]' : 'min-h-[260px]'"
          class="ui-field w-full resize-none min-h-[260px] px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
          @input="onTextareaInput"
          @keydown="onTextareaKeydown"
        />
        <div v-if="uploading" class="ui-panel absolute right-3 top-2 px-2 py-1 text-xs">
          上传中 {{ uploadProgress }}%
        </div>
      </div>

      <div v-if="showPreview" :class="['min-h-[260px] border border-gray-200 bg-[var(--ui-subtle)] p-4 dark:border-gray-700', mobilePreviewExclusive ? 'lg:block' : '']">
        <div class="ui-muted mb-2 text-xs font-medium">{{ previewLabel }}</div>
        <div class="prose max-w-none break-words dark:prose-invert" v-html="previewHtml" />
      </div>
    </div>
  </div>
</template>

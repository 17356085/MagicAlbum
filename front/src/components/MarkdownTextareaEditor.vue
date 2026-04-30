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
}>(), {
  rows: 12,
  maxLength: undefined,
  placeholder: '',
  previewLabel: '预览',
  uploading: false,
  uploadProgress: 0,
  uploadImage: null,
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
  <div ref="rootRef" class="space-y-3">
    <div class="flex flex-wrap items-center gap-2">
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('bold')">粗体</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('italic')">斜体</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('heading')">标题</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('quote')">引用</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('code')">代码块</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('list')">列表</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('ordered-list')">有序列表</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('link')">链接</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="insertMarkdown('image')">图片</button>
      <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="showPreview = !showPreview">
        {{ showPreview ? '隐藏预览' : previewLabel }}
      </button>
      <span class="ml-auto text-xs text-gray-500 dark:text-gray-400">字数：{{ contentLength }}<template v-if="typeof maxLength === 'number'">/{{ maxLength }}</template></span>
    </div>

    <div v-if="showLinkPanel" ref="linkPanelRef" class="rounded-md border border-gray-200 bg-white p-3 shadow-sm dark:border-gray-700 dark:bg-gray-800">
      <div class="mb-2 text-sm font-medium dark:text-gray-100">插入链接</div>
      <div class="grid gap-3 md:grid-cols-2">
        <label class="text-xs text-gray-600 dark:text-gray-300">
          链接文本
          <input
            ref="linkTextInputRef"
            v-model="linkLabel"
            type="text"
            class="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
            placeholder="请输入链接文本"
            @keyup.enter="submitLinkPanel"
            @keyup.esc="closeLinkPanel"
          />
        </label>
        <label class="text-xs text-gray-600 dark:text-gray-300">
          链接地址
          <input
            v-model="linkUrl"
            type="url"
            class="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
            placeholder="https://"
            @keyup.enter="submitLinkPanel"
            @keyup.esc="closeLinkPanel"
          />
        </label>
      </div>
      <div class="mt-2 flex items-center justify-between gap-2">
        <div v-if="linkError" class="text-xs text-red-600 dark:text-red-400">{{ linkError }}</div>
        <div v-else class="text-xs text-gray-500 dark:text-gray-400">按 Enter 确认，Esc 取消</div>
        <div class="flex items-center gap-2">
          <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="closeLinkPanel">取消</button>
          <button type="button" class="rounded bg-brandDay-600 px-2.5 py-1 text-xs text-white hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-700" @click="submitLinkPanel">插入链接</button>
        </div>
      </div>
    </div>

    <div v-if="showImagePanel" ref="imagePanelRef" class="rounded-md border border-gray-200 bg-white p-3 shadow-sm dark:border-gray-700 dark:bg-gray-800">
      <div class="mb-2 text-sm font-medium dark:text-gray-100">插入图片</div>
      <div class="grid gap-3 md:grid-cols-2">
        <label class="text-xs text-gray-600 dark:text-gray-300">
          图片说明
          <input
            ref="imageAltInputRef"
            v-model="imageAlt"
            type="text"
            class="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
            placeholder="请输入图片说明"
            @keyup.enter="submitImagePanel"
            @keyup.esc="closeImagePanel"
          />
        </label>
        <label class="text-xs text-gray-600 dark:text-gray-300">
          图片地址
          <input
            v-model="imageUrl"
            type="url"
            class="mt-1 w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
            placeholder="https://"
            @keyup.enter="submitImagePanel"
            @keyup.esc="closeImagePanel"
          />
        </label>
      </div>
      <div class="mt-3 flex flex-wrap items-center justify-between gap-2">
        <div v-if="imageError" class="text-xs text-red-600 dark:text-red-400">{{ imageError }}</div>
        <div v-else class="text-xs text-gray-500 dark:text-gray-400">可直接填图片 URL，也可选择本地图片上传后插入</div>
        <div class="flex items-center gap-2">
          <button
            type="button"
            class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200"
            :disabled="!uploadImage || uploading"
            @click="fileInputRef?.click()"
          >
            {{ uploading ? '上传中...' : '上传图片' }}
          </button>
          <button type="button" class="rounded border px-2.5 py-1 text-xs dark:border-gray-700 dark:text-gray-200" @click="closeImagePanel">取消</button>
          <button type="button" class="rounded bg-brandDay-600 px-2.5 py-1 text-xs text-white hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-700" @click="submitImagePanel">插入图片</button>
        </div>
      </div>
    </div>

    <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="onSelectImage" />

    <div class="grid gap-3 lg:grid-cols-2">
      <div class="relative">
        <textarea
          ref="textareaRef"
          :value="modelValue"
          :rows="rows"
          :maxlength="maxLength"
          :placeholder="placeholder"
          class="min-h-[260px] w-full resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          @input="onTextareaInput"
          @keydown="onTextareaKeydown"
        />
        <div v-if="uploading" class="absolute top-2 right-3 rounded border border-gray-200 bg-white/85 px-2 py-1 text-xs dark:border-gray-700 dark:bg-gray-800/85 dark:text-gray-200">
          上传中 {{ uploadProgress }}%
        </div>
      </div>

      <div v-if="showPreview" class="min-h-[260px] rounded-md border border-gray-200 bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-900/40">
        <div class="mb-2 text-xs font-medium text-gray-500 dark:text-gray-400">{{ previewLabel }}</div>
        <div class="prose max-w-none break-words dark:prose-invert" v-html="previewHtml" />
      </div>
    </div>
  </div>
</template>

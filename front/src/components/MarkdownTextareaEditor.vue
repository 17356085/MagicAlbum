<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'
import { createMarkdownImage, createMarkdownLink, insertText, wrapSelection } from '@/utils/markdownEditor'

type InsertAction = 'bold' | 'italic' | 'quote' | 'code' | 'heading' | 'list' | 'ordered-list' | 'link' | 'image'

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

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const linkTextInputRef = ref<HTMLInputElement | null>(null)
const imageAltInputRef = ref<HTMLInputElement | null>(null)
const rootRef = ref<HTMLElement | null>(null)
const linkPanelRef = ref<HTMLElement | null>(null)
const imagePanelRef = ref<HTMLElement | null>(null)
const showPreview = ref(true)
const showLinkPanel = ref(false)
const showImagePanel = ref(false)
const linkLabel = ref('')
const linkUrl = ref('https://')
const linkError = ref('')
const imageAlt = ref('image')
const imageUrl = ref('https://')
const imageError = ref('')
const pendingSelection = ref({ start: 0, end: 0 })
const md = createMarkdownRenderer({ katex: true, normalizeImages: true })

const previewHtml = computed(() => renderMarkdown(md, props.modelValue))
const contentLength = computed(() => String(props.modelValue || '').length)

function updateValue(value: string): void {
  emit('update:modelValue', value)
}

function focusTextarea(start?: number, end?: number): void {
  nextTick(() => {
    const el = textareaRef.value
    if (!el) return
    el.focus()
    if (typeof start === 'number' && typeof end === 'number') {
      el.setSelectionRange(start, end)
    }
  })
}

function getSelection() {
  const current = String(props.modelValue || '')
  return {
    start: textareaRef.value?.selectionStart ?? current.length,
    end: textareaRef.value?.selectionEnd ?? current.length,
  }
}

function applyResult(value: string, start: number, end: number): void {
  updateValue(value)
  focusTextarea(start, end)
}

function replaceSelectedBlock(
  transform: (selected: string) => { value: string; selectionStart?: number; selectionEnd?: number },
  fallback: string,
): void {
  const selection = getSelection()
  const currentValue = String(props.modelValue || '')
  const selected = currentValue.slice(selection.start, selection.end) || fallback
  const transformed = transform(selected)
  const nextValue = `${currentValue.slice(0, selection.start)}${transformed.value}${currentValue.slice(selection.end)}`
  const selectionStart = selection.start + (transformed.selectionStart ?? 0)
  const selectionEnd = selection.start + (transformed.selectionEnd ?? transformed.value.length)
  applyResult(nextValue, selectionStart, selectionEnd)
}

function addPrefixToLines(selected: string, prefix: string): { value: string } {
  return {
    value: selected
      .split('\n')
      .map((line) => `${prefix}${line || ''}`)
      .join('\n'),
  }
}

function addOrderedPrefixToLines(selected: string): { value: string } {
  const lines = selected.split('\n')
  return {
    value: lines
      .map((line, index) => `${index + 1}. ${line || ''}`)
      .join('\n'),
  }
}

function isValidUrl(raw: string): boolean {
  const value = String(raw || '').trim()
  if (!value) return false
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol)
  } catch (_) {
    return false
  }
}

function getCurrentLine(value: string, position: number): { start: number; end: number; text: string } {
  const start = value.lastIndexOf('\n', Math.max(0, position - 1)) + 1
  const nextBreak = value.indexOf('\n', position)
  const end = nextBreak >= 0 ? nextBreak : value.length
  return {
    start,
    end,
    text: value.slice(start, end),
  }
}

function normalizeOrderedListAround(value: string, anchorStart: number): string {
  const lines = value.split('\n')
  let charIndex = 0
  let lineIndex = 0

  for (let i = 0; i < lines.length; i += 1) {
    const lineLength = lines[i].length
    if (anchorStart <= charIndex + lineLength) {
      lineIndex = i
      break
    }
    charIndex += lineLength + 1
  }

  const orderedPattern = /^(\s*)(\d+)\.\s(.*)$/
  const currentMatch = lines[lineIndex]?.match(orderedPattern)
  if (!currentMatch) return value

  const indent = currentMatch[1] || ''
  let start = lineIndex
  let end = lineIndex

  while (start > 0) {
    const prevMatch = lines[start - 1].match(orderedPattern)
    if (!prevMatch || prevMatch[1] !== indent) break
    start -= 1
  }

  while (end + 1 < lines.length) {
    const nextMatch = lines[end + 1].match(orderedPattern)
    if (!nextMatch || nextMatch[1] !== indent) break
    end += 1
  }

  let order = 1
  for (let i = start; i <= end; i += 1) {
    const match = lines[i].match(orderedPattern)
    if (!match || match[1] !== indent) continue
    lines[i] = `${indent}${order}. ${match[3]}`
    order += 1
  }

  return lines.join('\n')
}

function getLineIndexAtPosition(value: string, position: number): number {
  let lineIndex = 0
  for (let i = 0; i < Math.min(position, value.length); i += 1) {
    if (value[i] === '\n') lineIndex += 1
  }
  return lineIndex
}

function normalizeAllOrderedLists(value: string, cursor: number): { value: string; cursor: number } {
  const lines = value.split('\n')
  const orderedPattern = /^(\s*)(\d+)\.\s(.*)$/
  const cursorLineIndex = getLineIndexAtPosition(value, cursor)

  let nextCursor = cursor
  let charIndex = 0
  let i = 0

  while (i < lines.length) {
    const match = lines[i].match(orderedPattern)
    if (!match) {
      charIndex += lines[i].length + 1
      i += 1
      continue
    }

    const indent = match[1] || ''
    const start = i
    let end = i

    while (end + 1 < lines.length) {
      const nextMatch = lines[end + 1].match(orderedPattern)
      if (!nextMatch || nextMatch[1] !== indent) break
      end += 1
    }

    let localCharIndex = charIndex
    for (let index = start; index <= end; index += 1) {
      const currentMatch = lines[index].match(orderedPattern)
      if (!currentMatch) continue

      const oldLine = lines[index]
      const newLine = `${indent}${index - start + 1}. ${currentMatch[3]}`
      const delta = newLine.length - oldLine.length

      lines[index] = newLine

      if (index < cursorLineIndex) {
        nextCursor += delta
      } else if (index === cursorLineIndex) {
        const cursorColumn = cursor - localCharIndex
        if (cursorColumn > 0) nextCursor += delta
      }

      localCharIndex += oldLine.length + 1
    }

    for (let index = start; index <= end; index += 1) {
      charIndex += lines[index].length + 1
    }
    i = end + 1
  }

  return {
    value: lines.join('\n'),
    cursor: Math.max(0, nextCursor),
  }
}

function openLinkPanel(): void {
  const selection = getSelection()
  const currentValue = String(props.modelValue || '')
  const selectedText = currentValue.slice(selection.start, selection.end).trim()

  pendingSelection.value = selection
  linkLabel.value = selectedText || '链接文本'
  linkUrl.value = 'https://'
  linkError.value = ''
  showLinkPanel.value = true

  nextTick(() => {
    linkTextInputRef.value?.focus()
    linkTextInputRef.value?.select()
  })
}

function closeLinkPanel(): void {
  showLinkPanel.value = false
  linkError.value = ''
  focusTextarea(pendingSelection.value.start, pendingSelection.value.end)
}

function submitLinkPanel(): void {
  const url = String(linkUrl.value || '').trim()
  const label = String(linkLabel.value || '').trim() || '链接文本'

  if (!url) {
    linkError.value = '请输入链接地址'
    return
  }
  if (!isValidUrl(url)) {
    linkError.value = '链接地址格式无效，请输入 http:// 或 https:// 开头的地址'
    return
  }

  const result = insertText(
    String(props.modelValue || ''),
    createMarkdownLink(url, label),
    pendingSelection.value,
  )
  showLinkPanel.value = false
  linkError.value = ''
  applyResult(result.value, result.selection.start, result.selection.end)
}

function openImagePanel(): void {
  pendingSelection.value = getSelection()
  imageAlt.value = 'image'
  imageUrl.value = 'https://'
  imageError.value = ''
  showImagePanel.value = true

  nextTick(() => {
    imageAltInputRef.value?.focus()
    imageAltInputRef.value?.select()
  })
}

function closeImagePanel(): void {
  showImagePanel.value = false
  imageError.value = ''
  focusTextarea(pendingSelection.value.start, pendingSelection.value.end)
}

function submitImagePanel(): void {
  const url = String(imageUrl.value || '').trim()
  const alt = String(imageAlt.value || '').trim() || 'image'

  if (!url) {
    imageError.value = '请输入图片地址'
    return
  }
  if (!isValidUrl(url)) {
    imageError.value = '图片地址格式无效，请输入 http:// 或 https:// 开头的地址'
    return
  }

  const result = insertText(
    String(props.modelValue || ''),
    createMarkdownImage(url, alt),
    pendingSelection.value,
  )
  showImagePanel.value = false
  imageError.value = ''
  applyResult(result.value, result.selection.start, result.selection.end)
}

function insertMarkdown(action: InsertAction): void {
  const selection = getSelection()
  const currentValue = String(props.modelValue || '')

  if (action === 'bold') {
    const result = wrapSelection(currentValue, '**', '**', selection, '粗体文本')
    applyResult(result.value, result.selection.start, result.selection.end)
    return
  }
  if (action === 'italic') {
    const result = wrapSelection(currentValue, '*', '*', selection, '斜体文本')
    applyResult(result.value, result.selection.start, result.selection.end)
    return
  }
  if (action === 'quote') {
    replaceSelectedBlock((selected) => addPrefixToLines(selected, '> '), '引用内容')
    return
  }
  if (action === 'code') {
    replaceSelectedBlock((selected) => {
      const content = selected.trim() || '代码块'
      const value = `\`\`\`text\n${content}\n\`\`\``
      return {
        value,
        selectionStart: 8,
        selectionEnd: 8 + content.length,
      }
    }, '代码块')
    return
  }
  if (action === 'heading') {
    replaceSelectedBlock((selected) => {
      const trimmed = selected.trim() || '标题'
      return {
        value: `## ${trimmed}`,
        selectionStart: 3,
        selectionEnd: 3 + trimmed.length,
      }
    }, '标题')
    return
  }
  if (action === 'list') {
    replaceSelectedBlock((selected) => addPrefixToLines(selected, '- '), '列表项')
    return
  }
  if (action === 'ordered-list') {
    replaceSelectedBlock((selected) => addOrderedPrefixToLines(selected), '列表项')
    return
  }
  if (action === 'link') {
    openLinkPanel()
    return
  }
  if (action === 'image') {
    openImagePanel()
  }
}

async function onSelectImage(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement | null
  const file = target?.files?.[0]
  if (!file || !props.uploadImage) {
    if (target) target.value = ''
    return
  }

  try {
    const url = await props.uploadImage(file)
    if (!url) return
    imageUrl.value = url
    const result = insertText(
      String(props.modelValue || ''),
      createMarkdownImage(url, String(imageAlt.value || '').trim() || 'image'),
      pendingSelection.value,
    )
    showImagePanel.value = false
    imageError.value = ''
    applyResult(result.value, result.selection.start, result.selection.end)
  } finally {
    if (target) target.value = ''
  }
}

function onTextareaInput(event: Event): void {
  const target = event.target as HTMLTextAreaElement | null
  const rawValue = target?.value ?? ''
  const cursor = target?.selectionStart ?? rawValue.length
  const normalized = normalizeAllOrderedLists(rawValue, cursor)

  updateValue(normalized.value)
  focusTextarea(normalized.cursor, normalized.cursor)
}

function onTextareaKeydown(event: KeyboardEvent): void {
  if ((showLinkPanel.value || showImagePanel.value) && event.key === 'Escape') {
    event.preventDefault()
    if (showLinkPanel.value) closeLinkPanel()
    if (showImagePanel.value) closeImagePanel()
    return
  }
  if (event.key === 'Backspace') {
    const selection = getSelection()
    if (selection.start === selection.end) {
      const currentValue = String(props.modelValue || '')
      const line = getCurrentLine(currentValue, selection.start)
      const unorderedEmptyMatch = line.text.match(/^(\s*)-\s$/)

      if (unorderedEmptyMatch && selection.start === line.end) {
        event.preventDefault()
        const nextValue = `${currentValue.slice(0, line.start)}${unorderedEmptyMatch[1]}${currentValue.slice(line.end)}`
        const cursor = line.start + unorderedEmptyMatch[1].length
        applyResult(nextValue, cursor, cursor)
        return
      }
    }
  }
  if (event.key === 'Enter') {
    const selection = getSelection()
    if (selection.start === selection.end) {
      const currentValue = String(props.modelValue || '')
      const line = getCurrentLine(currentValue, selection.start)
      const unorderedMatch = line.text.match(/^(\s*)-\s(.*)$/)
      const orderedMatch = line.text.match(/^(\s*)(\d+)\.\s(.*)$/)

      if (unorderedMatch) {
        event.preventDefault()
        const indent = unorderedMatch[1] || ''
        const content = unorderedMatch[2] || ''
        const insert = content.trim() ? `\n${indent}- ` : '\n'
        const nextValue = `${currentValue.slice(0, selection.start)}${insert}${currentValue.slice(selection.end)}`
        const cursor = selection.start + insert.length
        applyResult(nextValue, cursor, cursor)
        return
      }

      if (orderedMatch) {
        event.preventDefault()
        const indent = orderedMatch[1] || ''
        const order = Number(orderedMatch[2] || '1')
        const content = orderedMatch[3] || ''
        const insert = content.trim() ? `\n${indent}${order + 1}. ` : '\n'
        const rawNextValue = `${currentValue.slice(0, selection.start)}${insert}${currentValue.slice(selection.end)}`
        const nextValue = normalizeOrderedListAround(rawNextValue, line.start)
        const cursor = selection.start + insert.length
        applyResult(nextValue, cursor, cursor)
        return
      }
    }
  }
  if (event.key !== 'Tab') return

  event.preventDefault()
  const selection = getSelection()
  const result = insertText(String(props.modelValue || ''), '  ', selection)
  applyResult(result.value, result.selection.start, result.selection.end)
}

function onDocumentPointerDown(event: MouseEvent): void {
  const target = event.target as Node | null
  if (!target) return

  if (showLinkPanel.value) {
    const clickedLinkPanel = linkPanelRef.value?.contains(target)
    const clickedRoot = rootRef.value?.contains(target)
    if (clickedRoot && !clickedLinkPanel) {
      closeLinkPanel()
    }
  }

  if (showImagePanel.value) {
    const clickedImagePanel = imagePanelRef.value?.contains(target)
    const clickedRoot = rootRef.value?.contains(target)
    if (clickedRoot && !clickedImagePanel) {
      closeImagePanel()
    }
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentPointerDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentPointerDown)
})
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
          class="min-h-[260px] w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
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

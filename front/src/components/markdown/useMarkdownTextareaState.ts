import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'
import { insertText, wrapSelection } from '@/utils/markdownEditor'
import { handleMarkdownKeydown } from '@/components/markdown/handleMarkdownKeydown'
import { normalizeAllOrderedLists } from '@/components/markdown/markdownListUtils'
import { useMarkdownUrlPanels } from '@/components/markdown/useMarkdownUrlPanels'

export type InsertAction = 'bold' | 'italic' | 'quote' | 'code' | 'heading' | 'list' | 'ordered-list' | 'link' | 'image'

export interface MarkdownTextareaProps {
  modelValue: string
  rows?: number
  maxLength?: number
  placeholder?: string
  previewLabel?: string
  uploading?: boolean
  uploadProgress?: number
  uploadImage?: ((file: File) => Promise<string>) | null
}

interface MarkdownTextareaEmit {
  (event: 'update:modelValue', value: string): void
}

export function useMarkdownTextareaState(
  props: MarkdownTextareaProps,
  emit: MarkdownTextareaEmit,
) {
  const textareaRef = ref<HTMLTextAreaElement | null>(null)
  const rootRef = ref<HTMLElement | null>(null)
  const showPreview = ref(true)
  const markdownRenderer = createMarkdownRenderer({ katex: true, normalizeImages: true })

  const previewHtml = computed(() => renderMarkdown(markdownRenderer, props.modelValue))
  const contentLength = computed(() => String(props.modelValue || '').length)

  function updateValue(value: string): void {
    emit('update:modelValue', value)
  }

  function focusTextarea(start?: number, end?: number): void {
    nextTick(() => {
      const element = textareaRef.value
      if (!element) return
      element.focus()
      if (typeof start === 'number' && typeof end === 'number') {
        element.setSelectionRange(start, end)
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
      value: selected.split('\n').map((line) => `${prefix}${line || ''}`).join('\n'),
    }
  }

  function addOrderedPrefixToLines(selected: string): { value: string } {
    const lines = selected.split('\n')
    return {
      value: lines.map((line, index) => `${index + 1}. ${line || ''}`).join('\n'),
    }
  }

  const {
    closeImagePanel,
    closeLinkPanel,
    fileInputRef,
    imageAlt,
    imageAltInputRef,
    imageError,
    imagePanelRef,
    imageUrl,
    linkError,
    linkLabel,
    linkPanelRef,
    linkTextInputRef,
    linkUrl,
    onSelectImage,
    openImagePanel,
    openLinkPanel,
    showImagePanel,
    showLinkPanel,
    submitImagePanel,
    submitLinkPanel,
  } = useMarkdownUrlPanels({
    getValue: () => String(props.modelValue || ''),
    getSelection,
    focusTextarea,
    applyResult,
    uploadImage: props.uploadImage,
  })

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
        return { value, selectionStart: 8, selectionEnd: 8 + content.length }
      }, '代码块')
      return
    }
    if (action === 'heading') {
      replaceSelectedBlock((selected) => {
        const trimmed = selected.trim() || '标题'
        return { value: `## ${trimmed}`, selectionStart: 3, selectionEnd: 3 + trimmed.length }
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

  function onTextareaInput(event: Event): void {
    const target = event.target as HTMLTextAreaElement | null
    const rawValue = target?.value ?? ''
    const cursor = target?.selectionStart ?? rawValue.length
    const normalized = normalizeAllOrderedLists(rawValue, cursor)
    updateValue(normalized.value)
    focusTextarea(normalized.cursor, normalized.cursor)
  }

  function onTextareaKeydown(event: KeyboardEvent): void {
    handleMarkdownKeydown(event, {
      value: String(props.modelValue || ''),
      selection: getSelection(),
      showLinkPanel: showLinkPanel.value,
      showImagePanel: showImagePanel.value,
      closeLinkPanel,
      closeImagePanel,
      applyResult,
    })
  }

  function onDocumentPointerDown(event: MouseEvent): void {
    const target = event.target as Node | null
    if (!target) return
    if (showLinkPanel.value) {
      const clickedLinkPanel = linkPanelRef.value?.contains(target)
      const clickedRoot = rootRef.value?.contains(target)
      if (clickedRoot && !clickedLinkPanel) closeLinkPanel()
    }
    if (showImagePanel.value) {
      const clickedImagePanel = imagePanelRef.value?.contains(target)
      const clickedRoot = rootRef.value?.contains(target)
      if (clickedRoot && !clickedImagePanel) closeImagePanel()
    }
  }

  onMounted(() => {
    document.addEventListener('mousedown', onDocumentPointerDown)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('mousedown', onDocumentPointerDown)
  })

  return {
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
    closeImagePanel,
    closeLinkPanel,
  }
}

import { nextTick, ref } from 'vue'
import { createMarkdownImage, createMarkdownLink, insertText } from '@/utils/markdownEditor'

interface Selection {
  start: number
  end: number
}

interface PanelHelpers {
  getValue: () => string
  getSelection: () => Selection
  focusTextarea: (start?: number, end?: number) => void
  applyResult: (value: string, start: number, end: number) => void
  uploadImage: ((file: File) => Promise<string>) | null | undefined
}

export function useMarkdownUrlPanels({
  getValue,
  getSelection,
  focusTextarea,
  applyResult,
  uploadImage,
}: PanelHelpers) {
  const fileInputRef = ref<HTMLInputElement | null>(null)
  const linkTextInputRef = ref<HTMLInputElement | null>(null)
  const imageAltInputRef = ref<HTMLInputElement | null>(null)
  const linkPanelRef = ref<HTMLElement | null>(null)
  const imagePanelRef = ref<HTMLElement | null>(null)
  const showLinkPanel = ref(false)
  const showImagePanel = ref(false)
  const linkLabel = ref('')
  const linkUrl = ref('https://')
  const linkError = ref('')
  const imageAlt = ref('image')
  const imageUrl = ref('https://')
  const imageError = ref('')
  const pendingSelection = ref<Selection>({ start: 0, end: 0 })

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

  function openLinkPanel(): void {
    const selection = getSelection()
    const selectedText = getValue().slice(selection.start, selection.end).trim()
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
    const result = insertText(getValue(), createMarkdownLink(url, label), pendingSelection.value)
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
    const result = insertText(getValue(), createMarkdownImage(url, alt), pendingSelection.value)
    showImagePanel.value = false
    imageError.value = ''
    applyResult(result.value, result.selection.start, result.selection.end)
  }

  async function onSelectImage(event: Event): Promise<void> {
    const target = event.target as HTMLInputElement | null
    const file = target?.files?.[0]
    if (!file || !uploadImage) {
      if (target) target.value = ''
      return
    }
    try {
      const url = await uploadImage(file)
      if (!url) return
      imageUrl.value = url
      const result = insertText(
        getValue(),
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

  return {
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
  }
}

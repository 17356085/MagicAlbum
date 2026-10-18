import { nextTick, onBeforeUnmount, ref } from 'vue'
import { createMarkdownImage, createMarkdownLink, replaceSelection } from '@/utils/markdownEditor'

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
  isDisabled?: () => boolean
}

export function useMarkdownUrlPanels({
  getValue,
  getSelection,
  focusTextarea,
  applyResult,
  uploadImage,
  isDisabled,
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
  const imageContentSnapshot = ref('')
  const imageUploadInFlight = ref(false)
  let disposed = false
  let imagePanelGeneration = 0
  let imageUploadSequence = 0

  function isEditorDisabled(): boolean {
    try {
      return Boolean(isDisabled?.())
    } catch (_) {
      return true
    }
  }

  function cloneSelection(selection: Selection): Selection {
    return { start: Number(selection.start || 0), end: Number(selection.end || 0) }
  }

  function isCurrentImageUpload(uploadId: number, panelGeneration: number): boolean {
    return !disposed
      && !isEditorDisabled()
      && showImagePanel.value
      && imageUploadInFlight.value
      && imageUploadSequence === uploadId
      && imagePanelGeneration === panelGeneration
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

  function openLinkPanel(): void {
    if (disposed || isEditorDisabled()) return
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
    if (disposed || isEditorDisabled()) return
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
    const result = replaceSelection(getValue(), createMarkdownLink(url, label), pendingSelection.value)
    showLinkPanel.value = false
    linkError.value = ''
    applyResult(result.value, result.selection.start, result.selection.end)
  }

  function openImagePanel(): void {
    if (disposed || isEditorDisabled()) return
    imagePanelGeneration += 1
    imageUploadSequence += 1
    imageUploadInFlight.value = false
    pendingSelection.value = getSelection()
    imageContentSnapshot.value = getValue()
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
    imagePanelGeneration += 1
    imageUploadSequence += 1
    showImagePanel.value = false
    imageUploadInFlight.value = false
    imageError.value = ''
    focusTextarea(pendingSelection.value.start, pendingSelection.value.end)
  }

  function submitImagePanel(): void {
    if (disposed || isEditorDisabled()) return
    if (imageUploadInFlight.value) {
      imageError.value = '请等待图片上传完成'
      return
    }
    const url = String(imageUrl.value || '').trim()
    const alt = String(imageAlt.value || '').trim() || 'image'
    if (!url) {
      imageError.value = '请输入图片地址'
      return
    }
    if (!isValidUrl(url)) {
      // Uploaded local images use stable same-origin paths.
      if (!/^\/(?!\/)[^\s\\]+$/.test(url)) {
        imageError.value = '图片地址格式无效，请输入 http:// 或 https:// 开头的地址'
        return
      }
    }
    const currentValue = getValue()
    // If the body changed while the panel was open, only a fresh selection is
    // safe. The click on this button is the explicit confirmation to use it.
    const selection = currentValue === imageContentSnapshot.value
      ? pendingSelection.value
      : cloneSelection(getSelection())
    const result = replaceSelection(currentValue, createMarkdownImage(url, alt), selection)
    showImagePanel.value = false
    imageError.value = ''
    applyResult(result.value, result.selection.start, result.selection.end)
  }

  async function onSelectImage(event: Event): Promise<void> {
    const target = event.target as HTMLInputElement | null
    const file = target?.files?.[0]
    if (!file || !uploadImage || disposed || isEditorDisabled() || !showImagePanel.value || imageUploadInFlight.value) {
      if (target) target.value = ''
      return
    }
    const uploadId = ++imageUploadSequence
    const panelGeneration = imagePanelGeneration
    const contentSnapshot = imageContentSnapshot.value
    const selectionSnapshot = cloneSelection(pendingSelection.value)
    const altSnapshot = String(imageAlt.value || '').trim() || 'image'
    imageUploadInFlight.value = true
    try {
      const url = await uploadImage(file)
      if (!url || !isCurrentImageUpload(uploadId, panelGeneration)) return
      imageUrl.value = url
      const currentValue = getValue()
      if (currentValue !== contentSnapshot) {
        imageError.value = '正文已变化，请重新选择插入位置后点击“插入图片”'
        return
      }
      const result = replaceSelection(currentValue, createMarkdownImage(url, altSnapshot), selectionSnapshot)
      showImagePanel.value = false
      imageError.value = ''
      applyResult(result.value, result.selection.start, result.selection.end)
    } catch (_) {
      if (isCurrentImageUpload(uploadId, panelGeneration)) imageError.value = '图片上传失败，请稍后重试'
    } finally {
      if (imageUploadSequence === uploadId) imageUploadInFlight.value = false
      if (target) target.value = ''
    }
  }

  onBeforeUnmount(() => {
    disposed = true
    imagePanelGeneration += 1
    imageUploadSequence += 1
    imageUploadInFlight.value = false
  })

  return {
    closeImagePanel,
    closeLinkPanel,
    fileInputRef,
    imageAlt,
    imageAltInputRef,
    imageError,
    imagePanelRef,
    imageUploadInFlight,
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

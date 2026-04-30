import { getCurrentLine, normalizeOrderedListAround } from '@/components/markdown/markdownListUtils'
import { insertText } from '@/utils/markdownEditor'

interface Selection {
  start: number
  end: number
}

interface KeydownContext {
  value: string
  selection: Selection
  showLinkPanel: boolean
  showImagePanel: boolean
  closeLinkPanel: () => void
  closeImagePanel: () => void
  applyResult: (value: string, start: number, end: number) => void
}

export function handleMarkdownKeydown(event: KeyboardEvent, context: KeydownContext): void {
  const {
    value,
    selection,
    showLinkPanel,
    showImagePanel,
    closeLinkPanel,
    closeImagePanel,
    applyResult,
  } = context

  if ((showLinkPanel || showImagePanel) && event.key === 'Escape') {
    event.preventDefault()
    if (showLinkPanel) closeLinkPanel()
    if (showImagePanel) closeImagePanel()
    return
  }

  if (event.key === 'Backspace' && selection.start === selection.end) {
    const line = getCurrentLine(value, selection.start)
    const unorderedEmptyMatch = line.text.match(/^(\s*)-\s$/)
    if (unorderedEmptyMatch && selection.start === line.end) {
      event.preventDefault()
      const nextValue = `${value.slice(0, line.start)}${unorderedEmptyMatch[1]}${value.slice(line.end)}`
      const cursor = line.start + unorderedEmptyMatch[1].length
      applyResult(nextValue, cursor, cursor)
      return
    }
  }

  if (event.key === 'Enter' && selection.start === selection.end) {
    const line = getCurrentLine(value, selection.start)
    const unorderedMatch = line.text.match(/^(\s*)-\s(.*)$/)
    const orderedMatch = line.text.match(/^(\s*)(\d+)\.\s(.*)$/)
    if (unorderedMatch) {
      event.preventDefault()
      const indent = unorderedMatch[1] || ''
      const content = unorderedMatch[2] || ''
      const insert = content.trim() ? `\n${indent}- ` : '\n'
      const nextValue = `${value.slice(0, selection.start)}${insert}${value.slice(selection.end)}`
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
      const rawNextValue = `${value.slice(0, selection.start)}${insert}${value.slice(selection.end)}`
      const nextValue = normalizeOrderedListAround(rawNextValue, line.start)
      const cursor = selection.start + insert.length
      applyResult(nextValue, cursor, cursor)
      return
    }
  }

  if (event.key !== 'Tab') {
    return
  }

  event.preventDefault()
  const result = insertText(value, '  ', selection)
  applyResult(result.value, result.selection.start, result.selection.end)
}

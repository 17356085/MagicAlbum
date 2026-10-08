export interface TextSelectionRange {
  start: number
  end: number
}

export interface WrappedTextResult {
  value: string
  selection: TextSelectionRange
}

function clampRange(value: string, range?: Partial<TextSelectionRange>): TextSelectionRange {
  const length = value.length
  const start = Math.max(0, Math.min(Number(range?.start ?? length), length))
  const end = Math.max(start, Math.min(Number(range?.end ?? start), length))
  return { start, end }
}

export function wrapSelection(
  value: string,
  prefix: string,
  suffix: string = prefix,
  range?: Partial<TextSelectionRange>,
  fallback = '',
): WrappedTextResult {
  const safeValue = String(value || '')
  const safeRange = clampRange(safeValue, range)
  const selected = safeValue.slice(safeRange.start, safeRange.end) || fallback
  const nextValue = `${safeValue.slice(0, safeRange.start)}${prefix}${selected}${suffix}${safeValue.slice(safeRange.end)}`
  const selectionStart = safeRange.start + prefix.length
  const selectionEnd = selectionStart + selected.length

  return {
    value: nextValue,
    selection: { start: selectionStart, end: selectionEnd },
  }
}

export function insertText(
  value: string,
  text: string,
  range?: Partial<TextSelectionRange>,
): WrappedTextResult {
  return wrapSelection(value, '', '', range, text)
}

export function appendLine(
  value: string,
  line: string,
  range?: Partial<TextSelectionRange>,
): WrappedTextResult {
  const safeValue = String(value || '')
  const safeRange = clampRange(safeValue, range)
  const prefix = safeRange.start > 0 && !safeValue.slice(0, safeRange.start).endsWith('\n') ? '\n' : ''
  const suffix = safeRange.end < safeValue.length && !safeValue.slice(safeRange.end).startsWith('\n') ? '\n' : ''
  const nextValue = `${safeValue.slice(0, safeRange.start)}${prefix}${line}${suffix}${safeValue.slice(safeRange.end)}`
  const cursor = safeRange.start + prefix.length + line.length

  return {
    value: nextValue,
    selection: { start: cursor, end: cursor },
  }
}

export function createMarkdownImage(url: string, alt = 'image'): string {
  return `![${alt}](${url})`
}

export function createMarkdownLink(url: string, label = 'link'): string {
  return `[${label}](${url})`
}

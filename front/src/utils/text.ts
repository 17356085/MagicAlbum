import DOMPurify from 'dompurify'

export function escapeHtml(text: string | null | undefined): string {
  return String(text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export function highlightText(
  text: string | null | undefined,
  keyword: string | null | undefined,
  markClass = 'bg-yellow-100',
): string {
  const escapedText = escapeHtml(text)
  const normalizedKeyword = String(keyword || '').trim()
  if (!normalizedKeyword) return escapedText

  const escapedKeyword = normalizedKeyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matcher = new RegExp(`(${escapedKeyword})`, 'ig')
  const highlighted = escapedText.replace(matcher, `<mark class="${markClass}">$1</mark>`)
  return DOMPurify.sanitize(highlighted)
}

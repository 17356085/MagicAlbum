export const SITE_NAME = '同频'

export function formatPageTitle(title?: string): string {
  const normalizedTitle = String(title || '').trim()
  if (!normalizedTitle || normalizedTitle === SITE_NAME) return SITE_NAME

  const suffix = ` · ${SITE_NAME}`
  return normalizedTitle.endsWith(suffix) ? normalizedTitle : `${normalizedTitle}${suffix}`
}

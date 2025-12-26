export function normalizeImageUrl(u) {
  if (!u) return ''
  const url = String(u).trim()
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
    return url
  }
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
  const backendBase = apiBase.replace(/\/api\/v1$/, '')
  if (url.startsWith('/')) return backendBase + url
  return backendBase + '/' + url
}
export type SearchType = 'threads' | 'users'

export interface SearchHistoryEntry {
  type: SearchType
  query: string
}

const maximumEntries = 10
const maximumLength = 200

function storageKey(owner: string): string {
  return 'bluealbum.search-history.v1.' + encodeURIComponent(owner)
}

export function readSearchHistory(owner: string | null): SearchHistoryEntry[] {
  if (!owner) return []
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(storageKey(owner)) || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter((entry): entry is SearchHistoryEntry =>
      entry !== null && (entry.type === 'threads' || entry.type === 'users')
      && typeof entry.query === 'string' && entry.query.trim().length > 0
      && entry.query.length <= maximumLength).slice(0, maximumEntries)
  } catch (_) {
    return []
  }
}

export function recordSearchHistory(owner: string | null, type: SearchType, query: string): SearchHistoryEntry[] {
  if (!owner) return []
  const old = readSearchHistory(owner)
  if (!query || query.length > maximumLength) return old
  const entries = [{ type, query }, ...old.filter(item => item.type !== type
    || item.query.toLowerCase() !== query.toLowerCase())].slice(0, maximumEntries)
  try { localStorage.setItem(storageKey(owner), JSON.stringify(entries)) } catch (_) { /* Storage is optional. */ }
  return entries
}

export function clearSearchHistory(owner: string | null): void {
  if (!owner) return
  try { localStorage.removeItem(storageKey(owner)) } catch (_) { /* Storage is optional. */ }
}

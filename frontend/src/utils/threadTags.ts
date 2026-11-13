export const THREAD_TAG_LIMIT = 5

export function parseThreadTags(raw: unknown): string[] {
  const tags: string[] = []
  const seen = new Set<string>()

  for (const value of String(raw || '').split(/[,，、\s]+/)) {
    const tag = value.trim().replace(/^#+/, '')
    if (!tag) continue
    const key = tag.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    tags.push(tag)
    if (tags.length >= THREAD_TAG_LIMIT) break
  }

  return tags
}

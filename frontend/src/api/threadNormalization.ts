import type { Thread } from '@/types'

export function normalizeThread(raw: Thread & { authorAvatar?: string }): Thread {
  if (!raw) return raw
  return {
    ...raw,
    authorAvatarUrl: raw.authorAvatarUrl || raw.authorAvatar || '',
  }
}

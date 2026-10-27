import { describe, expect, it, vi } from 'vitest'
import type { Thread } from '@/types'
import { normalizeThread } from '../threadNormalization'

const clientState = vi.hoisted(() => ({ throwOnImport: false }))
const clientMethods = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
}))

vi.mock('../client', () => {
  if (clientState.throwOnImport) {
    throw new Error('HTTP client must not initialize for thread normalization')
  }
  return { default: clientMethods }
})

describe('normalizeThread', () => {
  it('maps the legacy avatar field without mutating or dropping extra fields', () => {
    const raw = {
      id: 7,
      title: '保留原始帖子',
      authorAvatar: '/legacy-avatar.png',
      extraMetadata: { source: 'fixture' },
    } satisfies Thread & { authorAvatar?: string; extraMetadata: { source: string } }
    const before = { ...raw, extraMetadata: { ...raw.extraMetadata } }

    const result = normalizeThread(raw)

    expect(result).toEqual({ ...raw, authorAvatarUrl: '/legacy-avatar.png' })
    expect(result).not.toBe(raw)
    expect(raw).toEqual(before)
  })

  it('keeps the canonical avatar when it is truthy and preserves falsy fallback behavior', () => {
    expect(normalizeThread({
      id: 8,
      title: '已有新头像',
      authorAvatarUrl: '/canonical-avatar.png',
      authorAvatar: '/legacy-avatar.png',
    })).toMatchObject({ authorAvatarUrl: '/canonical-avatar.png' })

    expect(normalizeThread({
      id: 9,
      title: '只有旧头像',
      authorAvatarUrl: '',
      authorAvatar: '/legacy-avatar.png',
    })).toMatchObject({ authorAvatarUrl: '/legacy-avatar.png' })
  })

  it('remains available from the legacy threads API module', async () => {
    const { normalizeThread: normalizeFromThreads } = await import('../threads')
    const raw = { id: 10, title: '兼容导出', authorAvatar: '/avatar.png' } satisfies Thread & { authorAvatar?: string }

    expect(normalizeFromThreads(raw)).toEqual({ ...raw, authorAvatarUrl: '/avatar.png' })
  })

  it('imports without initializing the HTTP client', async () => {
    clientState.throwOnImport = true
    vi.resetModules()

    try {
      const module = await import('../threadNormalization')
      const raw: Thread = { id: 11, title: '纯模块导入' }

      expect(module.normalizeThread(raw)).toEqual({ ...raw, authorAvatarUrl: '' })
    } finally {
      clientState.throwOnImport = false
      vi.resetModules()
    }
  })
})

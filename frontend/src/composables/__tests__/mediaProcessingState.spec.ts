import { beforeEach, describe, expect, it } from 'vitest'
import { persistAuthState } from '@/utils/authStorage'
import { decodeMediaAsset, dismissMediaAsset, hydrateMediaAssets, mediaAssets, trackMediaUpload, updateMediaAsset } from '../mediaProcessingState'

const asset = { assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/original.png', processingStatus: 'PENDING' as const }
function owner(id: number, token: string) {
  persistAuthState({ accessToken: token, user: { id, username: `owner-${id}` } })
  hydrateMediaAssets(token)
}

describe('owner-scoped media status', () => {
  beforeEach(() => { localStorage.clear(); hydrateMediaAssets('') })
  it('keeps account caches separate and discards an old response', () => {
    owner(1, 'first')
    trackMediaUpload(asset, 'first')
    expect(mediaAssets.value).toHaveLength(1)
    owner(2, 'second')
    expect(mediaAssets.value).toEqual([])
    trackMediaUpload(asset, 'first')
    updateMediaAsset({ ...asset, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/preview.png' }, 'first', true)
    expect(mediaAssets.value).toEqual([])
    owner(1, 'rotated')
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(localStorage.getItem('bluealbum:media:1')).not.toContain('first')
  })

  it('blocks stale owner events after an external token switch before hydration', () => {
    owner(1, 'first')
    trackMediaUpload(asset, 'first')
    const firstCache = localStorage.getItem('bluealbum:media:1')

    persistAuthState({ accessToken: 'second', user: { id: 2, username: 'owner-2' } })
    dismissMediaAsset(asset.assetId)
    updateMediaAsset({ ...asset, processingStatus: 'SUCCEEDED' }, 'first', true)
    trackMediaUpload({ ...asset, processingStatus: 'SUCCEEDED' }, 'first')

    expect(mediaAssets.value).toEqual([expect.objectContaining(asset)])
    expect(localStorage.getItem('bluealbum:media:1')).toBe(firstCache)
    expect(localStorage.getItem('bluealbum:media:2')).toBeNull()

    hydrateMediaAssets('second')
    expect(mediaAssets.value).toEqual([])

    owner(1, 'first')
    expect(mediaAssets.value).toEqual([expect.objectContaining(asset)])
  })

  it('does not create a cache for a token-only session', () => {
    persistAuthState({ accessToken: 'token-only', user: null })
    hydrateMediaAssets('token-only')

    trackMediaUpload(asset, 'token-only')
    updateMediaAsset(asset, 'token-only', true)
    dismissMediaAsset(asset.assetId)

    expect(mediaAssets.value).toEqual([])
    expect(localStorage.getItem('bluealbum:media:1')).toBeNull()
  })

  it('stops writes when the bound owner changes under the same token', () => {
    owner(1, 'stable-token')
    trackMediaUpload(asset, 'stable-token')
    const firstCache = localStorage.getItem('bluealbum:media:1')

    localStorage.setItem('currentUserSession.v1', JSON.stringify({
      accessToken: 'stable-token',
      user: { id: 2, username: 'owner-2' },
    }))
    dismissMediaAsset(asset.assetId)
    updateMediaAsset({ ...asset, processingStatus: 'SUCCEEDED' }, 'stable-token', true)

    expect(mediaAssets.value).toEqual([expect.objectContaining(asset)])
    expect(localStorage.getItem('bluealbum:media:1')).toBe(firstCache)
    expect(localStorage.getItem('bluealbum:media:2')).toBeNull()
  })

  it('rejects a cache hydration when the token is paired with another owner', () => {
    owner(1, 'first')
    trackMediaUpload(asset, 'first')
    const firstCache = localStorage.getItem('bluealbum:media:1')

    localStorage.setItem('accessToken', 'second')
    hydrateMediaAssets('second')

    expect(mediaAssets.value).toEqual([])
    expect(localStorage.getItem('bluealbum:media:1')).toBe(firstCache)
  })

  it('limits history and treats legacy uploads as original-only', () => {
    owner(1, 'token')
    trackMediaUpload({ url: '/legacy.png' }, 'token')
    expect(mediaAssets.value).toEqual([])
    for (let index = 0; index < 25; index++) trackMediaUpload({ ...asset, assetId: `d2a1665b-60b1-40f9-85a5-${String(index).padStart(12, '0')}` }, 'token')
    expect(mediaAssets.value).toHaveLength(20)
    hydrateMediaAssets('token')
    expect(mediaAssets.value).toHaveLength(20)
  })
  it('rejects executable URLs and suppresses an unsafe preview', () => {
    expect(decodeMediaAsset({ ...asset, url: 'javascript:alert(1)' })).toBeNull()
    expect(decodeMediaAsset({ ...asset, previewUrl: 'data:text/html,unsafe' })?.previewUrl).toBeNull()
    expect(decodeMediaAsset({ ...asset, processingStatus: 'invented' })).toBeNull()
  })
})

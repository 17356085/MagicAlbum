import { beforeEach, describe, expect, it } from 'vitest'
import { persistAuthState } from '@/utils/authStorage'
import { decodeMediaAsset, dismissMediaAsset, hydrateMediaAssets, isCurrentMediaCacheOwner, mediaAssets, trackMediaUpload, updateMediaAsset, updateMediaAssetIfCurrent } from '../mediaProcessingState'

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

  it('exposes only the current token-bound media cache owner', () => {
    owner(1, 'stable-token')
    expect(isCurrentMediaCacheOwner('stable-token')).toBe(true)
    expect(isCurrentMediaCacheOwner('other-token')).toBe(false)

    persistAuthState({ accessToken: 'stable-token', user: { id: 2, username: 'owner-2' } })
    expect(isCurrentMediaCacheOwner('stable-token')).toBe(false)
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

  it('conditionally replaces the exact current record and persists only for its owner', () => {
    owner(1, 'cas-token')
    trackMediaUpload(asset, 'cas-token')
    const expected = mediaAssets.value[0]!
    const latest = { ...asset, processingStatus: 'SUCCEEDED' as const, previewUrl: '/uploads/preview.png' }

    expect(updateMediaAssetIfCurrent(expected, latest, 'cas-token')).toBe(true)
    expect(mediaAssets.value[0]).toEqual(latest)
    expect(localStorage.getItem('bluealbum:media:1')).toContain('preview.png')

    expect(updateMediaAssetIfCurrent(expected, { ...latest, processingStatus: 'FAILED' }, 'cas-token')).toBe(false)
    expect(mediaAssets.value[0]?.processingStatus).toBe('SUCCEEDED')

    localStorage.setItem('accessToken', 'different-token')
    expect(updateMediaAssetIfCurrent(mediaAssets.value[0]!, { ...latest, processingStatus: 'FAILED' }, 'cas-token')).toBe(false)
    expect(mediaAssets.value[0]?.processingStatus).toBe('SUCCEEDED')
  })

  it('rejects a conditional update when the response asset id does not match', () => {
    owner(1, 'cas-id-token')
    trackMediaUpload(asset, 'cas-id-token')
    const expected = mediaAssets.value[0]!
    const otherAsset = { ...asset, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', processingStatus: 'SUCCEEDED' as const }

    expect(updateMediaAssetIfCurrent(expected, otherAsset, 'cas-id-token')).toBe(false)
    expect(mediaAssets.value[0]).toBe(expected)
    expect(localStorage.getItem('bluealbum:media:1')).not.toContain(otherAsset.assetId)
  })
  it('deduplicates decoded cache entries without letting invalid records claim an id', () => {
    owner(1, 'token')
    const firstValid = { ...asset, url: '/uploads/first-valid.png' }
    const firstDuplicate = { ...firstValid, url: '/uploads/first-duplicate.png', processingStatus: 'SUCCEEDED' as const }
    const secondValid = { ...asset, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/second-valid.png' }
    const thirdValid = { ...asset, assetId: 'f2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/third-valid.png' }
    const invalidFirst = { ...firstValid, processingStatus: 'invalid' }
    const invalidSecond = { ...secondValid, url: 'javascript:alert(1)' }
    localStorage.setItem('bluealbum:media:1', JSON.stringify([
      invalidFirst,
      firstValid,
      firstDuplicate,
      secondValid,
      invalidSecond,
      thirdValid,
    ]))

    hydrateMediaAssets('token')

    expect(mediaAssets.value.map(item => item.assetId)).toEqual([
      firstValid.assetId,
      secondValid.assetId,
      thirdValid.assetId,
    ])
    expect(mediaAssets.value.map(item => item.url)).toEqual([
      firstValid.url,
      secondValid.url,
      thirdValid.url,
    ])
  })
  it('rejects executable URLs and suppresses an unsafe preview', () => {
    expect(decodeMediaAsset({ ...asset, url: 'javascript:alert(1)' })).toBeNull()
    expect(decodeMediaAsset({ ...asset, previewUrl: 'data:text/html,unsafe' })?.previewUrl).toBeNull()
    expect(decodeMediaAsset({ ...asset, processingStatus: 'invented' })).toBeNull()
  })
})

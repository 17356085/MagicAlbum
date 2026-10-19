import { beforeEach, describe, expect, it } from 'vitest'
import { decodeMediaAsset, hydrateMediaAssets, mediaAssets, trackMediaUpload, updateMediaAsset } from '../mediaProcessingState'

const asset = { assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/original.png', processingStatus: 'PENDING' as const }
function owner(id: number, token: string) {
  localStorage.setItem('accessToken', token)
  localStorage.setItem('currentUser', JSON.stringify({ id }))
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

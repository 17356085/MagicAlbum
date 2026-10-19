import { ref } from 'vue'
import { getStoredAccessToken, getStoredCurrentUser } from '@/utils/authStorage'

export type ProcessingStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'UNSUPPORTED' | 'NOT_REQUESTED'
export interface MediaAssetState {
  assetId: string
  url: string
  status?: string
  jobId?: string | null
  processingStatus: ProcessingStatus
  previewUrl?: string | null
  errorCode?: string | null
  unavailable?: boolean
}

export const mediaAssets = ref<MediaAssetState[]>([])
let cacheKey = ''
const uuid = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
const statuses: ProcessingStatus[] = ['PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNSUPPORTED', 'NOT_REQUESTED']

export function safeMediaUrl(value: unknown): value is string {
  if (typeof value !== 'string' || !value || value.length > 2048) return false
  try { return ['http:', 'https:'].includes(new URL(value, window.location.origin).protocol) }
  catch { return false }
}

export function decodeMediaAsset(value: unknown): MediaAssetState | null {
  if (!value || typeof value !== 'object') return null
  const item = value as Partial<MediaAssetState>
  if (typeof item.assetId !== 'string' || !uuid.test(item.assetId) || !safeMediaUrl(item.url)
    || !statuses.includes(item.processingStatus as ProcessingStatus)) return null
  return {
    assetId: item.assetId, url: item.url, processingStatus: item.processingStatus as ProcessingStatus,
    status: typeof item.status === 'string' ? item.status : undefined,
    jobId: typeof item.jobId === 'string' && uuid.test(item.jobId) ? item.jobId : null,
    previewUrl: safeMediaUrl(item.previewUrl) ? item.previewUrl : null,
    errorCode: typeof item.errorCode === 'string' && item.errorCode.length <= 64 ? item.errorCode : null,
  }
}

export function hydrateMediaAssets(token: string): void {
  mediaAssets.value = []
  cacheKey = ''
  const user = getStoredCurrentUser()
  if (!token || getStoredAccessToken() !== token || !user?.id) return
  cacheKey = `bluealbum:media:${String(user.id)}`
  try {
    const parsed: unknown = JSON.parse(localStorage.getItem(cacheKey) || '[]')
    if (Array.isArray(parsed)) mediaAssets.value = parsed.slice(0, 20).map(decodeMediaAsset).filter((item): item is MediaAssetState => !!item)
  } catch { /* An invalid local cache never affects server ownership. */ }
}

function persist(): void {
  if (!cacheKey) return
  try { localStorage.setItem(cacheKey, JSON.stringify(mediaAssets.value.slice(0, 20))) } catch { /* Quota does not prevent using the original image. */ }
}

export function trackMediaUpload(value: unknown, token: string): void {
  if (!token || getStoredAccessToken() !== token) return
  const item = decodeMediaAsset(value)
  if (!item) return
  updateMediaAsset(item, token, true)
}

export function updateMediaAsset(item: MediaAssetState, token: string, insert = false): void {
  if (!token || getStoredAccessToken() !== token) return
  const index = mediaAssets.value.findIndex(value => value.assetId === item.assetId)
  if (index >= 0) mediaAssets.value[index] = item
  else if (insert) mediaAssets.value = [item, ...mediaAssets.value].slice(0, 20)
  persist()
}

export function dismissMediaAsset(assetId: string): void {
  mediaAssets.value = mediaAssets.value.filter(item => item.assetId !== assetId)
  persist()
}

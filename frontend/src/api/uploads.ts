import api, { assertAutomaticRequestOwnsCurrentSession } from './client'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { AxiosProgressEvent } from 'axios'
import { decodeMediaAsset, trackMediaUpload, type MediaAssetState } from '@/composables/mediaProcessingState'
import { decodeUploadResponse } from './uploadResponse'
import type { UploadImageResponse } from './uploadResponse'
export type { UploadImageResponse } from './uploadResponse'

export async function uploadImage(
  file: File,
  token?: string,
  onProgress?: (percent: number) => void,
): Promise<UploadImageResponse> {
  const form = new FormData()
  form.append('file', file)
  const explicitAuthToken = typeof token === 'string' && hasRealToken(token) ? token : null
  const authToken = explicitAuthToken ?? getStoredAccessToken()
  if (explicitAuthToken === null) assertAutomaticRequestOwnsCurrentSession(authToken)
  const { data } = await api.post('/uploads/images', form, {
    headers: {
      ...(hasRealToken(authToken) ? { Authorization: `Bearer ${authToken}` } : {}),
    },
    onUploadProgress: (evt: AxiosProgressEvent) => {
      if (!evt || !evt.total) return
      const percent = Math.round((evt.loaded / evt.total) * 100)
      if (onProgress) onProgress(percent)
    }
  })
  const response = decodeUploadResponse(data)
  if (hasRealToken(authToken)) trackMediaUpload(response, authToken)
  return response
}

export async function getMediaAsset(assetId: string, token: string, signal?: AbortSignal): Promise<MediaAssetState> {
  const { data } = await api.get(`/uploads/assets/${encodeURIComponent(assetId)}`, {
    signal, headers: { Authorization: `Bearer ${token}` },
  })
  const item = decodeMediaAsset(data)
  if (!item || item.assetId !== assetId) throw new Error('图片状态暂不可用')
  return item
}

export async function changeMediaJob(assetId: string, action: 'retry' | 'cancel', token: string): Promise<MediaAssetState> {
  const { data } = await api.post(`/uploads/assets/${encodeURIComponent(assetId)}/${action}`, undefined, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const item = decodeMediaAsset(data)
  if (!item || item.assetId !== assetId) throw new Error('图片状态暂不可用')
  return item
}

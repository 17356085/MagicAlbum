import api from './client'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { AxiosProgressEvent } from 'axios'
import { decodeMediaAsset, trackMediaUpload, type MediaAssetState } from '@/composables/mediaProcessingState'

export interface UploadImageResponse {
  url?: string
  path?: string
  [key: string]: unknown
}

export async function uploadImage(
  file: File,
  token?: string,
  onProgress?: (percent: number) => void,
): Promise<UploadImageResponse> {
  const form = new FormData()
  form.append('file', file)
  const authToken = hasRealToken(token) ? token : getStoredAccessToken()
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
  if (authToken) trackMediaUpload(data, authToken)
  return data
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

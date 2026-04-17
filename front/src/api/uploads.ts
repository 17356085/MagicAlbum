import api from './client'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { AxiosProgressEvent } from 'axios'

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
      'Content-Type': 'multipart/form-data',
      ...(hasRealToken(authToken) ? { Authorization: `Bearer ${authToken}` } : {}),
    },
    onUploadProgress: (evt: AxiosProgressEvent) => {
      if (!evt || !evt.total) return
      const percent = Math.round((evt.loaded / evt.total) * 100)
      if (onProgress) onProgress(percent)
    }
  })
  return data
}

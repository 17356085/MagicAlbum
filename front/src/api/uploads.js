import api from './client'

export async function uploadImage(file, token, onProgress) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post('/uploads/images', form, {
    headers: {
      'Content-Type': 'multipart/form-data',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    onUploadProgress: (evt) => {
      if (!evt || !evt.total) return
      const percent = Math.round((evt.loaded / evt.total) * 100)
      if (onProgress) onProgress(percent)
    }
  })
  return data
}
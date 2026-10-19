import { onBeforeUnmount, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getMediaAsset } from '@/api/uploads'
import { hydrateMediaAssets, mediaAssets, updateMediaAsset } from './mediaProcessingState'

export function useMediaProcessing(): void {
  const auth = useAuthStore()
  let generation = 0
  let offset = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let request: AbortController | undefined
  let disposed = false
  const stopWatch = watch(() => [auth.token, auth.user?.id], () => {
    generation += 1
    request?.abort()
    if (timer) clearTimeout(timer)
    hydrateMediaAssets(auth.token)
    if (!auth.isLoggedIn) return
    const current = generation
    const token = auth.token
    const initial = new Set(mediaAssets.value.map(item => item.assetId))
    const poll = async () => {
      if (disposed || current !== generation) return
      request = new AbortController()
      const candidates = mediaAssets.value.filter(item => initial.has(item.assetId) || ['PENDING', 'PROCESSING'].includes(item.processingStatus))
      const count = Math.min(4, candidates.length)
      if (document.visibilityState !== 'hidden') for (let i = 0; i < count; i++) {
        if (disposed || current !== generation || request.signal.aborted) break
        const item = candidates[(offset + i) % candidates.length]
        if (!item) continue
        try {
          const latest = await getMediaAsset(item.assetId, token, request.signal)
          if (current === generation && !disposed) {
            updateMediaAsset(latest, token)
            initial.delete(item.assetId)
          }
        } catch {
          if (current === generation && !disposed && !request.signal.aborted) item.unavailable = true
        }
      }
      offset += count
      if (!disposed && current === generation) timer = setTimeout(() => { void poll() }, 2500)
    }
    void poll()
  }, { immediate: true, flush: 'post' })
  onBeforeUnmount(() => {
    disposed = true; generation += 1
    request?.abort()
    if (timer) clearTimeout(timer)
    stopWatch()
  })
}

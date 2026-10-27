import { onBeforeUnmount, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getMediaAsset } from '@/api/uploads'
import { hydrateMediaAssets, mediaAssets, updateMediaAsset } from './mediaProcessingState'

const POLL_DELAY = 2500
const ACTIVE_STATUSES = ['PENDING', 'PROCESSING'] as const
const isDocumentVisible = (): boolean => document.visibilityState !== 'hidden'

type PollSession = {
  current: number
  token: string
  offset: number
  initial: Set<string>
  running: boolean
  timer: ReturnType<typeof setTimeout> | undefined
  request: AbortController | undefined
  disposed: boolean
  stopMediaWatch: (() => void) | undefined
}

export function useMediaProcessing(): void {
  const auth = useAuthStore()
  let generation = 0
  let disposed = false
  let disposeSession: (() => void) | undefined

  const stopSessionTimer = (session: PollSession): void => {
    if (session.timer !== undefined) {
      clearTimeout(session.timer)
      session.timer = undefined
    }
  }

  const stopWatch = watch(() => [auth.token, auth.user?.id], () => {
    generation += 1
    disposeSession?.()
    disposeSession = undefined
    hydrateMediaAssets(auth.token)
    if (!auth.isLoggedIn) return

    const session: PollSession = {
      current: generation,
      token: auth.token,
      offset: 0,
      initial: new Set(mediaAssets.value.map(item => item.assetId)),
      running: false,
      timer: undefined,
      request: undefined,
      disposed: false,
      stopMediaWatch: undefined,
    }
    const currentSession = (): boolean => !disposed && !session.disposed && session.current === generation
    const isCandidate = (item: typeof mediaAssets.value[number]): boolean => (
      session.initial.has(item.assetId) || ACTIVE_STATUSES.includes(item.processingStatus as typeof ACTIVE_STATUSES[number])
    )
    const candidates = (): typeof mediaAssets.value => mediaAssets.value.filter(isCandidate)
    const scheduleNext = (): void => {
      if (!currentSession() || session.running || session.timer !== undefined || candidates().length === 0) return
      session.timer = setTimeout(() => {
        session.timer = undefined
        void poll()
      }, POLL_DELAY)
    }
    async function poll(): Promise<void> {
      if (!currentSession() || session.running) return
      const snapshot = candidates()
      if (snapshot.length === 0) {
        stopSessionTimer(session)
        return
      }

      session.running = true
      const controller = new AbortController()
      session.request = controller
      const count = Math.min(4, snapshot.length)
      try {
        if (isDocumentVisible()) for (let i = 0; i < count; i++) {
          if (!currentSession() || controller.signal.aborted || !isDocumentVisible()) break
          const item = mediaAssets.value.find(value => value.assetId === snapshot[(session.offset + i) % snapshot.length]?.assetId)
          if (!item || !isCandidate(item)) continue
          try {
            const latest = await getMediaAsset(item.assetId, session.token, controller.signal)
            if (currentSession() && !controller.signal.aborted && mediaAssets.value.some(value => value.assetId === item.assetId)) {
              updateMediaAsset(latest, session.token)
              session.initial.delete(item.assetId)
            }
          } catch {
            if (currentSession() && !controller.signal.aborted) {
              const unavailable = mediaAssets.value.find(value => value.assetId === item.assetId)
              if (unavailable) unavailable.unavailable = true
            }
          }
        }
        session.offset += count
      } finally {
        if (session.request === controller) session.request = undefined
        session.running = false
        if (currentSession()) scheduleNext()
      }
    }

    session.stopMediaWatch = watch(mediaAssets, () => {
      if (!currentSession()) return
      const hasCandidates = candidates().length > 0
      if (!hasCandidates) {
        if (!session.running) stopSessionTimer(session)
        return
      }
      if (session.running || session.timer !== undefined) return
      void poll()
    }, { deep: true, flush: 'post' })
    disposeSession = () => {
      session.disposed = true
      session.request?.abort()
      stopSessionTimer(session)
      session.stopMediaWatch?.()
      session.stopMediaWatch = undefined
    }
    void poll()
  }, { immediate: true, flush: 'post' })

  onBeforeUnmount(() => {
    disposed = true
    generation += 1
    disposeSession?.()
    disposeSession = undefined
    stopWatch()
  })
}

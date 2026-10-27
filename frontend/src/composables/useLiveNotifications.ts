import { onBeforeUnmount, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { NotificationStreamError, readNotificationStream } from '@/api/notificationStream'
import { signalNotificationChanges } from './notificationSignals'

/** A single application connection, with owner-scoped replay state kept only in memory. */
export function useLiveNotifications(): void {
  const auth = useAuthStore()
  let controller: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let refreshTimer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let disposed = false
  let lastSessionToken: string | undefined

  const restart = () => {
    generation += 1
    controller?.abort()
    if (timer) clearTimeout(timer)
    if (refreshTimer) clearTimeout(refreshTimer)
    timer = undefined
    refreshTimer = undefined
    const token = auth.token
    const sessionChanged = token !== lastSessionToken
    lastSessionToken = token
    if (sessionChanged) signalNotificationChanges('session')
    if (disposed || !hasRealToken(token) || document.visibilityState === 'hidden') return
    const current = generation
    let cursor: string | undefined
    let failures = 0
    const connect = async () => {
      if (disposed || current !== generation || getStoredAccessToken() !== token) return
      controller = new AbortController()
      signalNotificationChanges('resync')
      try {
        await readNotificationStream(token, cursor, controller.signal, change => {
          if (current !== generation || disposed || getStoredAccessToken() !== token) return
          cursor = change.cursor
          failures = 0
          if (!refreshTimer) refreshTimer = setTimeout(() => {
            refreshTimer = undefined
            if (current === generation && !disposed) signalNotificationChanges('changed')
          }, 100)
        })
      } catch (error) {
        if (error instanceof NotificationStreamError && error.status === 400) cursor = undefined
        failures += 1
      }
      if (!disposed && current === generation && getStoredAccessToken() === token) {
        const delay = Math.min(15000, 1000 * 2 ** Math.min(failures, 4))
        timer = setTimeout(() => { void connect() }, delay)
      }
    }
    void connect()
  }
  const stopWatch = watch(() => auth.token, restart, { immediate: true, flush: 'post' })
  document.addEventListener('visibilitychange', restart)
  onBeforeUnmount(() => {
    disposed = true
    generation += 1
    controller?.abort()
    if (timer) clearTimeout(timer)
    if (refreshTimer) clearTimeout(refreshTimer)
    stopWatch()
    document.removeEventListener('visibilitychange', restart)
  })
}

import { onMounted, onUnmounted } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * Reconcile the in-memory auth session with changes made by another browser
 * context without writing anything back to storage or loading a profile.
 */
export function useAuthStorageSync(): void {
  const auth = useAuthStore()

  const sync = (): void => {
    auth.syncAuthFromStorage()
  }

  const onStorage = (event: StorageEvent): void => {
    if (event.storageArea !== null && event.storageArea !== localStorage) return
    if (event.key !== null && event.key !== 'accessToken') return
    sync()
  }

  const onFocus = (): void => {
    sync()
  }

  const onVisibilityChange = (): void => {
    if (document.visibilityState === 'visible') sync()
  }

  onMounted(() => {
    sync()
    window.addEventListener('storage', onStorage)
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onUnmounted(() => {
    window.removeEventListener('storage', onStorage)
    window.removeEventListener('focus', onFocus)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })
}

import { computed, onScopeDispose, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { getThread } from '@/api/threads'
import { getUserProfile } from '@/api/users'
import { addVisit, captureRecentVisitsScope } from '@/composables/useRecentVisits'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { normalizeImageUrl } from '@/utils/image'
import type { Thread } from '@/types'
import { formatPageTitle } from '@/config/brand'
import { useAuthStore } from '@/stores/auth'

function getRouteParamId(value: string | string[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

export function useThreadDetailPage(route: RouteLocationNormalizedLoaded) {
  const auth = useAuthStore()
  let disposed = false
  let requestSequence = 0
  let authEventEpoch = 0
  let loadedHistoryCallback: (() => void) | null = null
  const loading = ref(false)
  const error = ref('')
  const thread = ref<Thread | null>(null)
  const authorProfileAvatarUrl = ref('')
  const anchorPostId = ref<number | null>(null)

  function currentUserId(): string | number | null {
    return auth.user?.id ?? auth.user?.userId ?? null
  }

  function clearInvalidatedLoading(sequence: number, id: string, path: string): void {
    if (disposed || sequence !== requestSequence) return
    if (getRouteParamId(route.params.id as string | string[] | undefined) !== id || route.path !== path) return
    thread.value = null
    authorProfileAvatarUrl.value = ''
    loading.value = false
  }

  function handleAuthStateChanged(): void {
    if (disposed) return
    authEventEpoch += 1
    requestSequence += 1
    loadedHistoryCallback = null
    thread.value = null
    authorProfileAvatarUrl.value = ''
    anchorPostId.value = null
    error.value = ''
    loading.value = false
  }

  window.addEventListener('auth-state-changed', handleAuthStateChanged)

  onScopeDispose(() => {
    disposed = true
    requestSequence += 1
    loadedHistoryCallback = null
    window.removeEventListener('auth-state-changed', handleAuthStateChanged)
  })

  watch(
    currentUserId,
    (nextId, previousId) => {
      if (disposed || previousId != null || nextId == null) return
      const callback = loadedHistoryCallback
      if (!callback) return
      loadedHistoryCallback = null
      callback()
    },
  )

  const displayAuthorAvatarUrl = computed(() =>
    normalizeImageUrl(thread.value?.authorAvatarUrl || authorProfileAvatarUrl.value || ''),
  )

  function updateAnchorFromHash(): void {
    if (disposed) return
    const hash = String(route.hash || '')
    const match = hash.match(/^#post-(\d+)$/)
    anchorPostId.value = match ? Number(match[1]) : null
  }

  async function load(): Promise<void> {
    if (disposed) return
    const sequence = ++requestSequence
    const id = getRouteParamId(route.params.id as string | string[] | undefined)
    const path = route.path
    const token = auth.token
    const requestedOwnerId = currentUserId()
    let historyScope = captureRecentVisitsScope({ token, ownerId: requestedOwnerId })
    const epoch = authEventEpoch
    loadedHistoryCallback = null
    const isCurrent = (): boolean => !disposed && sequence === requestSequence &&
      getRouteParamId(route.params.id as string | string[] | undefined) === id &&
      route.path === path && authEventEpoch === epoch && auth.token === token &&
      getStoredAccessToken() === token
    loading.value = true
    error.value = ''
    thread.value = null
    authorProfileAvatarUrl.value = ''
    if (!isCurrent()) {
      clearInvalidatedLoading(sequence, id, path)
      return
    }
    try {
      const data = await getThread(id)
      if (!isCurrent()) {
        clearInvalidatedLoading(sequence, id, path)
        return
      }
      thread.value = data

      if (!data.authorAvatarUrl && data.authorId) {
        try {
          const profile = await getUserProfile(data.authorId)
          if (isCurrent()) authorProfileAvatarUrl.value = profile?.avatarUrl || ''
        } catch (_) {}
      }
      if (!isCurrent()) {
        clearInvalidatedLoading(sequence, id, path)
        return
      }
      // Route remounts do not cancel promises: guard persistent history and
      // document metadata as well as this component's local refs.
      try {
        const titleText = String(data.title || '').trim()
        if (titleText) {
          if (route.name === 'thread-detail' && getRouteParamId(route.params.id as string | string[] | undefined) === id) {
            document.title = formatPageTitle(titleText)
          }
        }

        if (!isCurrent()) {
          clearInvalidatedLoading(sequence, id, path)
          return
        }

        const isHistoryIdentityCurrent = (): boolean => {
          if (!isCurrent()) return false
          if (!hasRealToken(token)) return true
          const ownerId = currentUserId()
          if (requestedOwnerId == null) return ownerId != null
          return ownerId != null && String(ownerId) === String(requestedOwnerId)
        }

        const recordHistory = (): void => {
          if (!isHistoryIdentityCurrent()) return
          const ownerId = currentUserId()
          if (!historyScope) {
            historyScope = captureRecentVisitsScope({ token, ownerId })
          }
          if (!historyScope) return
          addVisit({
            id,
            path,
            name: String(route.name || ''),
            title: titleText,
            sectionId: data.sectionId,
            sectionName: data.sectionName,
            scope: historyScope,
          })
        }

        recordHistory()
        if (hasRealToken(token) && currentUserId() == null) {
          loadedHistoryCallback = recordHistory
        }
      } catch (_) {}
    } catch (cause) {
      if (isCurrent()) {
        error.value = isAxiosError(cause) && cause.response?.status === 404
          ? '未找到帖子'
          : '加载帖子详情失败'
      }
    } finally {
      if (isCurrent()) loading.value = false
      else clearInvalidatedLoading(sequence, id, path)
    }
  }

  function updateThread(patch: Partial<Thread>): void {
    if (disposed || !thread.value) {
      return
    }
    thread.value = {
      ...thread.value,
      ...patch,
    }
  }

  return {
    anchorPostId,
    displayAuthorAvatarUrl,
    error,
    load,
    loading,
    thread,
    updateThread,
    updateAnchorFromHash,
  }
}

import { computed, onScopeDispose, ref } from 'vue'
import { isAxiosError } from 'axios'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { getThread } from '@/api/threads'
import { getUserProfile } from '@/api/users'
import { updateVisitSectionById, updateVisitTitleById, updateVisitTitleByPath } from '@/composables/useRecentVisits'
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
  const loading = ref(false)
  const error = ref('')
  const thread = ref<Thread | null>(null)
  const authorProfileAvatarUrl = ref('')
  const anchorPostId = ref<number | null>(null)

  function accountContext(): string {
    return JSON.stringify([auth.token, auth.user?.id ?? auth.user?.userId ?? null])
  }

  onScopeDispose(() => {
    disposed = true
    requestSequence += 1
  })

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
    const account = accountContext()
    const isCurrent = (): boolean => !disposed && sequence === requestSequence &&
      getRouteParamId(route.params.id as string | string[] | undefined) === id &&
      route.path === path && accountContext() === account
    loading.value = true
    error.value = ''
    thread.value = null
    authorProfileAvatarUrl.value = ''
    try {
      const data = await getThread(id)
      if (!isCurrent()) return
      thread.value = data

      if (!data.authorAvatarUrl && data.authorId) {
        try {
          const profile = await getUserProfile(data.authorId)
          if (isCurrent()) authorProfileAvatarUrl.value = profile?.avatarUrl || ''
        } catch (_) {}
      }
      // Route remounts do not cancel promises: guard persistent history and
      // document metadata as well as this component's local refs.
      if (!isCurrent()) return

      try {
        updateVisitSectionById(Number(id), data.sectionId, data.sectionName)
      } catch (_) {}

      try {
        const titleText = String(data.title || '').trim()
        if (titleText) {
          if (route.name === 'thread-detail' && getRouteParamId(route.params.id as string | string[] | undefined) === id) {
            document.title = formatPageTitle(titleText)
          }
          updateVisitTitleByPath(path, titleText)
          try {
            updateVisitTitleById(Number(id), titleText)
          } catch (_) {}
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

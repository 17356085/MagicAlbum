import { computed, ref } from 'vue'
import { isAxiosError } from 'axios'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { getThread } from '@/api/threads'
import { getUserProfile } from '@/api/users'
import { updateVisitSectionById, updateVisitTitleById, updateVisitTitleByPath } from '@/composables/useRecentVisits'
import { normalizeImageUrl } from '@/utils/image'
import type { Thread } from '@/types'
import { formatPageTitle } from '@/config/brand'

function getRouteParamId(value: string | string[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

export function useThreadDetailPage(route: RouteLocationNormalizedLoaded) {
  const loading = ref(false)
  const error = ref('')
  const thread = ref<Thread | null>(null)
  const authorProfileAvatarUrl = ref('')
  const anchorPostId = ref<number | null>(null)

  const displayAuthorAvatarUrl = computed(() =>
    normalizeImageUrl(thread.value?.authorAvatarUrl || authorProfileAvatarUrl.value || ''),
  )

  function updateAnchorFromHash(): void {
    const hash = String(route.hash || '')
    const match = hash.match(/^#post-(\d+)$/)
    anchorPostId.value = match ? Number(match[1]) : null
  }

  async function load(): Promise<void> {
    loading.value = true
    error.value = ''
    thread.value = null
    try {
      const id = getRouteParamId(route.params.id as string | string[] | undefined)
      const data = await getThread(id)
      thread.value = data
      authorProfileAvatarUrl.value = ''

      if (!thread.value?.authorAvatarUrl && thread.value?.authorId) {
        try {
          const profile = await getUserProfile(thread.value.authorId)
          authorProfileAvatarUrl.value = profile?.avatarUrl || ''
        } catch (_) {}
      }

      try {
        updateVisitSectionById(Number(route.params.id), thread.value?.sectionId, thread.value?.sectionName)
      } catch (_) {}

      try {
        const titleText = String(thread.value?.title || '').trim()
        if (titleText) {
          if (route.name === 'thread-detail' && getRouteParamId(route.params.id as string | string[] | undefined) === id) {
            document.title = formatPageTitle(titleText)
          }
          const path = route.fullPath || route.path
          updateVisitTitleByPath(path, titleText)
          try {
            updateVisitTitleById(Number(id), titleText)
          } catch (_) {}
        }
      } catch (_) {}
    } catch (cause) {
      error.value = isAxiosError(cause) && cause.response?.status === 404
        ? '未找到帖子'
        : '加载帖子详情失败'
    } finally {
      loading.value = false
    }
  }

  function updateThread(patch: Partial<Thread>): void {
    if (!thread.value) {
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

<template>
  <div class="sidebar-section">
    <div class="flex items-center justify-between px-4 py-3">
      <span class="text-xs font-semibold ui-muted">最近浏览</span>
      <div class="flex gap-2">
        <button class="text-[10px] text-gray-400 hover:text-brandDay-600 dark:hover:text-brandNight-400" @click="refreshRecent">刷新</button>
        <button class="text-[10px] text-gray-400 hover:text-red-500" :disabled="!hasValidDisplayedScope" @click="clearRecent">清除</button>
      </div>
    </div>

    <div class="px-2 pb-2">
      <div v-if="scopeError" class="py-4 text-center text-xs text-amber-600" role="alert">账号状态已变化，请点击“刷新”重试。</div>
      <template v-else>
        <div v-if="recentVisits.length === 0" class="py-4 text-center text-xs text-gray-400">暂无浏览记录</div>
        <ul v-else class="space-y-0.5">
          <li v-for="visit in recentVisits" :key="visit.path">
            <router-link :to="visit.path" class="group flex items-center justify-between rounded-md px-2 py-1.5 text-xs text-gray-600 transition-colors hover:bg-gray-50 hover:text-brandDay-700 dark:text-gray-400 dark:hover:bg-gray-700/50 dark:hover:text-brandNight-300">
              <span class="truncate pr-2">{{ visit.title || visit.name || visit.path }}</span>
              <span class="shrink-0 text-[10px] text-gray-400 opacity-0 transition-opacity group-hover:opacity-100">{{ formatRelativeShort(visit.ts) }}</span>
            </router-link>
          </li>
        </ul>
      </template>
      <div class="mt-2 border-t border-gray-50 pt-2 text-center dark:border-gray-700/50">
        <router-link to="/history" class="text-xs font-medium text-brandDay-700 hover:text-brandDay-800 dark:text-brandNight-400 dark:hover:text-brandNight-300">查看全部浏览</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  captureRecentVisitsScope,
  clearAllRecentVisits,
  getRecentVisits,
  isRecentVisitsScopeCurrent,
} from '@/composables/useRecentVisits'
import { useAuthStore } from '@/stores/auth'
import type { RecentVisit } from '@/types'
import type { RecentVisitsIdentity, RecentVisitsScope } from '@/composables/useRecentVisits'

const auth = useAuthStore()
const recentVisits = ref<RecentVisit[]>([])
const displayedScope = ref<RecentVisitsScope | null>(null)
const scopeError = ref(false)
const hasValidDisplayedScope = computed(() => {
  const scope = displayedScope.value
  return !!scope && !scopeError.value && isRecentVisitsScopeCurrent(scope, currentIdentity())
})

function currentIdentity(): RecentVisitsIdentity {
  return {
    token: String(auth.token || ''),
    ownerId: auth.user?.id ?? auth.user?.userId ?? null,
  }
}

function clearScopeView(showError: boolean): void {
  recentVisits.value = []
  displayedScope.value = null
  scopeError.value = showError
}

function refreshRecent(): void {
  const expected = currentIdentity()
  const scope = captureRecentVisitsScope(expected)
  if (!scope) {
    clearScopeView(true)
    return
  }

  const nextVisits = getRecentVisits(5, scope)
  if (!isRecentVisitsScopeCurrent(scope, currentIdentity())) {
    clearScopeView(true)
    return
  }

  displayedScope.value = scope
  recentVisits.value = nextVisits
  scopeError.value = false
}

function onRecentUpdated(): void {
  refreshRecent()
}

function clearRecent(): void {
  const scope = displayedScope.value
  if (!scope || !isRecentVisitsScopeCurrent(scope, currentIdentity())) {
    clearScopeView(true)
    return
  }
  clearAllRecentVisits(scope)
  refreshRecent()
}

function onAuthStateChanged(): void {
  refreshRecent()
}

function formatRelativeShort(ts: number | string | null | undefined): string {
  const diff = Date.now() - Number(ts || 0)
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h`
  return `${Math.floor(hours / 24)}d`
}

refreshRecent()
watch([() => auth.token, () => auth.user?.id ?? auth.user?.userId], refreshRecent)

onMounted(() => {
  window.addEventListener('recent-visits-updated', onRecentUpdated)
  window.addEventListener('auth-state-changed', onAuthStateChanged)
})
onBeforeUnmount(() => {
  window.removeEventListener('recent-visits-updated', onRecentUpdated)
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

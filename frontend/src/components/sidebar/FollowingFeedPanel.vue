<template>
  <div class="sidebar-section">
    <div class="flex items-center justify-between px-4 py-3">
      <span class="text-xs font-semibold ui-muted">动态</span>
      <button class="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300" @click="toggleFollowCollapsed">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-4 w-4 transition-transform duration-200" :class="{ 'rotate-180': !followCollapsed }">
          <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 10.94l3.71-3.71a.75.75 0 011.08 1.04l-4.25 4.25a.75.75 0 01-1.08 0L5.21 8.27a.75.75 0 01.02-1.06z" clip-rule="evenodd" />
        </svg>
      </button>
    </div>

    <div v-show="!followCollapsed" class="px-2 pb-2">
      <div v-if="followLoading" class="py-4 text-center text-xs text-gray-400">加载中...</div>
      <div v-else-if="followError" class="flex flex-wrap items-center justify-center gap-2 py-4 text-xs text-[var(--ui-danger)]" role="alert">
        <span>{{ followError }}</span>
        <button type="button" class="ui-button min-h-11" @click="resetAndReload">重试</button>
      </div>
      <div v-else-if="feedItems.length === 0" class="py-4 text-center text-xs text-gray-400">暂无动态</div>
      <ul v-else class="space-y-1">
        <li v-for="item in feedItems" :key="item.id" class="group rounded-lg transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50">
          <router-link :to="`/threads/${item.id}`" class="flex items-start gap-3 p-2">
            <img
              :src="avatarOf(item)"
              alt="avatar"
              class="mt-0.5 h-8 w-8 rounded-full object-cover ring-2 ring-transparent transition-all group-hover:ring-brandDay-100 dark:group-hover:ring-brandNight-900"
            />
            <div class="min-w-0 flex-1">
              <div class="flex items-center justify-between">
                <div class="truncate text-sm font-medium text-gray-700 dark:text-gray-200">{{ displayNameOf(item) }}</div>
                <span class="shrink-0 text-[10px] text-gray-400">{{ formatRelativeTime(item.createdAt || item.updatedAt) }}</span>
              </div>
              <div class="mt-0.5 line-clamp-2 text-xs font-medium leading-5 text-gray-600 group-hover:text-brandDay-700 dark:text-gray-300 dark:group-hover:text-brandNight-300">{{ item.title }}</div>
            </div>
          </router-link>
        </li>
      </ul>
      <div v-if="!followLoading && !followError" class="mt-2 flex justify-end border-t border-gray-50 pt-2 dark:border-gray-700/50">
        <router-link to="/feed" class="text-xs font-medium text-brandDay-700 hover:text-brandDay-800 dark:text-brandNight-400 dark:hover:text-brandNight-300">查看更多</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { listFollowingFeed, type FollowingFeedItem } from '@/api/followingFeed'
import { getStoredAccessToken } from '@/utils/authStorage'
import { formatRelativeTime } from '@/composables/time'
import { normalizeImageUrl } from '@/utils/image'
import type { Id } from '@/types'

const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const followCollapsed = ref(false)
const feedItems = ref<FollowingFeedItem[]>([])
const followLoading = ref(false)
const followError = ref('')
let followRequestSeq = 0
let followSessionEpoch = 0
let reloadQueued = false
let disposed = false

function toggleFollowCollapsed(): void {
  followCollapsed.value = !followCollapsed.value
  try { localStorage.setItem('sidebar_follow_collapsed', String(followCollapsed.value)) } catch (_) {}
}

const currentUserId = computed(() => user.value?.id ?? user.value?.userId ?? null)

interface FeedRequestContext {
  sequence: number
  epoch: number
  token: string
  accountId: Id | null
}

function createRequestContext(): FeedRequestContext {
  return {
    sequence: ++followRequestSeq,
    epoch: followSessionEpoch,
    token: authStore.token,
    accountId: currentUserId.value,
  }
}

function isCurrentRequest(request: FeedRequestContext): boolean {
  return !disposed
    && request.sequence === followRequestSeq
    && request.epoch === followSessionEpoch
    && request.token === authStore.token
    && getStoredAccessToken() === request.token
    && isLoggedIn.value
}

async function resolveCurrentUserId(request: FeedRequestContext): Promise<Id | null> {
  if (!isCurrentRequest(request)) return null
  const knownId = currentUserId.value
  if (knownId !== null && knownId !== undefined) {
    request.accountId = knownId
    return knownId
  }
  try {
    const refreshed = await authStore.refreshCurrentUser()
    if (!isCurrentRequest(request)) return null
    if (!refreshed) return null
    const hydratedId = currentUserId.value
    if (hydratedId === null || hydratedId === undefined) return null
    request.accountId = hydratedId
    return hydratedId
  } catch (_) {}
  return null
}

function displayNameOf(item: FollowingFeedItem): string {
  return item.authorNickname || item.authorUsername || '用户'
}

function avatarOf(item: FollowingFeedItem): string {
  const name = displayNameOf(item)
  return normalizeImageUrl(item.authorAvatarUrl || `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(name || 'U')}`)
}

async function loadFeedItems(): Promise<void> {
  const request = createRequestContext()
  if (disposed) return
  if (!isLoggedIn.value) {
    feedItems.value = []
    followError.value = ''
    followLoading.value = false
    return
  }
  feedItems.value = []
  followLoading.value = true
  followError.value = ''
  try {
    const userId = await resolveCurrentUserId(request)
    if (!isCurrentRequest(request)) return
    if (userId === null || userId === undefined) {
      feedItems.value = []
      followError.value = '账号信息加载失败，请重试'
      return
    }
    if (!isCurrentRequest(request)) return
    const page = await listFollowingFeed({ page: 1, size: 4 })
    if (isCurrentRequest(request)) feedItems.value = page.items
  } catch (_) {
    if (isCurrentRequest(request)) {
      feedItems.value = []
      followError.value = '动态加载失败'
    }
  } finally {
    if (isCurrentRequest(request)) followLoading.value = false
  }
}

function queueReload(): void {
  if (disposed || reloadQueued) return
  reloadQueued = true
  queueMicrotask(() => {
    reloadQueued = false
    if (disposed) return
    void loadFeedItems()
  })
}

function resetAndReload(): void {
  if (disposed) return
  followSessionEpoch += 1
  followRequestSeq += 1
  feedItems.value = []
  followError.value = ''
  followLoading.value = false
  queueReload()
}

function onAuthStateChanged(): void {
  resetAndReload()
}

const stopAuthWatch = watch(
  [() => authStore.token, () => currentUserId.value, () => isLoggedIn.value],
  () => resetAndReload(),
  { flush: 'sync' },
)

onMounted(() => {
  try {
    const raw = localStorage.getItem('sidebar_follow_collapsed')
    if (raw === 'true') followCollapsed.value = true
  } catch (_) {}
  void loadFeedItems()
  window.addEventListener('follow-state-updated', resetAndReload)
  window.addEventListener('auth-state-changed', onAuthStateChanged)
})

onBeforeUnmount(() => {
  disposed = true
  followSessionEpoch += 1
  followRequestSeq += 1
  reloadQueued = false
  stopAuthWatch()
  window.removeEventListener('follow-state-updated', resetAndReload)
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

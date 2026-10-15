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
      <div v-else-if="followError" class="py-4 text-center text-xs text-red-500">{{ followError }}</div>
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
import { getCurrentUser } from '@/api/users'
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
let disposed = false

function toggleFollowCollapsed(): void {
  followCollapsed.value = !followCollapsed.value
  try { localStorage.setItem('sidebar_follow_collapsed', String(followCollapsed.value)) } catch (_) {}
}

const currentUserId = computed(() => user.value?.id || user.value?.userId || null)

interface FeedRequestContext {
  sequence: number
  identity: string
}

function currentAccountIdentity(): string {
  if (!isLoggedIn.value) return 'logged-out'
  const current = user.value
  const id = current?.id ?? current?.userId
  if (id !== undefined && id !== null) return `id:${String(id)}`
  return `username:${String(current?.username || '').trim().toLowerCase()}`
}

function isCurrentRequest(request: FeedRequestContext): boolean {
  return !disposed && request.sequence === followRequestSeq && isLoggedIn.value && currentAccountIdentity() === request.identity
}

async function resolveCurrentUserId(request: FeedRequestContext): Promise<Id | null> {
  if (!isCurrentRequest(request)) return null
  if (!isLoggedIn.value) return null
  if (currentUserId.value) return currentUserId.value
  try {
    const current = await getCurrentUser()
    if (!isCurrentRequest(request)) return null
    const id = current?.id || current?.userId || null
    if (current && id) {
      authStore.updateCurrentUser(current)
      request.identity = currentAccountIdentity()
      return id
    }
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
  const request: FeedRequestContext = {
    sequence: ++followRequestSeq,
    identity: currentAccountIdentity(),
  }
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
    if (!userId) {
      feedItems.value = []
      return
    }
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

function resetAndReload(): void {
  if (disposed) return
  followRequestSeq += 1
  feedItems.value = []
  followError.value = ''
  followLoading.value = false
  void loadFeedItems()
}

onMounted(() => {
  try {
    const raw = localStorage.getItem('sidebar_follow_collapsed')
    if (raw === 'true') followCollapsed.value = true
  } catch (_) {}
  void loadFeedItems()
  window.addEventListener('follow-state-updated', loadFeedItems)
  window.addEventListener('auth-state-changed', resetAndReload)
})

watch([currentUserId, isLoggedIn], () => resetAndReload())

onBeforeUnmount(() => {
  disposed = true
  followRequestSeq += 1
  window.removeEventListener('follow-state-updated', loadFeedItems)
  window.removeEventListener('auth-state-changed', resetAndReload)
})
</script>

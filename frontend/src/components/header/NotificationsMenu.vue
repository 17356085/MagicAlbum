<template>
  <div ref="notificationContainer" class="relative shrink-0">
    <button
      ref="notificationButton"
      type="button"
      class="relative inline-flex h-12 w-12 shrink-0 items-center justify-center rounded hover:bg-gray-100 dark:hover:bg-gray-700 lg:h-9 lg:w-auto lg:px-3 lg:py-1"
      aria-label="通知"
      title="通知"
      aria-controls="header-notifications-panel"
      :aria-expanded="notificationOpen"
      @click="toggleNotifications"
    >
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
        <path d="M12 3a6 6 0 00-6 6v3.5l-1.5 2.5h15L18 12.5V9a6 6 0 00-6-6z" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M10 19a2 2 0 004 0" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span v-if="unreadNotificationCount" class="absolute right-1 top-0 min-w-4 rounded-full bg-red-500 px-1 text-[10px] leading-4 text-white">{{ unreadNotificationCount > 99 ? '99+' : unreadNotificationCount }}</span>
      <span class="sr-only">通知</span>
    </button>
    <div
      v-if="notificationOpen"
      id="header-notifications-panel"
      role="dialog"
      aria-label="通知"
      class="mobile-notifications-panel fixed inset-x-2 top-14 z-50 mt-2 max-h-[calc(100vh-5rem)] w-auto max-w-none overflow-hidden rounded-md border border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-800 lg:absolute lg:inset-x-auto lg:right-0 lg:top-full lg:mt-2 lg:w-80 lg:max-w-[calc(100vw-1rem)]"
    >
      <div class="flex items-center justify-between px-3 py-2">
        <span class="text-sm font-semibold text-gray-900 dark:text-gray-50">通知</span>
        <router-link class="inline-flex min-h-11 items-center px-2 text-xs text-brandDay-600 hover:underline dark:text-brandNight-300 lg:min-h-0" :to="{ name: 'settings', query: { tab: 'notifications' } }" @click="notificationOpen = false">查看全部</router-link>
      </div>
      <div v-if="notificationLoading" class="px-3 py-4 text-xs text-gray-500">正在加载...</div>
      <div v-else-if="notificationError" class="px-3 py-4 text-xs text-red-600">{{ notificationError }}</div>
      <ul v-else class="ui-scroll max-h-[calc(100vh-10rem)] overflow-auto lg:max-h-96">
        <li
          v-for="item in headerNotifications"
          :key="item.id"
          class="border-t border-gray-100 px-3 py-2 text-xs dark:border-gray-700"
          :class="notificationCanNavigate(item) ? 'cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/60' : ''"
          @click="openNotification(item)"
        >
          <div class="flex items-start gap-2">
            <span class="mt-1 h-2 w-2 shrink-0 rounded-full" :class="item.read ? 'bg-gray-300 dark:bg-gray-600' : 'bg-brandDay-600 dark:bg-brandNight-400'"></span>
            <div class="min-w-0 flex-1">
              <div class="truncate font-medium text-gray-900 dark:text-gray-50">{{ item.title || notificationTypeLabel(item.type) }}</div>
              <div class="mt-1 max-h-8 overflow-hidden text-gray-600 dark:text-gray-300">{{ item.content }}</div>
              <div class="mt-1 text-[11px] text-gray-400">{{ item.createdAt ? formatRelativeTime(item.createdAt) : '' }}</div>
            </div>
            <div class="flex shrink-0 flex-col gap-1">
              <button v-if="notificationCanNavigate(item)" class="inline-flex min-h-11 items-center justify-center rounded border border-brandDay-200 px-3 py-1 text-[11px] text-brandDay-700 hover:bg-brandDay-50 dark:border-brandNight-700 dark:text-brandNight-200 dark:hover:bg-brandNight-900/30 lg:min-h-0" @click.stop="openNotification(item)">查看</button>
              <button v-if="!item.read" class="inline-flex min-h-11 items-center justify-center rounded border border-gray-200 px-3 py-1 text-[11px] hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700 lg:min-h-0" @click.stop="setHeaderNotificationRead(item.id)">已读</button>
            </div>
          </div>
        </li>
        <li v-if="!headerNotifications.length" class="border-t border-gray-100 px-3 py-5 text-center text-xs text-gray-500 dark:border-gray-700">暂无通知</li>
      </ul>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { formatRelativeTime } from '@/composables/time'
import type { Id, NotificationItem } from '@/types'
import { subscribeNotificationChanges } from '@/composables/notificationSignals'
import { getStoredAccessToken } from '@/utils/authStorage'

const props = defineProps<{
  isLoggedIn: boolean
}>()

const emit = defineEmits<{
  'open-login': []
}>()

const route = useRoute()
const router = useRouter()
const notificationOpen = ref(false)
const notificationContainer = ref<HTMLElement | null>(null)
const notificationButton = ref<HTMLButtonElement | null>(null)
const notificationLoading = ref(false)
const notificationError = ref('')
const headerNotifications = ref<NotificationItem[]>([])
const unreadNotificationCount = ref(0)
let requestSeq = 0
let badgeRequestSeq = 0
let readRequestSeq = 0
const stopLiveUpdates = subscribeNotificationChanges(signal => {
  if (signal === 'session') {
    requestSeq += 1; badgeRequestSeq += 1; readRequestSeq += 1
    headerNotifications.value = []; unreadNotificationCount.value = 0
    notificationLoading.value = false; notificationError.value = ''
  }
  if (!props.isLoggedIn) return
  if (notificationOpen.value) void loadHeaderNotifications()
  else void refreshNotificationBadge().catch(() => {})
})

function notificationTypeLabel(type: string | undefined): string {
  if (type === 'reply') return '回复通知'
  if (type === 'mention') return '提及通知'
  if (type === 'like') return '点赞通知'
  if (type === 'follow') return '关注通知'
  if (type === 'system') return '系统通知'
  return '通知'
}

async function refreshNotificationBadge(): Promise<void> {
  const currentRequest = ++badgeRequestSeq
  const ownerToken = getStoredAccessToken()
  if (!props.isLoggedIn) {
    unreadNotificationCount.value = 0
    headerNotifications.value = []
    return
  }
  const data = await listNotifications({ unread: true, page: 1, size: 1 })
  if (currentRequest !== badgeRequestSeq || getStoredAccessToken() !== ownerToken) return
  unreadNotificationCount.value = data.total
}

async function loadHeaderNotifications(): Promise<void> {
  const currentRequest = ++requestSeq
  const ownerToken = getStoredAccessToken()
  if (!props.isLoggedIn) {
    headerNotifications.value = []
    unreadNotificationCount.value = 0
    return
  }
  notificationLoading.value = true
  notificationError.value = ''
  try {
    const [recent, unread] = await Promise.all([
      listNotifications({ page: 1, size: 8 }),
      listNotifications({ unread: true, page: 1, size: 1 }),
    ])
    if (currentRequest !== requestSeq || getStoredAccessToken() !== ownerToken) return
    headerNotifications.value = recent.items
    unreadNotificationCount.value = unread.total
  } catch (_) {
    if (currentRequest === requestSeq && getStoredAccessToken() === ownerToken) notificationError.value = '通知加载失败'
  } finally {
    if (currentRequest === requestSeq && getStoredAccessToken() === ownerToken) notificationLoading.value = false
  }
}

function closeNotifications(restoreFocus = false): void {
  if (!notificationOpen.value) return
  notificationOpen.value = false
  if (restoreFocus) {
    void nextTick(() => notificationButton.value?.focus())
  }
}

function toggleNotifications(): void {
  if (!props.isLoggedIn) {
    emit('open-login')
    return
  }
  if (notificationOpen.value) {
    closeNotifications()
    return
  }
  notificationOpen.value = true
  void loadHeaderNotifications()
}

async function setHeaderNotificationRead(id: Id): Promise<void> {
  const currentRequest = ++readRequestSeq
  const ownerToken = getStoredAccessToken()
  try {
    const updated = await markNotificationRead(id)
    if (currentRequest !== readRequestSeq || getStoredAccessToken() !== ownerToken) return
    headerNotifications.value = headerNotifications.value.map(item => (
      item.id === id ? { ...item, read: true, ...(updated as Partial<NotificationItem>) } : item
    ))
    await refreshNotificationBadge()
  } catch (_) {
    if (currentRequest === readRequestSeq && getStoredAccessToken() === ownerToken) notificationError.value = '标记已读失败'
  }
}

function notificationCanNavigate(item: NotificationItem): boolean {
  return Boolean(notificationLink(item))
}

async function openNotification(item: NotificationItem): Promise<void> {
  const ownerToken = getStoredAccessToken()
  const link = notificationLink(item)
  if (!link) return
  closeNotifications()
  if (!item.read) {
    const currentRequest = ++readRequestSeq
    try {
      await markNotificationRead(item.id)
      if (currentRequest !== readRequestSeq || getStoredAccessToken() !== ownerToken) return
      headerNotifications.value = headerNotifications.value.map((next) => (
        next.id === item.id ? { ...next, read: true } : next
      ))
      void refreshNotificationBadge().catch(() => {})
    } catch (_) {}
  }
  if (getStoredAccessToken() === ownerToken) await router.push(link)
}

function notificationLink(item: NotificationItem): string {
  const directLink = String(item?.link || '').trim()
  if (directLink) return directLink
  const threadId = item?.threadId || (item?.targetType === 'thread' ? item?.targetId : null)
  if (!threadId) return ''
  const targetId = item?.targetType === 'post' ? item?.targetId : null
  return `/threads/${threadId}${targetId ? `#post-${targetId}` : ''}`
}

function onDocumentClick(event: MouseEvent): void {
  const target = event.target
  if (!notificationOpen.value || !(target instanceof Node)) return
  if (!notificationContainer.value?.contains(target)) closeNotifications()
}

function onGlobalKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || !notificationOpen.value) return
  event.preventDefault()
  closeNotifications(true)
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onGlobalKeydown)
})

onBeforeUnmount(() => {
  stopLiveUpdates()
  requestSeq += 1
  badgeRequestSeq += 1
  readRequestSeq += 1
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onGlobalKeydown)
})

watch(() => props.isLoggedIn, (loggedIn) => {
  requestSeq += 1
  badgeRequestSeq += 1
  readRequestSeq += 1
  if (!loggedIn) {
    closeNotifications()
    headerNotifications.value = []
    unreadNotificationCount.value = 0
    notificationLoading.value = false
    notificationError.value = ''
    return
  }
  void refreshNotificationBadge().catch(() => {})
}, { immediate: true })

watch(() => route.fullPath, () => closeNotifications())
</script>

<style scoped>
.mobile-notifications-panel {
  top: calc(56px + env(safe-area-inset-top, 0px));
}

@media (min-width: 1024px) {
  .mobile-notifications-panel {
    top: 100%;
  }
}
</style>

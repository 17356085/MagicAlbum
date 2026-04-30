<template>
  <header class="fixed top-0 left-0 right-0 z-50 border-b border-gray-200 bg-white/90 backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 text-gray-800 dark:text-gray-200 motion-safe:transition-colors motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none">
    <div class="mx-auto max-w-7xl px-4 py-3 flex items-center justify-between">
      <BrandBar />
      <SearchBox
        :search-type="searchType"
        :search-query="searchQuery"
        :suggest-open="suggestOpen"
        :suggest-loading="suggestLoading"
        :suggest-error="suggestError"
        :visible-suggestions="visibleSuggestions"
        :suggest-profiles="suggestProfiles"
        :active-index="activeIndex"
        @update:search-type="searchType = $event"
        @update:search-query="searchQuery = $event"
        @update:active-index="activeIndex = $event"
        @search="doSearch"
        @input-keydown="onInputKeydown"
        @input-focus="onInputFocus"
        @input-blur="onInputBlur"
        @close-suggest="suggestOpen = false"
      />
      <nav class="flex items-center gap-2 text-sm">
        <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="toggleTheme" :title="themeLabel">
          <span v-if="isDark">🌙</span><span v-else>☀️</span>
        </button>
        <!-- 发帖：未登录时直接拉起登录弹窗，已登录时进入发帖页 -->
        <router-link to="/threads/new" class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center" aria-label="发帖" title="发帖" @click="onCreateThreadClick">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
            <circle cx="12" cy="12" r="9" stroke-width="1.8" />
            <path d="M12 8.5v7M8.5 12h7" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span class="sr-only">发帖</span>
        </router-link>
        <div class="relative">
          <button class="relative inline-flex items-center rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" aria-label="通知" title="通知" @click="toggleNotifications">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
              <path d="M12 3a6 6 0 00-6 6v3.5l-1.5 2.5h15L18 12.5V9a6 6 0 00-6-6z" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M10 19a2 2 0 004 0" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <span v-if="unreadNotificationCount" class="absolute right-1 top-0 min-w-4 rounded-full bg-red-500 px-1 text-[10px] leading-4 text-white">{{ unreadNotificationCount > 99 ? '99+' : unreadNotificationCount }}</span>
            <span class="sr-only">通知</span>
          </button>
          <div v-if="notificationOpen" class="absolute right-0 top-full z-50 mt-2 w-80 overflow-hidden rounded-md border border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-800">
            <div class="flex items-center justify-between px-3 py-2">
              <span class="text-sm font-semibold text-gray-900 dark:text-gray-50">通知</span>
              <router-link class="text-xs text-brandDay-600 hover:underline dark:text-brandNight-300" :to="{ name: 'settings', query: { tab: 'notifications' } }" @click="notificationOpen = false">查看全部</router-link>
            </div>
            <div v-if="notificationLoading" class="px-3 py-4 text-xs text-gray-500">正在加载...</div>
            <div v-else-if="notificationError" class="px-3 py-4 text-xs text-red-600">{{ notificationError }}</div>
            <ul v-else class="max-h-96 overflow-auto">
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
                    <button v-if="notificationCanNavigate(item)" class="rounded border border-brandDay-200 px-2 py-1 text-[11px] text-brandDay-700 hover:bg-brandDay-50 dark:border-brandNight-700 dark:text-brandNight-200 dark:hover:bg-brandNight-900/30" @click.stop="openNotification(item)">查看</button>
                    <button v-if="!item.read" class="rounded border border-gray-200 px-2 py-1 text-[11px] hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700" @click.stop="setHeaderNotificationRead(item.id)">已读</button>
                  </div>
                </div>
              </li>
              <li v-if="!headerNotifications.length" class="border-t border-gray-100 px-3 py-5 text-center text-xs text-gray-500 dark:border-gray-700">暂无通知</li>
            </ul>
          </div>
        </div>
        <AuthEntry v-if="!isLoggedIn" @open-login="showLogin = true" />
        <template v-else>
          <UserMenu :user="user" :avatar-url="avatarUrl" :display-name="displayName" @logout="onLogoutClick" />
        </template>
      </nav>
    </div>
  </header>
  <AuthModal v-if="showLogin" @close="showLogin = false" @success="onLoginSuccess" />

  <!-- 登出确认弹窗 -->
  <div v-if="showLogoutConfirm" class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/30" @click="showLogoutConfirm = false"></div>
    <div class="relative z-10 w-full max-w-sm rounded-lg border border-gray-200 bg-white shadow-xl dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h3 class="text-base font-semibold">确认登出</h3>
        <button class="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showLogoutConfirm = false" aria-label="关闭">✕</button>
      </div>
      <div class="px-4 py-4 text-sm text-gray-700 dark:text-gray-200">确定要退出登录吗？</div>
      <div class="px-4 pb-4 flex items-center justify-end gap-2">
        <button class="rounded px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="showLogoutConfirm = false">取消</button>
          <button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="confirmLogout">确认</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import AuthModal from '@/components/auth/AuthModal.vue'
import AuthEntry from '@/components/header/AuthEntry.vue'
import BrandBar from '@/components/header/BrandBar.vue'
import SearchBox from '@/components/header/SearchBox.vue'
import UserMenu from '@/components/header/UserMenu.vue'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyProfile } from '@/api/settings'
import { suggestUsers, getUserProfile } from '@/api/users'
import { formatRelativeTime } from '@/composables/time'
import { clearPendingAuthRedirect, getPendingAuthRedirect, setPendingAuthRedirect } from '@/utils/authStorage'
import type { Id, NotificationItem, PageResult, ProfileUpdatedDetail, User, UserProfile } from '@/types'

const showLogin = ref(false)
const showLogoutConfirm = ref(false)
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const { logout, updateCurrentUser } = authStore
const router = useRouter()
const route = useRoute()
type SearchType = 'threads' | 'users'
type SuggestProfile = Pick<UserProfile, 'avatarUrl' | 'nickname'>

const searchType = ref<SearchType>('threads')
const searchQuery = ref('')
// 顶栏联想建议（仅用户搜索时启用）
const suggestOpen = ref(false)
const suggestions = ref<User[]>([])
const suggestLoading = ref(false)
const suggestError = ref('')
let suggestTimer: ReturnType<typeof setTimeout> | null = null
const suggestProfiles = ref<Record<string, SuggestProfile>>({})
const activeIndex = ref(-1)
const keyword = computed(() => String(searchQuery.value || '').trim().toLowerCase())

function includesI(str: string | number | null | undefined, kw: string): boolean {
  return String(str || '').toLowerCase().includes(String(kw || '').toLowerCase())
}

// 仅支持用户名或昵称匹配的可见建议列表
const visibleSuggestions = computed<User[]>(() => {
  const kw = keyword.value
  if (!kw) return []
  return (Array.isArray(suggestions.value) ? suggestions.value : []).filter(u => {
    if (includesI(u?.username, kw)) return true
    const p = suggestProfiles.value[String(u?.id)]
    if (p && includesI(p.nickname, kw)) return true
    return false
  })
})
const notificationOpen = ref(false)
const notificationLoading = ref(false)
const notificationError = ref('')
const headerNotifications = ref<NotificationItem[]>([])
const unreadNotificationCount = ref(0)

function getNotificationItems(data: PageResult<NotificationItem> | NotificationItem[]): NotificationItem[] {
  return Array.isArray(data) ? data : (Array.isArray(data?.items) ? data.items : [])
}

function getNotificationTotal(data: PageResult<NotificationItem> | NotificationItem[]): number {
  return Array.isArray(data) ? data.length : Number(data?.total || 0)
}

function notificationTypeLabel(type: string | undefined): string {
  if (type === 'reply') return '回复通知'
  if (type === 'mention') return '提及通知'
  if (type === 'like') return '点赞通知'
  if (type === 'follow') return '关注通知'
  if (type === 'system') return '系统通知'
  return '通知'
}

async function refreshNotificationBadge(): Promise<void> {
  if (!isLoggedIn.value) {
    unreadNotificationCount.value = 0
    headerNotifications.value = []
    return
  }
  try {
    const data = await listNotifications({ unread: true, page: 1, size: 1 })
    unreadNotificationCount.value = getNotificationTotal(data)
  } catch (_) {}
}

async function loadHeaderNotifications(): Promise<void> {
  if (!isLoggedIn.value) {
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
    headerNotifications.value = getNotificationItems(recent)
    unreadNotificationCount.value = getNotificationTotal(unread)
  } catch (_) {
    notificationError.value = '通知加载失败'
  } finally {
    notificationLoading.value = false
  }
}

function toggleNotifications(): void {
  if (!isLoggedIn.value) {
    showLogin.value = true
    return
  }
  notificationOpen.value = !notificationOpen.value
  if (notificationOpen.value) {
    loadHeaderNotifications().catch(() => {})
  }
}

async function setHeaderNotificationRead(id: Id): Promise<void> {
  try {
    const updated = await markNotificationRead(id)
    headerNotifications.value = headerNotifications.value.map(item => (
      item.id === id ? { ...item, read: true, ...(updated as NotificationItem) } : item
    ))
    await refreshNotificationBadge()
  } catch (_) {
    notificationError.value = '标记已读失败'
  }
}

function notificationCanNavigate(item: NotificationItem): boolean {
  return Boolean(notificationLink(item))
}

async function openNotification(item: NotificationItem): Promise<void> {
  const link = notificationLink(item)
  if (!link) {
    return
  }
  notificationOpen.value = false
  if (!item.read) {
    try {
      await markNotificationRead(item.id)
      headerNotifications.value = headerNotifications.value.map((next) => (
        next.id === item.id ? { ...next, read: true } : next
      ))
      refreshNotificationBadge().catch(() => {})
    } catch (_) {}
  }
  await router.push(link)
}

function notificationLink(item: NotificationItem): string {
  const directLink = String(item?.link || '').trim()
  if (directLink) {
    return directLink
  }
  const threadId = item?.threadId || (item?.targetType === 'thread' ? item?.targetId : null)
  if (!threadId) {
    return ''
  }
  const targetId = item?.targetType === 'post' ? item?.targetId : null
  return `/threads/${threadId}${targetId ? `#post-${targetId}` : ''}`
}
const avatarUrl = ref('')
const displayName = ref('')
const profileHydrated = ref(false)

function applyUserIdentity(): void {
  const currentUser = user.value
  avatarUrl.value = currentUser?.avatarUrl || ''
  displayName.value =
    (currentUser?.nickname && String(currentUser.nickname).trim()) ||
    currentUser?.username ||
    ''
}

function needsProfileHydration(): boolean {
  if (!isLoggedIn.value) {
    return false
  }
  if (!user.value?.id && !user.value?.userId) return true
  const currentAvatar = String(user.value.avatarUrl || '').trim()
  const currentNickname = String(user.value.nickname || '').trim()
  return !currentAvatar || !currentNickname
}

async function refreshMyProfile(): Promise<void> {
  if (!isLoggedIn.value) {
    avatarUrl.value = ''
    displayName.value = ''
    profileHydrated.value = false
    return
  }

  // 先用登录态里的用户信息兜底，避免界面短时间回退成默认头像。
  applyUserIdentity()

  if (!needsProfileHydration()) {
    profileHydrated.value = true
    return
  }

  try {
    const p = await getMyProfile()
    avatarUrl.value = p?.avatarUrl || avatarUrl.value || ''
    displayName.value =
      (p?.nickname && String(p.nickname).trim()) ||
      displayName.value ||
      (user.value?.username || '')
    if (p?.id || p?.userId || p?.username || p?.avatarUrl || p?.nickname) {
      updateCurrentUser({
        id: p?.id || p?.userId || user.value?.id,
        userId: p?.userId || p?.id || user.value?.userId,
        username: p?.username || user.value?.username || '',
        avatarUrl: p?.avatarUrl || user.value?.avatarUrl || '',
        nickname: p?.nickname || user.value?.nickname || '',
      })
    }
    profileHydrated.value = true
  } catch (_) {}
}

// 主题切换（light/dark），持久化到 localStorage，并同步到 html.dark
const isDark = ref(false)
const themeLabel = computed(() => (isDark.value ? '切换为白天模式' : '切换为黑夜模式'))

function applyThemeClass(dark: boolean): void {
  const root = document.documentElement
  if (dark) root.classList.add('dark')
  else root.classList.remove('dark')
}

function initTheme(): void {
  const saved = localStorage.getItem('theme')
  if (saved === 'dark') {
    isDark.value = true
  } else if (saved === 'light') {
    isDark.value = false
  } else {
    // system: 跟随系统
    const systemDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    isDark.value = !!systemDark
  }
  applyThemeClass(isDark.value)
}

function toggleTheme(): void {
  isDark.value = !isDark.value
  localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
  applyThemeClass(isDark.value)
}

// 事件处理定义在 setup 顶层，便于在卸载时正确移除
const onProfileUpdated = (evt: Event): void => {
  const detail = (evt as CustomEvent<ProfileUpdatedDetail>).detail || {}
  const nextAvatar = detail?.avatarUrl
  const nextNickname = detail?.nickname
  if (typeof nextAvatar === 'string') avatarUrl.value = nextAvatar
  if (typeof nextNickname === 'string') displayName.value = nextNickname || (user.value?.username || '')
  if (typeof nextAvatar === 'string' || typeof nextNickname === 'string') {
    updateCurrentUser({
      avatarUrl: typeof nextAvatar === 'string' ? nextAvatar : (user.value?.avatarUrl || ''),
      nickname: typeof nextNickname === 'string' ? nextNickname : (user.value?.nickname || ''),
    })
  }
}

const onOpenLoginModal = (): void => {
  showLogin.value = true
}

onMounted((): void => {
  initTheme()
  applyUserIdentity()
  if (needsProfileHydration()) {
    refreshMyProfile().catch(() => {})
  } else {
    profileHydrated.value = true
  }
  window.addEventListener('profile-updated', onProfileUpdated)
  window.addEventListener('open-login-modal', onOpenLoginModal)
  refreshNotificationBadge().catch(() => {})
  if (!isLoggedIn.value && route.query.login === '1') {
    showLogin.value = true
    try {
      router.replace({ name: 'discover', query: { ...route.query, login: undefined } })
    } catch (_) {}
  }
})

onUnmounted(() => {
  window.removeEventListener('profile-updated', onProfileUpdated)
  window.removeEventListener('open-login-modal', onOpenLoginModal)
  if (suggestTimer) {
    clearTimeout(suggestTimer)
    suggestTimer = null
  }
})

function onLoginSuccess(): void {
  applyUserIdentity()
  profileHydrated.value = false
  if (needsProfileHydration()) {
    refreshMyProfile().catch(() => {})
  }
  const redirect = getPendingAuthRedirect()
  if (redirect) {
    clearPendingAuthRedirect()
    try { router.push(redirect) } catch (_) {}
  }
}

watch(
  () => [isLoggedIn.value, user.value?.id] as const,
  ([loggedIn, userId], previousValue) => {
    const previousUserId = previousValue?.[1]
    if (userId !== previousUserId) {
      profileHydrated.value = false
    }
    if (!loggedIn) {
      avatarUrl.value = ''
      displayName.value = ''
      profileHydrated.value = false
      notificationOpen.value = false
      headerNotifications.value = []
      unreadNotificationCount.value = 0
      return
    }
    applyUserIdentity()
    if (!profileHydrated.value && needsProfileHydration()) {
      refreshMyProfile().catch(() => {})
      return
    }
    profileHydrated.value = true
    refreshNotificationBadge().catch(() => {})
  },
  { immediate: true }
)

watch(
  () => route.fullPath,
  () => {
    notificationOpen.value = false
  },
)

function onCreateThreadClick(event: MouseEvent): void {
  if (isLoggedIn.value) return
  event.preventDefault()
  setPendingAuthRedirect('/threads/new')
  showLogin.value = true
}

function onLogoutClick(): void {
  showLogoutConfirm.value = true
}

function confirmLogout(): void {
  // 在设置、我的帖子、我的评论页面登出时，先重定向到发现页
  const currentName = String(route.name || '')
  const needRedirect = currentName === 'settings' || currentName === 'my-threads' || currentName === 'my-posts'
  showLogoutConfirm.value = false
  if (needRedirect) {
    try {
      router.replace({ name: 'discover' })
    } catch (_) {}
    // 稍作延迟，确保路由跳转生效后再执行登出（会触发强制刷新）
    setTimeout(() => { try { logout() } catch (_) {} }, 30)
  } else {
    logout()
  }
  // 清理本地显示名与头像，避免残留
  avatarUrl.value = ''
  displayName.value = ''
  profileHydrated.value = false
}

function doSearch(): void {
  const q = String(searchQuery.value || '').trim()
  if (!q) {
    // 空关键字：跳到对应列表首页
    if (searchType.value === 'users') {
      router.push({ name: 'users', query: { page: 1 } })
    } else {
      router.push({ name: 'discover', query: { page: 1 } })
    }
    return
  }
  if (searchType.value === 'users') {
    router.push({ name: 'users', query: { q, page: 1 } })
  } else {
    router.push({ name: 'discover', query: { q, page: 1, sectionId: route.query.sectionId } })
  }
}

// 输入防抖与联想建议拉取
async function fetchSuggestions(keyword: string): Promise<void> {
  suggestLoading.value = true
  suggestError.value = ''
  try {
    const items = await suggestUsers(keyword, 5)
    const base = (Array.isArray(items) ? items : [])
    suggestions.value = base.slice(0, 5)
    // 取消默认选中联想建议：仅在用户用方向键选择后才有选中项
    activeIndex.value = -1
    // 异步预取头像/昵称，提升建议项信息密度
    const ids = suggestions.value.map((u) => u.id).filter(Boolean) as Id[]
    prefetchSuggestionProfiles(ids)
  } catch (_) {
    suggestError.value = '加载建议失败'
    suggestions.value = []
    activeIndex.value = -1
  } finally {
    suggestLoading.value = false
  }
}

function scheduleSuggest(): void {
  if (suggestTimer) clearTimeout(suggestTimer)
  const q = String(searchQuery.value || '').trim()
  if (searchType.value !== 'users' || !q) {
    suggestions.value = []
    activeIndex.value = -1
    return
  }
  suggestTimer = setTimeout(() => {
    suggestOpen.value = true
    fetchSuggestions(q)
  }, 250)
}

watch(searchQuery, () => scheduleSuggest())

function onInputFocus(): void {
  if (searchType.value === 'users' && String(searchQuery.value || '').trim()) {
    suggestOpen.value = true
  }
}

function onInputBlur(): void {
  // 延迟关闭，允许点击建议项
  setTimeout(() => { suggestOpen.value = false }, 150)
}

function onInputKeydown(e: KeyboardEvent): void {
  const key = e.key
  const hasSuggest = suggestOpen.value && Array.isArray(visibleSuggestions.value) && visibleSuggestions.value.length > 0
  if (key === 'Enter') {
    if (hasSuggest && activeIndex.value >= 0 && visibleSuggestions.value[activeIndex.value]) {
      const u = visibleSuggestions.value[activeIndex.value]
      suggestOpen.value = false
      e.preventDefault()
      router.push({ path: '/users/' + u.id })
      return
    }
    // 无联想或未选择：执行常规搜索
    doSearch()
  } else if (key === 'ArrowDown') {
    if (hasSuggest) {
      e.preventDefault()
      activeIndex.value = (activeIndex.value + 1) % visibleSuggestions.value.length
    }
  } else if (key === 'ArrowUp') {
    if (hasSuggest) {
      e.preventDefault()
      activeIndex.value = activeIndex.value <= 0 ? (visibleSuggestions.value.length - 1) : (activeIndex.value - 1)
    }
  } else if (key === 'Escape') {
    suggestOpen.value = false
  }
}

async function prefetchSuggestionProfiles(ids: Id[]): Promise<void> {
  for (const id of ids) {
    const key = String(id)
    if (suggestProfiles.value[key]) continue
    try {
      const p = await getUserProfile(id)
      suggestProfiles.value[key] = { avatarUrl: p?.avatarUrl || '', nickname: p?.nickname || '' }
    } catch (_) {
      suggestProfiles.value[key] = { avatarUrl: '', nickname: '' }
    }
  }
}
</script>

<style scoped>
</style>

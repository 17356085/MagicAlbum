<template>
  <header class="community-header fixed inset-x-0 top-0 z-40 overflow-visible">
    <div class="mx-auto max-w-7xl px-4 py-3 sm:px-6">
      <div class="flex min-w-0 items-center gap-1 sm:gap-2">
        <BrandBar class="min-w-0 shrink-0" />
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
        <nav class="flex shrink-0 items-center gap-0.5 text-sm" aria-label="账户和快捷操作">
        <button type="button" class="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700" @click="toggleTheme" :title="themeLabel" :aria-label="themeLabel">
          <span v-if="isDark">🌙</span><span v-else>☀️</span>
        </button>
        <!-- 发帖：未登录时直接拉起登录弹窗，已登录时进入发帖页 -->
        <router-link to="/threads/new" class="header-create inline-flex h-10 w-10 shrink-0 items-center justify-center gap-2 rounded-lg md:w-auto md:px-4" aria-label="发帖" title="发帖" @click="onCreateThreadClick">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
            <circle cx="12" cy="12" r="9" stroke-width="1.8" />
            <path d="M12 8.5v7M8.5 12h7" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span class="hidden font-semibold md:inline">发帖</span>
        </router-link>
        <NotificationsMenu :is-logged-in="isLoggedIn" @open-login="showLogin = true" />
        <AuthEntry v-if="!isLoggedIn" @open-login="showLogin = true" />
        <template v-else>
          <div class="mobile-user-menu shrink-0">
            <UserMenu :user="user" :avatar-url="avatarUrl" :display-name="displayName" @logout="onLogoutClick" />
          </div>
        </template>
        </nav>
      </div>

      <nav class="mt-1 grid grid-cols-4 gap-1 border-t border-gray-200/80 pt-1 text-[11px] dark:border-gray-700 md:hidden" aria-label="移动端导航">
        <router-link to="/discover" class="flex min-w-0 items-center justify-center gap-1 rounded px-1 py-1.5 font-medium" :class="mobileNavClass('/discover')" aria-label="发现">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.8" stroke="currentColor" class="h-4 w-4 shrink-0" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M15.042 21.672L13.684 16.6m0 0l-2.51 2.225.569-9.47 5.227 7.917-3.286-.672zm-7.518-.267A8.25 8.25 0 1120.25 10.5M8.288 14.212A5.25 5.25 0 1117.25 10.5" /></svg>
          <span class="truncate">发现</span>
        </router-link>
        <router-link to="/sections" class="flex min-w-0 items-center justify-center gap-1 rounded px-1 py-1.5 font-medium" :class="mobileNavClass('/sections')" aria-label="分区">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.8" stroke="currentColor" class="h-4 w-4 shrink-0" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M3.75 6A2.25 2.25 0 016 3.75h2.25A2.25 2.25 0 0110.5 6v2.25a2.25 2.25 0 01-2.25 2.25H6a2.25 2.25 0 01-2.25-2.25V6zM3.75 15.75A2.25 2.25 0 016 13.5h2.25a2.25 2.25 0 012.25 2.25V18a2.25 2.25 0 01-2.25 2.25H6A2.25 2.25 0 013.75 18v-2.25zM13.5 6a2.25 2.25 0 012.25-2.25H18A2.25 2.25 0 0120.25 6v2.25A2.25 2.25 0 0118 10.5h-2.25a2.25 2.25 0 01-2.25-2.25V6zM13.5 15.75a2.25 2.25 0 012.25-2.25H18a2.25 2.25 0 012.25 2.25V18A2.25 2.25 0 0118 20.25h-2.25A2.25 2.25 0 0113.5 18v-2.25z" /></svg>
          <span class="truncate">分区</span>
        </router-link>
        <router-link to="/ranking" class="flex min-w-0 items-center justify-center gap-1 rounded px-1 py-1.5 font-medium" :class="mobileNavClass('/ranking')" aria-label="排行榜">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.8" stroke="currentColor" class="h-4 w-4 shrink-0" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z" /></svg>
          <span class="truncate">排行榜</span>
        </router-link>
        <router-link to="/feed" class="flex min-w-0 items-center justify-center gap-1 rounded px-1 py-1.5 font-medium" :class="mobileNavClass('/feed')" aria-label="关注动态">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.8" stroke="currentColor" class="h-4 w-4 shrink-0" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M18 8a6 6 0 00-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></svg>
          <span class="truncate">关注动态</span>
        </router-link>
      </nav>
    </div>
  </header>
  <AuthModal v-if="showLogin" @close="showLogin = false" @success="onLoginSuccess" />

  <!-- 登出确认弹窗 -->
  <div v-if="showLogoutConfirm" class="ui-dialog-layer fixed inset-0 flex items-center justify-center p-4">
    <div class="absolute inset-0 bg-black/30" @click="showLogoutConfirm = false"></div>
    <div class="ui-panel relative z-10 w-full max-w-sm">
      <div class="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h3 class="text-base font-semibold">确认登出</h3>
        <button class="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showLogoutConfirm = false" aria-label="关闭">✕</button>
      </div>
      <div class="px-4 py-4 text-sm text-gray-700 dark:text-gray-200">确定要退出登录吗？</div>
      <div class="px-4 pb-4 flex items-center justify-end gap-2">
        <button class="ui-button" @click="showLogoutConfirm = false">取消</button>
          <button class="ui-button-primary" @click="confirmLogout">确认</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import AuthModal from '@/components/auth/AuthModal.vue'
import AuthEntry from '@/components/header/AuthEntry.vue'
import BrandBar from '@/components/header/BrandBar.vue'
import NotificationsMenu from '@/components/header/NotificationsMenu.vue'
import SearchBox from '@/components/header/SearchBox.vue'
import UserMenu from '@/components/header/UserMenu.vue'
import { suggestUsers, getUserProfile } from '@/api/users'
import { clearPendingAuthRedirect, getPendingAuthRedirect, setPendingAuthRedirect } from '@/utils/authStorage'
import { useHeaderIdentity } from '@/components/header/useHeaderIdentity'
import { useHeaderTheme } from '@/components/header/useHeaderTheme'
import type { Id, User, UserProfile } from '@/types'

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
let blurTimer: ReturnType<typeof setTimeout> | null = null
let logoutTimer: ReturnType<typeof setTimeout> | null = null
let suggestRequestSeq = 0
let suggestionsDisposed = false
const suggestProfiles = ref<Record<string, SuggestProfile>>({})
const activeIndex = ref(-1)
const keyword = computed(() => String(searchQuery.value || '').trim().toLowerCase())

function accountIdentity(): string {
  if (!isLoggedIn.value) return 'logged-out'
  const currentUser = user.value
  const id = currentUser?.id ?? currentUser?.userId
  if (id !== undefined && id !== null) return `id:${String(id)}`
  return `username:${String(currentUser?.username || '').trim().toLowerCase()}`
}

function isActiveSuggestionRequest(requestSeq: number, requestedKeyword: string, requestedAccount: string): boolean {
  return !suggestionsDisposed &&
    suggestRequestSeq === requestSeq &&
    searchType.value === 'users' &&
    String(searchQuery.value || '').trim() === requestedKeyword &&
    accountIdentity() === requestedAccount
}

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
const {
  avatarUrl,
  displayName,
  profileHydrated,
  applyUserIdentity,
  needsProfileHydration,
  refreshMyProfile,
  resetIdentity,
  onProfileUpdated,
} = useHeaderIdentity({ isLoggedIn, user, updateCurrentUser })
const { isDark, themeLabel, toggleTheme } = useHeaderTheme()

// 事件处理定义在 setup 顶层，便于在卸载时正确移除
const onOpenLoginModal = (): void => {
  showLogin.value = true
}

onMounted((): void => {
  applyUserIdentity()
  if (needsProfileHydration()) {
    refreshMyProfile().catch(() => {})
  } else {
    profileHydrated.value = true
  }
  window.addEventListener('profile-updated', onProfileUpdated)
  window.addEventListener('open-login-modal', onOpenLoginModal)
  if (!isLoggedIn.value && route.query.login === '1') {
    showLogin.value = true
    try {
      router.replace({ name: 'discover', query: { ...route.query, login: undefined } })
    } catch (_) {}
  }
})

onBeforeUnmount(() => {
  suggestionsDisposed = true
  suggestRequestSeq += 1
  window.removeEventListener('profile-updated', onProfileUpdated)
  window.removeEventListener('open-login-modal', onOpenLoginModal)
  if (suggestTimer) {
    clearTimeout(suggestTimer)
    suggestTimer = null
  }
  if (blurTimer) {
    clearTimeout(blurTimer)
    blurTimer = null
  }
  if (logoutTimer) {
    clearTimeout(logoutTimer)
    logoutTimer = null
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
      resetIdentity()
      return
    }
    applyUserIdentity()
    if (!profileHydrated.value && needsProfileHydration()) {
      refreshMyProfile().catch(() => {})
      return
    }
    profileHydrated.value = true
  },
  { immediate: true }
)

function mobileNavClass(path: string): string {
  const active = route.path === path || (path === '/discover' && route.path === '/')
  return active
    ? 'bg-brandDay-100 text-brandDay-700 dark:bg-brandNight-900/40 dark:text-brandNight-200'
    : 'text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700'
}

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
    if (logoutTimer) clearTimeout(logoutTimer)
    logoutTimer = setTimeout(() => {
      logoutTimer = null
      if (suggestionsDisposed) return
      try { logout() } catch (_) {}
    }, 30)
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
async function fetchSuggestions(keyword: string, requestSeq: number, requestedAccount: string): Promise<void> {
  if (!isActiveSuggestionRequest(requestSeq, keyword, requestedAccount)) return
  suggestLoading.value = true
  suggestError.value = ''
  try {
    const items = await suggestUsers(keyword, 5)
    if (!isActiveSuggestionRequest(requestSeq, keyword, requestedAccount)) return
    const base = (Array.isArray(items) ? items : [])
    suggestions.value = base.slice(0, 5)
    // 取消默认选中联想建议：仅在用户用方向键选择后才有选中项
    activeIndex.value = -1
    // 异步预取头像/昵称，提升建议项信息密度
    const ids = suggestions.value.map((u) => u.id).filter(Boolean) as Id[]
    void prefetchSuggestionProfiles(ids, requestSeq, requestedAccount, keyword)
  } catch (_) {
    if (!isActiveSuggestionRequest(requestSeq, keyword, requestedAccount)) return
    suggestError.value = '加载建议失败'
    suggestions.value = []
    activeIndex.value = -1
  } finally {
    if (isActiveSuggestionRequest(requestSeq, keyword, requestedAccount)) suggestLoading.value = false
  }
}

function scheduleSuggest(): void {
  if (suggestTimer) clearTimeout(suggestTimer)
  suggestTimer = null
  const requestSeq = ++suggestRequestSeq
  const requestedAccount = accountIdentity()
  const q = String(searchQuery.value || '').trim()
  suggestions.value = []
  activeIndex.value = -1
  suggestError.value = ''
  suggestLoading.value = false
  if (searchType.value !== 'users' || !q) {
    suggestOpen.value = false
    return
  }
  suggestTimer = setTimeout(() => {
    suggestTimer = null
    if (!isActiveSuggestionRequest(requestSeq, q, requestedAccount)) return
    suggestOpen.value = true
    void fetchSuggestions(q, requestSeq, requestedAccount)
  }, 250)
}

watch([searchQuery, searchType, accountIdentity], () => scheduleSuggest(), { flush: 'sync' })

function onInputFocus(): void {
  if (blurTimer) {
    clearTimeout(blurTimer)
    blurTimer = null
  }
  if (searchType.value === 'users' && String(searchQuery.value || '').trim()) {
    suggestOpen.value = true
  }
}

function onInputBlur(): void {
  // 延迟关闭，允许点击建议项
  if (blurTimer) clearTimeout(blurTimer)
  blurTimer = setTimeout(() => {
    blurTimer = null
    if (!suggestionsDisposed) suggestOpen.value = false
  }, 150)
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

async function prefetchSuggestionProfiles(ids: Id[], requestSeq: number, requestedAccount: string, requestedKeyword: string): Promise<void> {
  for (const id of ids) {
    const key = String(id)
    if (suggestProfiles.value[key]) continue
    try {
      const p = await getUserProfile(id)
      if (!isActiveSuggestionRequest(requestSeq, requestedKeyword, requestedAccount)) return
      suggestProfiles.value[key] = { avatarUrl: p?.avatarUrl || '', nickname: p?.nickname || '' }
    } catch (_) {
      if (!isActiveSuggestionRequest(requestSeq, requestedKeyword, requestedAccount)) return
      suggestProfiles.value[key] = { avatarUrl: '', nickname: '' }
    }
  }
}
</script>

<style scoped>
.community-header { z-index: var(--ui-z-navigation); background: var(--ui-surface); color: var(--ui-ink); border-bottom: 1px solid var(--ui-border); }
.header-create { background: var(--ui-accent-soft); color: var(--ui-accent); }
.header-create:hover { background: var(--ui-subtle); }
.mobile-user-menu :deep(a > span) { display: none; }
@media (min-width: 768px) {
  .mobile-user-menu :deep(a > span) { display: inline; }
}
</style>

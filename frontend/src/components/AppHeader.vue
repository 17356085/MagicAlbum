<template>
  <header class="community-header fixed inset-x-0 top-0 z-40 overflow-visible">
    <div class="mx-auto h-full max-w-7xl px-3 sm:px-6 lg:h-auto lg:py-3">
      <div class="flex h-full min-w-0 items-center gap-0 sm:gap-2 lg:h-auto">
        <button v-if="isMobileChildRoute" type="button" class="mr-auto inline-flex h-12 items-center gap-2 pr-3 font-semibold lg:hidden" aria-label="返回上一页" @click="goBack">
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="m15 5-7 7 7 7" stroke-linecap="round" stroke-linejoin="round" /></svg>
          返回
        </button>
        <BrandBar class="mr-auto min-w-0 shrink-0 lg:mr-0" :class="isMobileChildRoute ? 'hidden lg:flex' : ''" />
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
        <button type="button" class="hidden h-9 w-9 shrink-0 items-center justify-center rounded-lg hover:bg-gray-100 dark:hover:bg-gray-700 lg:inline-flex" @click="toggleTheme" :title="themeLabel" :aria-label="themeLabel">
          <span v-if="isDark">🌙</span><span v-else>☀️</span>
        </button>
        <!-- 发帖：未登录时直接拉起登录弹窗，已登录时进入发帖页 -->
        <router-link to="/threads/new" class="header-create hidden h-10 w-10 shrink-0 items-center justify-center gap-2 rounded-lg lg:inline-flex lg:w-auto lg:px-4" aria-label="发帖" title="发帖" @click="onCreateThreadClick">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
            <circle cx="12" cy="12" r="9" stroke-width="1.8" />
            <path d="M12 8.5v7M8.5 12h7" stroke-width="1.8" stroke-linecap="round" />
          </svg>
          <span class="hidden font-semibold lg:inline">发帖</span>
        </router-link>
        <NotificationsMenu :is-logged-in="isLoggedIn" @open-login="showLogin = true" />
        <span v-if="!isLoggedIn" class="hidden lg:inline-flex">
          <AuthEntry @open-login="showLogin = true" />
        </span>
        <template v-else>
          <div class="mobile-user-menu hidden shrink-0 lg:block">
            <UserMenu :user="user" :avatar-url="avatarUrl" :display-name="displayName" @logout="onLogoutClick" />
          </div>
        </template>
        </nav>
      </div>

    </div>
  </header>
  <DeferredAuthModal v-if="showLogin" @close="onLoginModalClose" @success="onLoginSuccess" />

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
import DeferredAuthModal from '@/components/auth/DeferredAuthModal.vue'
import AuthEntry from '@/components/header/AuthEntry.vue'
import BrandBar from '@/components/header/BrandBar.vue'
import NotificationsMenu from '@/components/header/NotificationsMenu.vue'
import SearchBox from '@/components/header/SearchBox.vue'
import UserMenu from '@/components/header/UserMenu.vue'
import { suggestUsers } from '@/api/users'
import { clearPendingAuthRedirect, getPendingAuthRedirect, setPendingAuthRedirect } from '@/utils/authStorage'
import { useHeaderIdentity } from '@/components/header/useHeaderIdentity'
import { useHeaderTheme } from '@/components/header/useHeaderTheme'
import type { User, UserProfile } from '@/types'
import { safeBack } from '@/utils/router'

const showLogin = ref(false)
const showLogoutConfirm = ref(false)
const authStore = useAuthStore()
const { isLoggedIn, token, user } = storeToRefs(authStore)
const { logout, updateCurrentUser, hydrateCurrentUser } = authStore
const router = useRouter()
const route = useRoute()
const isMobileChildRoute = computed(() => !['/', '/discover', '/sections', '/feed', '/ranking', '/me'].includes(route.path))
function goBack(): void { safeBack(router) }
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
let suggestRequestSeq = 0
let suggestionsDisposed = false
let sessionHydrationScheduled = false
// The list response already carries public profile fields; keep them tied to
// the current results instead of fetching and caching every user's profile.
const suggestProfiles = computed<Record<string, SuggestProfile>>(() => Object.fromEntries(
  suggestions.value.map((u) => [String(u.id), { avatarUrl: u.avatarUrl || '', nickname: u.nickname || '' }]),
))
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
    return includesI(u?.nickname, kw)
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
} = useHeaderIdentity({ isLoggedIn, token, user, updateCurrentUser, hydrateCurrentUser })
const { isDark, themeLabel, toggleTheme } = useHeaderTheme()

// 事件处理定义在 setup 顶层，便于在卸载时正确移除
const onOpenLoginModal = (): void => {
  showLogin.value = true
}

function onLoginModalClose(): void {
  showLogin.value = false
  clearPendingAuthRedirect()
}

const onAuthStateChanged = (): void => {
  if (isLoggedIn.value) return
  if (!route.matched.some((record) => record.meta?.requiresAuth)) return
  void router.replace({ name: 'discover' })
}

function scheduleSessionHydration(): void {
  if (sessionHydrationScheduled) return
  sessionHydrationScheduled = true
  queueMicrotask(() => {
    sessionHydrationScheduled = false
    if (suggestionsDisposed || !isLoggedIn.value) return
    refreshMyProfile(true).catch(() => {})
  })
}

onMounted((): void => {
  applyUserIdentity()
  window.addEventListener('profile-updated', onProfileUpdated)
  window.addEventListener('open-login-modal', onOpenLoginModal)
  window.addEventListener('auth-state-changed', onAuthStateChanged)
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
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  if (suggestTimer) {
    clearTimeout(suggestTimer)
    suggestTimer = null
  }
  if (blurTimer) {
    clearTimeout(blurTimer)
    blurTimer = null
  }
})

function onLoginSuccess(): void {
  applyUserIdentity()
  const redirect = getPendingAuthRedirect()
  if (redirect) {
    clearPendingAuthRedirect()
    try { router.push(redirect) } catch (_) {}
  }
}

watch(
  [
    () => isLoggedIn.value,
    () => token.value,
    () => user.value?.id ?? user.value?.userId,
  ],
  ([loggedIn, accessToken, userId], previousValue) => {
    const previousToken = previousValue?.[1]
    const previousUserId = previousValue?.[2]
    const previousKnownId = previousUserId !== undefined && previousUserId !== null && String(previousUserId).trim() !== ''
    const knownId = userId !== undefined && userId !== null && String(userId).trim() !== ''
    const tokenChanged = previousValue !== undefined && previousToken !== undefined && accessToken !== previousToken
    const knownIdChanged = previousKnownId && knownId && String(userId) !== String(previousUserId)
    const lostKnownId = previousKnownId && !knownId
    const sessionChanged = tokenChanged || knownIdChanged || lostKnownId
    if (sessionChanged) {
      profileHydrated.value = false
    }
    if (!loggedIn) {
      resetIdentity()
      return
    }
    applyUserIdentity()
    if (sessionChanged) {
      scheduleSessionHydration()
      return
    }
    if (!profileHydrated.value && needsProfileHydration()) {
      refreshMyProfile().catch(() => {})
      return
    }
    profileHydrated.value = true
  },
  { immediate: true }
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
  showLogoutConfirm.value = false
  // 根组件根据当前 route.matched 统一把所有受保护页面带回公开入口。
  logout()
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

</script>

<style scoped>
.community-header { z-index: var(--ui-z-navigation); height: calc(56px + env(safe-area-inset-top, 0px)); padding-top: env(safe-area-inset-top, 0px); background: var(--ui-surface); color: var(--ui-ink); border-bottom: 1px solid var(--ui-border); }
.header-create { background: var(--ui-accent-soft); color: var(--ui-accent); }
.header-create:hover { background: var(--ui-subtle); }
.mobile-user-menu :deep(a > span) { display: none; }
@media (min-width: 1024px) {
  .community-header { height: auto; padding-top: 0; }
  .mobile-user-menu :deep(a > span) { display: inline; }
}
</style>

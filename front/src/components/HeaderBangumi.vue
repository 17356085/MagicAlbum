<template>
  <header class="fixed top-0 left-0 right-0 z-50 border-b border-gray-200 bg-white/90 backdrop-blur dark:border-gray-700 dark:bg-gray-900/90 text-gray-800 dark:text-gray-200 motion-safe:transition-colors motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none">
    <div class="mx-auto max-w-7xl px-4 py-3 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <router-link to="/discover" class="inline-flex items-center hover:opacity-90" aria-label="返回发现">
          <IconMagicalbum aria-label="Magicalbum Logo" />
        </router-link>
        <router-link to="/discover" class="text-lg font-semibold tracking-wide hover:opacity-90 text-gray-800 dark:text-gray-100">MagicAlbum</router-link>
        <span class="ml-2 rounded bg-orange-100 px-2 py-0.5 text-xs text-orange-600">beta</span>
      </div>
      <div class="hidden md:flex md:flex-1 md:mx-6 items-center gap-3">
        <div class="inline-flex rounded-md border border-gray-300 bg-white p-0.5 text-xs dark:bg-gray-800 dark:border-gray-700">
          <button
            class="rounded px-4 py-1 whitespace-nowrap"
            :class="searchType === 'threads' ? 'bg-brand-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
            @click="searchType = 'threads'"
          >搜帖子</button>
          <button
            class="rounded px-4 py-1 whitespace-nowrap"
            :class="searchType === 'users' ? 'bg-brand-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
            @click="searchType = 'users'"
          >搜用户</button>
        </div>
        <div class="relative flex-1">
          <input
            v-model="searchQuery"
            type="text"
            :placeholder="searchType === 'users' ? '搜索用户名/昵称' : '搜索帖子标题或内容'"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 pr-10 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
            @keydown="onInputKeydown"
            @focus="onInputFocus"
            @blur="onInputBlur"
          />
          <!-- 搜索图标按钮：与输入框一体化，右侧绝对定位 -->
          <button
            class="absolute right-1 top-1/2 -translate-y-1/2 rounded bg-brandDay-600 dark:bg-brandNight-600 p-2 text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-colors motion-safe:transition-transform motion-safe:duration-150 active:scale-95 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
            @click="doSearch"
            aria-label="搜索"
            title="搜索"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4">
              <path fill-rule="evenodd" d="M12.9 14.32a8 8 0 111.41-1.41l4.39 4.39a1 1 0 01-1.42 1.42l-4.38-4.4zM14 8a6 6 0 11-12 0 6 6 0 0112 0z" clip-rule="evenodd"/>
            </svg>
            <span class="sr-only">搜索</span>
          </button>
          <div
            v-if="searchType === 'users' && suggestOpen && (searchQuery || '').trim()"
            class="absolute z-50 mt-2 w-full rounded-md border border-gray-200 bg-white shadow dark:bg-gray-800 dark:border-gray-700"
          >
            <div class="border-b px-3 py-2 text-xs font-medium dark:border-gray-700">匹配的用户</div>
            <div v-if="suggestLoading" class="px-3 py-2 text-xs text-gray-600 dark:text-gray-300">加载中...</div>
            <div v-else-if="suggestError" class="px-3 py-2 text-xs text-red-600">{{ suggestError }}</div>
            <ul v-else class="p-1 text-sm max-h-64 overflow-auto">
              <li v-if="!suggestions.length" class="px-3 py-2 text-xs text-gray-500 dark:text-gray-400">无匹配</li>
              <li
                v-for="(u, idx) in visibleSuggestions.slice(0, 5)"
                :key="u.id"
                @mousemove="activeIndex = idx"
              >
                <router-link
                  :to="'/users/' + u.id"
                  class="flex items-center justify-between rounded px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-700"
                  :class="activeIndex === idx ? 'bg-brand-50 dark:bg-brand-900/30' : ''"
                  @click="suggestOpen = false"
                >
                  <div class="flex items-center gap-2 min-w-0">
                    <template v-if="suggestProfiles[u.id]?.avatarUrl">
                      <img :src="normalizeImageUrl(suggestProfiles[u.id].avatarUrl)" alt="avatar" class="w-6 h-6 rounded-full object-cover border border-gray-300 dark:border-gray-700" />
                    </template>
                    <template v-else>
                      <div class="w-6 h-6 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-[10px] font-medium">
                        {{ String((suggestProfiles[u.id]?.nickname || u.username || 'U')).slice(0,1).toUpperCase() }}
                      </div>
                    </template>
                    <div class="truncate">
                      <span class="font-medium" v-html="renderHighlightedTextHtml(suggestProfiles[u.id]?.nickname || u.username, searchQuery)"></span>
                      <span v-if="suggestProfiles[u.id]?.nickname" class="ml-2 text-xs text-gray-500 dark:text-gray-400">{{ u.username }}</span>
                    </div>
                  </div>
                  <span class="text-xs text-gray-400">#{{ u.id }}</span>
                </router-link>
              </li>
            </ul>
          </div>
        </div>
        
      </div>
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
        <!-- 通知：铃铛图标（占位按钮，后续接入通知功能） -->
        <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center" aria-label="通知" title="通知">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
            <path d="M12 3a6 6 0 00-6 6v3.5l-1.5 2.5h15L18 12.5V9a6 6 0 00-6-6z" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M10 19a2 2 0 004 0" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span class="sr-only">通知</span>
        </button>
        <template v-if="!isLoggedIn">
          <button
            class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center"
            @click="showLogin = true"
            aria-label="登录"
            title="登录"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
              <circle cx="12" cy="8" r="3.25" stroke-width="1.8" />
              <path d="M5 19.25C5.9 16.55 8.43 15 12 15s6.1 1.55 7 4.25" stroke-width="1.8" stroke-linecap="round" />
              <path d="M19 8.75h3" stroke-width="1.8" stroke-linecap="round" />
              <path d="M20.5 7.25v3" stroke-width="1.8" stroke-linecap="round" />
            </svg>
            <span class="sr-only">登录</span>
          </button>
        </template>
        <template v-else>
          <div class="flex items-center gap-2">
            <router-link :to="user?.id ? ('/users/' + user.id) : '/settings'" class="flex items-center gap-2 hover:opacity-90">
              <img 
                :src="avatarUrl ? normalizeImageUrl(avatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${displayName || 'U'}`" 
                alt="avatar" 
                class="w-8 h-8 rounded-full object-cover border border-gray-300 dark:border-gray-700 bg-gray-100 dark:bg-gray-700" 
              />
              <span class="text-gray-700 dark:text-gray-200">{{ displayName || user?.username }}</span>
            </router-link>
            <!-- 登出：电源图标按钮 -->
            <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center" @click="onLogoutClick" aria-label="登出" title="登出">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
                <path d="M12 4v7.5" stroke-width="1.8" stroke-linecap="round" />
                <path d="M7.5 6.5a7 7 0 1 0 9 0" fill="none" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              <span class="sr-only">登出</span>
            </button>
          </div>
        </template>
      </nav>
    </div>
  </header>
  <LoginModal v-if="showLogin" @close="showLogin = false" @success="onLoginSuccess" />

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
import LoginModal from './LoginModal.vue'
import { getMyProfile } from '@/api/settings'
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'
import { suggestUsers, getUserProfile } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { highlightText } from '@/utils/text'
import { clearPendingAuthRedirect, getPendingAuthRedirect, setPendingAuthRedirect } from '@/utils/authStorage'
import type { Id, ProfileUpdatedDetail, User, UserProfile } from '@/types'

const showLogin = ref(false)
const showLogoutConfirm = ref(false)
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const { logout } = authStore
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
const avatarUrl = ref('')
const displayName = ref('')

function applyUserIdentity(): void {
  const currentUser = user.value
  avatarUrl.value = currentUser?.avatarUrl || ''
  displayName.value =
    (currentUser?.nickname && String(currentUser.nickname).trim()) ||
    currentUser?.username ||
    ''
}

async function refreshMyProfile(): Promise<void> {
  if (!isLoggedIn.value) {
    avatarUrl.value = ''
    displayName.value = ''
    return
  }

  // 先用登录态里的用户信息兜底，避免界面短时间回退成默认头像。
  applyUserIdentity()

  try {
    const p = await getMyProfile()
    avatarUrl.value = p?.avatarUrl || avatarUrl.value || ''
    displayName.value =
      (p?.nickname && String(p.nickname).trim()) ||
      displayName.value ||
      (user.value?.username || '')
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
}

const onOpenLoginModal = (): void => {
  showLogin.value = true
}

onMounted((): void => {
  initTheme()
  refreshMyProfile().catch(() => {})
  window.addEventListener('profile-updated', onProfileUpdated)
  window.addEventListener('open-login-modal', onOpenLoginModal)
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
  refreshMyProfile().catch(() => {})
  const redirect = getPendingAuthRedirect()
  if (redirect) {
    clearPendingAuthRedirect()
    try { router.push(redirect) } catch (_) {}
  }
}

watch(
  () => [isLoggedIn.value, user.value?.id, user.value?.avatarUrl, user.value?.nickname, user.value?.username] as const,
  ([loggedIn]) => {
    if (!loggedIn) {
      avatarUrl.value = ''
      displayName.value = ''
      return
    }
    refreshMyProfile().catch(() => {})
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

function renderHighlightedTextHtml(text: string | null | undefined, keyword: string | null | undefined): string {
  return highlightText(text, keyword)
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

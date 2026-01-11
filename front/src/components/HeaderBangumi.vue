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
                      <span class="font-medium" v-html="highlight(suggestProfiles[u.id]?.nickname || u.username, searchQuery)"></span>
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
        <!-- 发帖：圆圈加号图标 -->
        <router-link to="/threads/new" class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center" aria-label="发帖" title="发帖">
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
          <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showLogin = true">登录</button>
          <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showRegister = true">注册</button>
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
  <RegisterModal v-if="showRegister" @close="showRegister = false" @success="onRegisterSuccess" />
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

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import RegisterModal from './RegisterModal.vue'
import LoginModal from './LoginModal.vue'
import { getMyProfile } from '@/api/settings'
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'
import { suggestUsers, getUserProfile } from '@/api/users'
import DOMPurify from 'dompurify'

const showRegister = ref(false)
const showLogin = ref(false)
const showLogoutConfirm = ref(false)
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const { logout } = authStore
const router = useRouter()
const route = useRoute()
const searchType = ref('threads')
const searchQuery = ref('')
// 顶栏联想建议（仅用户搜索时启用）
const suggestOpen = ref(false)
const suggestions = ref([])
const suggestLoading = ref(false)
const suggestError = ref('')
let suggestTimer = null
const suggestProfiles = ref({})
const activeIndex = ref(-1)
const keyword = computed(() => String(searchQuery.value || '').trim().toLowerCase())

function includesI(str, kw) {
  return String(str || '').toLowerCase().includes(String(kw || '').toLowerCase())
}

// 仅支持用户名或昵称匹配的可见建议列表
const visibleSuggestions = computed(() => {
  const kw = keyword.value
  if (!kw) return []
  return (Array.isArray(suggestions.value) ? suggestions.value : []).filter(u => {
    if (includesI(u?.username, kw)) return true
    const p = suggestProfiles.value[u?.id]
    if (p && includesI(p.nickname, kw)) return true
    return false
  })
})
const avatarUrl = ref('')
const displayName = ref('')

function normalizeImageUrl(u) {
  if (!u) return ''
  const url = String(u).trim()
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
    return url
  }
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
  const backendBase = apiBase.replace(/\/api\/v1$/, '')
  if (url.startsWith('/')) return backendBase + url
  return backendBase + '/' + url
}

// 主题切换（light/dark），持久化到 localStorage，并同步到 html.dark
const isDark = ref(false)
const themeLabel = computed(() => (isDark.value ? '切换为白天模式' : '切换为黑夜模式'))

function applyThemeClass(dark) {
  const root = document.documentElement
  if (dark) root.classList.add('dark')
  else root.classList.remove('dark')
}

function initTheme() {
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

function toggleTheme() {
  isDark.value = !isDark.value
  localStorage.setItem('theme', isDark.value ? 'dark' : 'light')
  applyThemeClass(isDark.value)
}

// 事件处理定义在 setup 顶层，便于在卸载时正确移除
const onProfileUpdated = (evt) => {
  const detail = evt?.detail || {}
  const nextAvatar = detail?.avatarUrl
  const nextNickname = detail?.nickname
  if (typeof nextAvatar === 'string') avatarUrl.value = nextAvatar
  if (typeof nextNickname === 'string') displayName.value = nextNickname || (user.value?.username || '')
}

onMounted(() => {
  initTheme()
  // 避免在生命周期钩子中使用 await，改用 Promise
  getMyProfile()
    .then((p) => {
      avatarUrl.value = p?.avatarUrl || ''
      displayName.value = (p?.nickname && String(p.nickname).trim()) || (user.value?.username || '')
    })
    .catch(() => {})
  window.addEventListener('profile-updated', onProfileUpdated)
})

onUnmounted(() => {
  window.removeEventListener('profile-updated', onProfileUpdated)
})

function onLoginSuccess() {
  // 登录成功后主动拉取我的资料，刷新昵称与头像
  try {
    getMyProfile()
      .then((p) => {
        avatarUrl.value = p?.avatarUrl || ''
        displayName.value = (p?.nickname && String(p.nickname).trim()) || (user.value?.username || '')
      })
      .catch(() => {})
  } catch (_) {}
}

function onRegisterSuccess() {
  // TODO: 注册成功后的处理（例如提示或刷新用户状态）
}

function onLogoutClick() {
  showLogoutConfirm.value = true
}

function confirmLogout() {
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

function doSearch() {
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
async function fetchSuggestions(keyword) {
  suggestLoading.value = true
  suggestError.value = ''
  try {
    const items = await suggestUsers(keyword, 5)
    const base = (Array.isArray(items) ? items : [])
    suggestions.value = base.slice(0, 5)
    // 取消默认选中联想建议：仅在用户用方向键选择后才有选中项
    activeIndex.value = -1
    // 异步预取头像/昵称，提升建议项信息密度
    const ids = suggestions.value.map(u => u.id).filter(Boolean)
    prefetchSuggestionProfiles(ids)
  } catch (e) {
    suggestError.value = '加载建议失败'
    suggestions.value = []
    activeIndex.value = -1
  } finally {
    suggestLoading.value = false
  }
}

function scheduleSuggest() {
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

function onInputFocus() {
  if (searchType.value === 'users' && String(searchQuery.value || '').trim()) {
    suggestOpen.value = true
  }
}

function onInputBlur() {
  // 延迟关闭，允许点击建议项
  setTimeout(() => { suggestOpen.value = false }, 150)
}

function onInputKeydown(e) {
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

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function highlight(text, keyword) {
  const t = escapeHtml(String(text || ''))
  const k = String(keyword || '').trim()
  if (!k) return t
  const escaped = k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp(`(${escaped})`, 'ig')
  const replaced = t.replace(re, '<mark class="bg-yellow-100">$1</mark>')
  return DOMPurify.sanitize(replaced)
}

async function prefetchSuggestionProfiles(ids) {
  for (const id of ids) {
    if (suggestProfiles.value[id]) continue
    try {
      const p = await getUserProfile(id)
      suggestProfiles.value[id] = { avatarUrl: p?.avatarUrl || '', nickname: p?.nickname || '' }
    } catch (_) {
      suggestProfiles.value[id] = { avatarUrl: '', nickname: '' }
    }
  }
}
</script>

<style scoped>
</style>
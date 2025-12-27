<template>
  <header class="fixed top-0 left-0 right-0 z-50 border-b border-gray-200 bg-white/90 backdrop-blur dark:border-gray-700 dark:bg-gray-900/90">
    <div class="mx-auto max-w-7xl px-4 py-3 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <router-link to="/discover" class="inline-flex items-center hover:opacity-90" aria-label="返回发现">
          <IconMagicalbum aria-label="Magicalbum Logo" />
        </router-link>
        <router-link to="/discover" class="text-lg font-semibold tracking-wide hover:opacity-90">MagicAlbum</router-link>
        <span class="ml-2 rounded bg-orange-100 px-2 py-0.5 text-xs text-orange-600">beta</span>
      </div>
      <div class="hidden md:flex md:flex-1 md:mx-6 items-center gap-3">
        <div class="inline-flex rounded-md border border-gray-300 bg-white p-0.5 text-xs dark:bg-gray-800 dark:border-gray-700">
          <button
            class="rounded px-4 py-1 whitespace-nowrap"
            :class="searchType === 'threads' ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
            @click="searchType = 'threads'"
          >搜帖子</button>
          <button
            class="rounded px-4 py-1 whitespace-nowrap"
            :class="searchType === 'users' ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
            @click="searchType = 'users'"
          >搜用户</button>
        </div>
        <div class="relative flex-1">
          <input
            v-model="searchQuery"
            type="text"
            :placeholder="searchType === 'users' ? '搜索用户名/邮箱/手机号' : '搜索帖子标题或内容'"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100"
            @keyup.enter="doSearch"
            @focus="onInputFocus"
            @blur="onInputBlur"
          />
          <div
            v-if="searchType === 'users' && suggestOpen && (searchQuery || '').trim()"
            class="absolute z-50 mt-2 w-full rounded-md border border-gray-200 bg-white shadow dark:bg-gray-800 dark:border-gray-700"
          >
            <div class="border-b px-3 py-2 text-xs font-medium dark:border-gray-700">匹配的用户</div>
            <div v-if="suggestLoading" class="px-3 py-2 text-xs text-gray-600 dark:text-gray-300">加载中...</div>
            <div v-else-if="suggestError" class="px-3 py-2 text-xs text-red-600">{{ suggestError }}</div>
            <ul v-else class="p-1 text-sm max-h-64 overflow-auto">
              <li v-if="!suggestions.length" class="px-3 py-2 text-xs text-gray-500 dark:text-gray-400">无匹配</li>
              <li v-for="u in suggestions" :key="u.id">
                <router-link :to="'/users/' + u.id" class="flex items-center justify-between rounded px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-700">
                  <div class="truncate">
                    <span class="font-medium">{{ u.username }}</span>
                    <span v-if="u.email" class="ml-2 text-xs text-gray-500 dark:text-gray-400">{{ u.email }}</span>
                  </div>
                  <span class="text-xs text-gray-400">#{{ u.id }}</span>
                </router-link>
              </li>
            </ul>
          </div>
        </div>
        <button class="rounded bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 whitespace-nowrap shrink-0" @click="doSearch">搜索</button>
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
        <template v-if="!isLoggedIn">
          <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showLogin = true">登录</button>
          <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showRegister = true">注册</button>
        </template>
        <template v-else>
          <div class="flex items-center gap-2">
            <router-link :to="user?.id ? ('/users/' + user.id) : '/settings'" class="flex items-center gap-2 hover:opacity-90">
              <img v-if="avatarUrl" :src="normalizeImageUrl(avatarUrl)" alt="avatar" class="w-8 h-8 rounded-full object-cover border border-gray-300 dark:border-gray-700" />
              <div v-else class="w-8 h-8 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-xs font-medium">
                {{ String(displayName || 'U').slice(0,1).toUpperCase() }}
              </div>
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
        <button class="rounded px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700" @click="showLogoutConfirm = false">取消</button>
        <button class="rounded bg-blue-600 px-3 py-2 text-sm text-white hover:bg-blue-700" @click="confirmLogout">确认</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import RegisterModal from './RegisterModal.vue'
import LoginModal from './LoginModal.vue'
import { useAuth } from '@/composables/useAuth'
import { getMyProfile } from '@/api/settings'
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'
import { suggestUsers } from '@/api/users'

const showRegister = ref(false)
const showLogin = ref(false)
const showLogoutConfirm = ref(false)
const { isLoggedIn, user, logout } = useAuth()
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
    const items = await suggestUsers(keyword, 6)
    suggestions.value = Array.isArray(items) ? items : []
  } catch (e) {
    suggestError.value = '加载建议失败'
    suggestions.value = []
  } finally {
    suggestLoading.value = false
  }
}

function scheduleSuggest() {
  if (suggestTimer) clearTimeout(suggestTimer)
  const q = String(searchQuery.value || '').trim()
  if (searchType.value !== 'users' || !q) {
    suggestions.value = []
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
</script>

<style scoped>
</style>
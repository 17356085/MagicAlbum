<template>
  <header class="border-b border-gray-200 bg-white/90 backdrop-blur dark:border-gray-700 dark:bg-gray-900/90">
    <div class="mx-auto max-w-6xl px-4 py-3 flex items-center justify-between">
      <div class="flex items-center gap-3">
        <IconMagicalbum aria-label="Magicalbum Logo" />
        <router-link to="/sections" class="text-lg font-semibold tracking-wide">MagicAlbum</router-link>
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
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="searchType === 'users' ? '搜索用户名/邮箱/手机号' : '搜索帖子标题或内容'"
          class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100"
          @keyup.enter="doSearch"
        />
        <button class="rounded bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 whitespace-nowrap shrink-0" @click="doSearch">搜索</button>
      </div>
      <nav class="flex items-center gap-2 text-sm">
        <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="toggleTheme" :title="themeLabel">
          <span v-if="isDark">🌙</span><span v-else>☀️</span>
        </button>
        <a href="/threads/new" class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700">发帖</a>
        <template v-if="!isLoggedIn">
          <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showLogin = true">登录</button>
          <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="showRegister = true">注册</button>
        </template>
        <template v-else>
          <div class="flex items-center gap-2">
            <router-link :to="user?.id ? ('/users/' + user.id) : '/settings'" class="flex items-center gap-2 hover:opacity-90">
              <img v-if="avatarUrl" :src="normalizeImageUrl(avatarUrl)" alt="avatar" class="w-8 h-8 rounded-full object-cover border border-gray-300 dark:border-gray-700" />
              <div v-else class="w-8 h-8 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-xs font-medium">
                {{ (user?.username || 'U').slice(0,1).toUpperCase() }}
              </div>
              <span class="text-gray-700 dark:text-gray-200">{{ user?.username }}</span>
            </router-link>
            <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="onLogoutClick">登出</button>
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
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import RegisterModal from './RegisterModal.vue'
import LoginModal from './LoginModal.vue'
import { useAuth } from '@/composables/useAuth'
import { getMyProfile } from '@/api/settings'
import IconMagicalbum from '@/components/icons/IconMagicalbum.vue'

const showRegister = ref(false)
const showLogin = ref(false)
const showLogoutConfirm = ref(false)
const { isLoggedIn, user, logout } = useAuth()
const router = useRouter()
const route = useRoute()
const searchType = ref('threads')
const searchQuery = ref('')
const avatarUrl = ref('')

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
  const next = evt?.detail?.avatarUrl
  if (typeof next === 'string') avatarUrl.value = next
}

onMounted(() => {
  initTheme()
  // 避免在生命周期钩子中使用 await，改用 Promise
  getMyProfile()
    .then((p) => { avatarUrl.value = p?.avatarUrl || '' })
    .catch(() => {})
  window.addEventListener('profile-updated', onProfileUpdated)
})

onUnmounted(() => {
  window.removeEventListener('profile-updated', onProfileUpdated)
})

function onLoginSuccess() {
  // 登录成功后保持弹窗关闭，Header 自动显示用户信息
}

function onRegisterSuccess() {
  // TODO: 注册成功后的处理（例如提示或刷新用户状态）
}

function onLogoutClick() {
  showLogoutConfirm.value = true
}

function confirmLogout() {
  logout()
  showLogoutConfirm.value = false
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
</script>

<style scoped>
</style>
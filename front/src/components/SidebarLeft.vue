<template>
  <aside class="space-y-3">
    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">导航</div>
      <ul class="p-2 text-sm space-y-1">
        <li>
          <router-link
            to="/sections"
            class="relative block rounded py-1 pl-8 pr-2 text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-colors motion-safe:duration-200 motion-reduce:transition-none"
            :class="isSectionsActive ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : ''"
          >
            <span
              class="absolute left-0 top-1 bottom-1 w-1 rounded bg-brandDay-500 dark:bg-accentCyan-400 transition-all duration-200 opacity-0 -translate-x-1"
              :class="isSectionsActive ? 'opacity-100 translate-x-0' : ''"
            ></span>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="absolute left-2 top-[6px] w-4 h-4 text-gray-500 dark:text-gray-400"><path d="M4 4h5v5H4V4zm7 0h5v5h-5V4zM4 11h5v5H4v-5zm7 5v-5h5v5h-5z"/></svg>
            <span>分区</span>
          </router-link>
        </li>
        <li>
          <router-link
            to="/discover"
            class="relative block rounded py-1 pl-8 pr-2 text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-colors motion-safe:duration-200 motion-reduce:transition-none"
            :class="isDiscoverActive ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : ''"
          >
            <span
              class="absolute left-0 top-1 bottom-1 w-1 rounded bg-brandDay-500 dark:bg-accentCyan-400 transition-all duration-200 opacity-0 -translate-x-1"
              :class="isDiscoverActive ? 'opacity-100 translate-x-0' : ''"
            ></span>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="absolute left-2 top-[6px] w-4 h-4 text-gray-500 dark:text-gray-400"><path fill-rule="evenodd" d="M12.9 14.32a8 8 0 111.41-1.41l4.39 4.39a1 1 0 01-1.42 1.42l-4.38-4.4zM14 8a6 6 0 11-12 0 6 6 0 0112 0z" clip-rule="evenodd"/></svg>
            <span>发现</span>
          </router-link>
        </li>
        <li>
          <a href="#" class="relative block rounded py-1 pl-8 pr-2 text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="absolute left-2 top-[6px] w-4 h-4 text-gray-500 dark:text-gray-400"><path d="M10 3l2.39 4.84L18 8.27l-4 3.9.95 5.53L10 15.9l-4.95 1.8L6 12.17l-4-3.9 5.61-.43L10 3z"/></svg>
            <span>排行榜</span>
          </a>
        </li>
        <li v-if="isLoggedIn">
          <router-link
            to="/settings"
            class="relative block rounded py-1 pl-8 pr-2 text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-colors motion-safe:duration-200 motion-reduce:transition-none"
            :class="isSettingsActive ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : ''"
          >
            <span
              class="absolute left-0 top-1 bottom-1 w-1 rounded bg-brandDay-500 dark:bg-accentCyan-400 transition-all duration-200 opacity-0 -translate-x-1"
              :class="isSettingsActive ? 'opacity-100 translate-x-0' : ''"
            ></span>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="absolute left-2 top-[6px] w-4 h-4 text-gray-500 dark:text-gray-400"><path fill-rule="evenodd" d="M11.983 1.918a1 1 0 00-1.966 0l-.176 1.408a7.027 7.027 0 00-1.59.92l-1.26-.73a1 1 0 00-1.366.366l-1 1.732a1 1 0 00.366 1.366l1.26.73a7.04 7.04 0 000 1.84l-1.26.73a1 1 0 00-.366 1.366l1 1.732a1 1 0 001.366.366l1.26-.73c.49.36 1.03.66 1.59.92l.176 1.408a1 1 0 001.966 0l.176-1.408a7.027 7.027 0 001.59-.92l1.26.73a1 1 0 001.366-.366l1-1.732a1 1 0 00-.366-1.366l-1.26-.73a7.04 7.04 0 000-1.84l1.26-.73a1 1 0 00.366-1.366l-1-1.732a1 1 0 00-1.366-.366l-1.26.73a7.027 7.027 0 00-1.59-.92l-.176-1.408zM10 12a2 2 0 100-4 2 2 0 000 4z" clip-rule="evenodd"/></svg>
            <span>设置</span>
          </router-link>
        </li>
        <li v-if="isLoggedIn">
          <router-link
            to="/my/threads"
            class="relative block rounded py-1 pl-8 pr-2 text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-colors motion-safe:duration-200 motion-reduce:transition-none"
            :class="isMyThreadsActive ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : ''"
          >
            <span
              class="absolute left-0 top-1 bottom-1 w-1 rounded bg-brandDay-500 dark:bg-accentCyan-400 transition-all duration-200 opacity-0 -translate-x-1"
              :class="isMyThreadsActive ? 'opacity-100 translate-x-0' : ''"
            ></span>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="absolute left-2 top-[6px] w-4 h-4 text-gray-500 dark:text-gray-400"><path d="M4 3h12a1 1 0 011 1v12a1 1 0 01-1 1H4a1 1 0 01-1-1V4a1 1 0 011-1zm2 3h8v2H6V6zm0 4h8v2H6v-2zm0 4h5v2H6v-2z"/></svg>
            <span>我的帖子</span>
          </router-link>
        </li>
        <li v-if="isLoggedIn">
          <router-link
            to="/my/posts"
            class="relative block rounded py-1 pl-8 pr-2 text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-colors motion-safe:duration-200 motion-reduce:transition-none"
            :class="isMyPostsActive ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : ''"
          >
            <span
              class="absolute left-0 top-1 bottom-1 w-1 rounded bg-brandDay-500 dark:bg-accentCyan-400 transition-all duration-200 opacity-0 -translate-x-1"
              :class="isMyPostsActive ? 'opacity-100 translate-x-0' : ''"
            ></span>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="absolute left-2 top-[6px] w-4 h-4 text-gray-500 dark:text-gray-400"><path d="M3 5a2 2 0 012-2h10a2 2 0 012 2v6a2 2 0 01-2 2H7l-4 4V5z"/></svg>
            <span>我的评论</span>
          </router-link>
        </li>
      </ul>
    </div>
    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">标签</div>
      <div class="p-2 flex flex-wrap gap-2 text-xs">
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">摄影</span>
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">绘画</span>
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">旅行</span>
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">随笔</span>
      </div>
    </div>
    <!-- 关注的用户：最近动态摘要（折叠，假数据） -->
    <div v-if="isLoggedIn" class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">
        <span>关注的用户</span>
        <button class="text-xs text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200" @click="toggleFollowCollapsed">{{ followCollapsed ? '展开' : '折叠' }}</button>
      </div>
      <div v-show="!followCollapsed" class="p-2 text-sm">
        <ul class="space-y-2">
          <li v-for="u in followedUsers" :key="u.id" class="flex items-start gap-2">
            <img :src="u.avatarUrl" alt="avatar" class="h-6 w-6 rounded-full object-cover" />
            <div class="flex-1 min-w-0">
              <div class="truncate font-medium">@{{ u.username }} <span v-if="u.nickname" class="text-xs text-gray-500 dark:text-gray-400">（{{ u.nickname }}）</span></div>
              <ul class="mt-1 text-xs text-gray-600 dark:text-gray-300">
                <li v-for="(a, idx) in u.activities.slice(0, 2)" :key="idx" class="truncate">• {{ a.text }} <span class="ml-1 text-[10px] text-gray-500">{{ a.time }}</span></li>
              </ul>
            </div>
          </li>
        </ul>
      </div>
    </div>

    <!-- 最近浏览：近几天我看过/打开过的条目（本地记录） -->
    <div v-if="isLoggedIn" class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">最近浏览</div>
      <div class="p-2 text-sm">
        <div v-if="recentVisits.length === 0" class="text-xs text-gray-500 dark:text-gray-400">暂无记录</div>
        <ul v-else class="space-y-1">
          <li v-for="v in recentVisits" :key="v.path" class="flex items-center justify-between gap-2">
            <router-link :to="v.path" class="min-w-0 truncate rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700">{{ v.title || v.name || v.path }}</router-link>
            <span class="flex-none whitespace-nowrap text-[10px] text-gray-500 dark:text-gray-400">{{ formatRelative(v.ts) }}</span>
          </li>
        </ul>
        <div class="mt-2 flex items-center justify-between">
          <button class="text-xs text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200" @click="clearRecent">清除</button>
          <router-link to="/history" class="text-xs text-brandDay-600 hover:underline dark:text-brandNight-400">查看全部</router-link>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '@/composables/useAuth'
import { getRecentVisits } from '@/composables/useRecentVisits'

const route = useRoute()
const { isLoggedIn } = useAuth()
const isSectionsActive = computed(() => route.name === 'sections' || route.path === '/sections')
const isDiscoverActive = computed(() => route.name === 'discover' || route.path === '/discover')
const isSettingsActive = computed(() => route.path.startsWith('/settings'))
const isMyThreadsActive = computed(() => route.name === 'my-threads' || route.path === '/my/threads')
const isMyPostsActive = computed(() => route.name === 'my-posts' || route.path === '/my/posts')

// 关注的用户（假数据）与折叠状态
const followCollapsed = ref(true)
function toggleFollowCollapsed() {
  followCollapsed.value = !followCollapsed.value
  try { localStorage.setItem('sidebar_follow_collapsed', String(followCollapsed.value)) } catch (_) {}
}
onMounted(() => {
  try {
    const raw = localStorage.getItem('sidebar_follow_collapsed')
    if (raw === 'false') followCollapsed.value = false
  } catch (_) {}
})

const followedUsers = ref([
  {
    id: 1,
    username: 'alice',
    nickname: '爱丽丝',
    avatarUrl: 'https://avatars.githubusercontent.com/u/9919?s=40&v=4',
    activities: [
      { text: '发布了新帖子《冬夜星光》', time: '2 小时前' },
      { text: '收藏了条目《雪之声》', time: '昨天' },
    ],
  },
  {
    id: 2,
    username: 'bob',
    nickname: '鲍勃',
    avatarUrl: 'https://avatars.githubusercontent.com/u/583231?s=40&v=4',
    activities: [
      { text: '评论了《旅途相册》', time: '3 小时前' },
      { text: '关注了用户 @charlie', time: '前天' },
    ],
  },
])

// 最近浏览：读取并展示（监听更新事件）
const recentVisits = ref(getRecentVisits(5))
function refreshRecent() { recentVisits.value = getRecentVisits(5) }
function onRecentUpdated() { refreshRecent() }
onMounted(() => { window.addEventListener('recent-visits-updated', onRecentUpdated) })
onBeforeUnmount(() => { window.removeEventListener('recent-visits-updated', onRecentUpdated) })
function clearRecent() {
  try { localStorage.removeItem('recent_visits_v1') } catch (_) {}
  refreshRecent()
}

function formatRelative(ts) {
  const diff = Date.now() - Number(ts || 0)
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  return `${d} 天前`
}
</script>
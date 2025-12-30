<template>
  <aside class="space-y-4">
    <!-- 主导航菜单 -->
    <nav class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="px-4 py-3 text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">
        浏览
      </div>
      <ul class="space-y-1 px-2 pb-2">
        <!-- 分区（可折叠） -->
        <li>
          <button
            @click="toggleSections"
            class="group flex w-full items-center justify-between rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isSectionsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <div class="flex items-center gap-3">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3.75 6A2.25 2.25 0 016 3.75h2.25A2.25 2.25 0 0110.5 6v2.25a2.25 2.25 0 01-2.25 2.25H6a2.25 2.25 0 01-2.25-2.25V6zM3.75 15.75A2.25 2.25 0 016 13.5h2.25a2.25 2.25 0 012.25 2.25V18a2.25 2.25 0 01-2.25 2.25H6A2.25 2.25 0 013.75 18v-2.25zM13.5 6a2.25 2.25 0 012.25-2.25H18A2.25 2.25 0 0120.25 6v2.25A2.25 2.25 0 0118 10.5h-2.25a2.25 2.25 0 01-2.25-2.25V6zM13.5 15.75a2.25 2.25 0 012.25-2.25H18a2.25 2.25 0 012.25 2.25V18A2.25 2.25 0 0118 20.25h-2.25A2.25 2.25 0 0113.5 18v-2.25z" />
              </svg>
              <span>分区</span>
            </div>
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-4 w-4 text-gray-400 transition-transform duration-200" :class="{ 'rotate-90': sectionsOpen }">
              <path fill-rule="evenodd" d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.29a.75.75 0 111.04-1.08l4.5 4.25a.75.75 0 010 1.08l-4.5 4.25a.75.75 0 01-1.06-.02z" clip-rule="evenodd" />
            </svg>
          </button>
          
          <!-- 子菜单：分区列表 -->
          <div
            class="grid overflow-hidden transition-all duration-300 ease-in-out"
            :class="sectionsOpen ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'"
          >
            <div class="min-h-0 space-y-0.5 pl-10 pr-2 pt-1">
              <router-link
                to="/sections"
                class="block rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-gray-50 hover:text-gray-900 dark:hover:bg-gray-700 dark:hover:text-gray-100"
                :class="route.path === '/sections' ? 'bg-gray-100 text-gray-900 font-medium dark:bg-gray-700 dark:text-gray-100' : 'text-gray-500 dark:text-gray-400'"
              >
                全部/概览
              </router-link>
              <div v-if="loadingSections" class="px-2 py-1 text-xs text-gray-400">加载中...</div>
              <router-link
                v-else
                v-for="s in sections"
                :key="s.id"
                :to="{ name: 'discover', query: { sectionId: s.id } }"
                class="block truncate rounded-md px-2 py-1.5 text-xs transition-colors hover:bg-gray-50 hover:text-gray-900 dark:hover:bg-gray-700 dark:hover:text-gray-100"
                :class="(route.name === 'discover' && String(route.query.sectionId) === String(s.id)) ? 'bg-brandDay-50 text-brandDay-600 font-medium dark:bg-brandNight-900/30 dark:text-brandNight-400' : 'text-gray-500 dark:text-gray-400'"
              >
                {{ s.name }}
              </router-link>
            </div>
          </div>
        </li>

        <!-- 发现 -->
        <li>
          <router-link
            to="/discover"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isDiscoverActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M15.042 21.672L13.684 16.6m0 0l-2.51 2.225.569-9.47 5.227 7.917-3.286-.672zm-7.518-.267A8.25 8.25 0 1120.25 10.5M8.288 14.212A5.25 5.25 0 1117.25 10.5" />
            </svg>
            <span>发现</span>
          </router-link>
        </li>

        <!-- 排行榜 -->
        <li>
          <a
            href="#"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50 text-gray-700 dark:text-gray-200"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 013 19.875v-6.75zM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V8.625zM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 01-1.125-1.125V4.125z" />
            </svg>
            <span>排行榜</span>
          </a>
        </li>
      </ul>
    </nav>

    <!-- 个人中心菜单 (仅登录) -->
    <nav v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="px-4 py-3 text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">
        我的
      </div>
      <ul class="space-y-1 px-2 pb-2">
        <li>
          <router-link
            to="/my/threads"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isMyThreadsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 14.25v-2.625a3.375 3.375 0 00-3.375-3.375h-1.5A1.125 1.125 0 0113.5 7.125v-1.5a3.375 3.375 0 00-3.375-3.375H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.625c-.621 0-1.125.504-1.125 1.125v17.25c0 .621.504 1.125 1.125 1.125h12.75c.621 0 1.125-.504 1.125-1.125V11.25a9 9 0 00-9-9z" />
            </svg>
            <span>我的帖子</span>
          </router-link>
        </li>
        <li>
          <router-link
            to="/my/posts"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isMyPostsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 01.865-.501 48.172 48.172 0 003.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
            </svg>
            <span>我的评论</span>
          </router-link>
        </li>
        <li>
          <router-link
            to="/settings"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors duration-200 hover:bg-gray-50 dark:hover:bg-gray-700/50"
            :class="isSettingsActive ? 'text-brandDay-600 bg-brandDay-50 dark:text-brandNight-400 dark:bg-brandNight-900/20' : 'text-gray-700 dark:text-gray-200'"
          >
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="h-5 w-5 opacity-70 group-hover:opacity-100">
              <path stroke-linecap="round" stroke-linejoin="round" d="M10.343 3.94c.09-.542.56-.94 1.11-.94h1.093c.55 0 1.02.398 1.11.94l.149.894c.07.424.384.764.78.93.398.164.855.142 1.205-.108l.737-.527a1.125 1.125 0 011.45.12l.773.774c.39.389.44 1.002.12 1.45l-.527.737c-.25.35-.272.806-.107 1.204.165.397.505.71.93.78l.893.15c.543.09.94.56.94 1.109v1.094c0 .55-.397 1.02-.94 1.11l-.893.149c-.425.07-.765.383-.93.78-.165.398-.143.854.107 1.204l.527.738c.32.447.269 1.06-.12 1.45l-.774.773a1.125 1.125 0 01-1.449.12l-.738-.527c-.35-.25-.806-.272-1.203-.107-.397.165-.71.505-.781.929l-.149.894c-.09.542-.56.94-1.11.94h-1.094c-.55 0-1.019-.398-1.11-.94l-.148-.894c-.071-.424-.384-.764-.781-.93-.398-.164-.854-.142-1.204.108l-.738.527c-.447.32-1.06.269-1.45-.12l-.773-.774a1.125 1.125 0 01-.12-1.45l.527-.737c.25-.35.273-.806.108-1.204-.165-.397-.505-.71-.93-.78l-.894-.15c-.542-.09-.94-.56-.94-1.109v-1.094c0-.55.398-1.02.94-1.11l.894-.149c.424-.07.765-.383.93-.78.165-.398.143-.854-.107-1.204l-.527-.738a1.125 1.125 0 01.12-1.45l.773-.773a1.125 1.125 0 011.45-.12l.737.527c.35.25.807.272 1.204.107.397-.165.71-.505.78-.929l.15-.894z" />
              <path stroke-linecap="round" stroke-linejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            <span>设置</span>
          </router-link>
        </li>
      </ul>
    </nav>

    <!-- 标签 -->
    <div class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="mb-3 text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">
        标签
      </div>
      <div class="flex flex-wrap gap-2">
        <span v-for="tag in ['摄影', '绘画', '旅行', '随笔', '美食', '数码']" :key="tag" class="cursor-pointer rounded-md bg-gray-50 px-2.5 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-gray-100 hover:text-brandDay-600 dark:bg-gray-700/50 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-brandNight-300">
          #{{ tag }}
        </span>
      </div>
    </div>

    <!-- 关注的用户 -->
    <div v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between px-4 py-3">
        <span class="text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">关注的用户</span>
        <button class="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300" @click="toggleFollowCollapsed">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-4 w-4 transition-transform duration-200" :class="{ 'rotate-180': !followCollapsed }">
            <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 10.94l3.71-3.71a.75.75 0 011.08 1.04l-4.25 4.25a.75.75 0 01-1.08 0L5.21 8.27a.75.75 0 01.02-1.06z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
      
      <div v-show="!followCollapsed" class="px-2 pb-2">
        <ul class="space-y-1">
          <li v-for="u in followedUsers" :key="u.id" class="group rounded-lg p-2 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50">
            <div class="flex items-start gap-3">
              <img :src="u.avatarUrl" alt="avatar" class="mt-0.5 h-8 w-8 rounded-full object-cover ring-2 ring-transparent transition-all group-hover:ring-brandDay-100 dark:group-hover:ring-brandNight-900" />
              <div class="min-w-0 flex-1">
                <div class="flex items-center justify-between">
                   <div class="truncate text-sm font-medium text-gray-700 dark:text-gray-200">
                     {{ u.nickname || u.username }}
                   </div>
                   <span class="text-[10px] text-gray-400">{{ u.activities[0]?.time }}</span>
                </div>
                <div class="mt-0.5 truncate text-xs text-gray-500 dark:text-gray-400">
                  {{ u.activities[0]?.text }}
                </div>
              </div>
            </div>
          </li>
        </ul>
      </div>
    </div>

    <!-- 最近浏览 -->
    <div v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between px-4 py-3">
        <span class="text-xs font-bold uppercase tracking-wider text-gray-400 dark:text-gray-500">最近浏览</span>
        <div class="flex gap-2">
           <button class="text-[10px] text-gray-400 hover:text-brandDay-600 dark:hover:text-brandNight-400" @click="refreshRecent">刷新</button>
           <button class="text-[10px] text-gray-400 hover:text-red-500" @click="clearRecent">清除</button>
        </div>
      </div>
      
      <div class="px-2 pb-2">
        <div v-if="recentVisits.length === 0" class="py-4 text-center text-xs text-gray-400">暂无浏览记录</div>
        <ul v-else class="space-y-0.5">
          <li v-for="v in recentVisits" :key="v.path">
            <router-link :to="v.path" class="group flex items-center justify-between rounded-md px-2 py-1.5 text-xs text-gray-600 transition-colors hover:bg-gray-50 hover:text-brandDay-700 dark:text-gray-400 dark:hover:bg-gray-700/50 dark:hover:text-brandNight-300">
              <span class="truncate pr-2">{{ v.title || v.name || v.path }}</span>
              <span class="shrink-0 text-[10px] text-gray-400 opacity-0 transition-opacity group-hover:opacity-100">{{ formatRelativeShort(v.ts) }}</span>
            </router-link>
          </li>
        </ul>
        <div class="mt-2 border-t border-gray-50 pt-2 text-center dark:border-gray-700/50">
          <router-link to="/history" class="text-xs font-medium text-brandDay-500 hover:text-brandDay-600 dark:text-brandNight-400 dark:hover:text-brandNight-300">
            查看全部历史
          </router-link>
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
import { listSections } from '@/api/sections'

const route = useRoute()
const { isLoggedIn } = useAuth()
const isSectionsActive = computed(() => route.name === 'sections' || route.path === '/sections')
const isDiscoverActive = computed(() => route.name === 'discover' || route.path === '/discover')
const isSettingsActive = computed(() => route.path.startsWith('/settings'))
const isMyThreadsActive = computed(() => route.name === 'my-threads' || route.path === '/my/threads')
const isMyPostsActive = computed(() => route.name === 'my-posts' || route.path === '/my/posts')

// 分区折叠逻辑
const sectionsOpen = ref(true)
const sections = ref([])
const loadingSections = ref(false)

async function loadSectionsData() {
  if (sections.value.length > 0) return
  loadingSections.value = true
  try {
    const data = await listSections({ size: 20 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (e) {
    console.error(e)
  } finally {
    loadingSections.value = false
  }
}

function toggleSections() {
  sectionsOpen.value = !sectionsOpen.value
  if (sectionsOpen.value) {
    loadSectionsData()
  }
}

// 关注的用户（假数据）与折叠状态
const followCollapsed = ref(false)
function toggleFollowCollapsed() {
  followCollapsed.value = !followCollapsed.value
  try { localStorage.setItem('sidebar_follow_collapsed', String(followCollapsed.value)) } catch (_) {}
}

const followedUsers = ref([
  {
    id: 1,
    username: 'alice',
    nickname: '爱丽丝',
    avatarUrl: 'https://avatars.githubusercontent.com/u/9919?s=40&v=4',
    activities: [
      { text: '发布了新帖子《冬夜星光》', time: '2h' },
      { text: '收藏了条目《雪之声》', time: '1d' },
    ],
  },
  {
    id: 2,
    username: 'bob',
    nickname: '鲍勃',
    avatarUrl: 'https://avatars.githubusercontent.com/u/583231?s=40&v=4',
    activities: [
      { text: '评论了《旅途相册》', time: '3h' },
      { text: '关注了用户 @charlie', time: '2d' },
    ],
  },
])

// 最近浏览
const recentVisits = ref(getRecentVisits(5))
function refreshRecent() { recentVisits.value = getRecentVisits(5) }
function onRecentUpdated() { refreshRecent() }
function clearRecent() {
  try { localStorage.removeItem('recent_visits_v1') } catch (_) {}
  refreshRecent()
}

function formatRelativeShort(ts) {
  const diff = Date.now() - Number(ts || 0)
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  return `${Math.floor(h / 24)}d`
}

onMounted(() => {
  try {
    const raw = localStorage.getItem('sidebar_follow_collapsed')
    if (raw === 'true') followCollapsed.value = true // Default open, save logic inverted? No, usually collapse=true means hidden.
  } catch (_) {}
  
  // 默认加载分区
  loadSectionsData()
  
  window.addEventListener('recent-visits-updated', onRecentUpdated)
})

onBeforeUnmount(() => { window.removeEventListener('recent-visits-updated', onRecentUpdated) })
</script>
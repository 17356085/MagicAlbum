<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getAllRecentVisits, clearAllRecentVisits, pruneExpired } from '@/composables/useRecentVisits'
import { useAuth } from '@/composables/useAuth'
import { listSections } from '@/api/sections'

const router = useRouter()
const items = ref([])
const query = ref({ q: '', sectionId: '', page: 1, size: 20 })
// 本地输入值：仅在触发搜索后同步到 query.q
const searchText = ref('')
const sections = ref([])
const { isLoggedIn } = useAuth()

function load() {
  // 修剪过期记录并加载
  try { pruneExpired() } catch (_) {}
  items.value = getAllRecentVisits()
}

async function loadSections() {
  try {
    const data = await listSections({ size: 200 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (_) {
    sections.value = []
  }
}

function clearAll() {
  clearAllRecentVisits()
  load()
}

// 安全返回：若直接通过地址栏进入或无站内来源，则跳转到发现页
function safeBack() {
  const ref = document.referrer || ''
  const sameOrigin = ref && ref.startsWith(location.origin)
  if (!sameOrigin || window.history.length <= 1) {
    router.replace({ name: 'discover' })
  } else {
    router.back()
  }
}

// 仅在点击搜索或按下回车时执行搜索
function applySearch() {
  query.value.q = String(searchText.value || '').trim()
  query.value.page = 1
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

// 过滤、排序与分页（参考我的帖子/我的评论）
const filteredItems = computed(() => {
  const q = String(query.value.q || '').trim().toLowerCase()
  const sid = String(query.value.sectionId || '')
  const src = Array.isArray(items.value) ? items.value.slice() : []
  const filtered = src.filter(it => {
    // 分区过滤（若选择了分区）
    if (sid) {
      const itemSid = String(it.sectionId == null ? '' : it.sectionId)
      if (itemSid !== sid) return false
    }
    // 关键词匹配：标题/名称/路径
    if (!q) return true
    const hay = ((it.title || '') + '\n' + (it.name || '') + '\n' + (it.path || '')).toLowerCase()
    return hay.includes(q)
  })
  // 按时间倒序
  filtered.sort((a, b) => Number(b.ts || 0) - Number(a.ts || 0))
  return filtered
})

const totalPages = computed(() => {
  const size = Number(query.value.size || 20)
  const pages = Math.ceil((filteredItems.value.length || 0) / (size || 20))
  return Math.max(1, pages || 1)
})

const pagedItems = computed(() => {
  const size = Number(query.value.size || 20)
  const page = Math.min(Math.max(1, Number(query.value.page || 1)), totalPages.value)
  const start = (page - 1) * size
  return filteredItems.value.slice(start, start + size)
})

function setPage(p) {
  const target = Math.min(Math.max(1, p), totalPages.value)
  query.value.page = target
}

function prevPage() { setPage((query.value.page || 1) - 1) }
function nextPage() { setPage((query.value.page || 1) + 1) }

onMounted(load)
onMounted(loadSections)
// 未登录则跳转走
onMounted(() => {
  if (!isLoggedIn.value) {
    router.replace({ name: 'discover' })
  }
})
watch(isLoggedIn, (v) => {
  if (!v) router.replace({ name: 'discover' })
})
</script>

<template>
  <div>
    <div v-if="isLoggedIn" class="rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="flex items-center justify-between border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">
        <div class="flex items-center gap-2">
          <!-- 返回箭头置于黑框标题栏左上角 -->
          <button @click="safeBack()" class="inline-flex items-center p-1 rounded text-brandDay-600 dark:text-brandNight-400 hover:bg-brandDay-50 dark:hover:bg-gray-700" aria-label="返回上一页" title="返回上一页">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5">
              <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
            </svg>
          </button>
          <span>历史记录</span>
        </div>
        <button class="text-xs text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200" @click="clearAll">清空</button>
      </div>

      <!-- 搜索与分区筛选（参考 我的帖子/我的评论） -->
      <div class="px-3 py-2 border-b dark:border-gray-700">
        <div class="flex items-center gap-2 text-xs">
          <input v-model="searchText" placeholder="关键词" @keyup.enter="applySearch" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700" />
          <button class="rounded px-2 py-1 border text-xs dark:border-gray-700" @click="applySearch">搜索</button>
          <div class="ml-auto flex items-center gap-2">
            <label class="text-xs text-gray-600 dark:text-gray-300">分区</label>
            <select v-model="query.sectionId" @change="query.page=1" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
              <option value="">全部</option>
              <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name || ('#' + s.id) }}</option>
            </select>
          </div>
        </div>
      </div>

      <ul class="p-2 space-y-1 text-sm">
        <li v-if="filteredItems.length === 0" class="text-xs text-gray-500 dark:text-gray-400">暂无记录</li>
        <li v-for="v in pagedItems" :key="(v.id ?? v.path)" class="flex items-center justify-between gap-2">
          <router-link :to="v.path" class="min-w-0 truncate rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700">
            {{ v.title || v.name || v.path }}
            <span v-if="v.sectionName || v.sectionId" class="ml-2 text-[10px] text-gray-500 dark:text-gray-400">分区：{{ v.sectionName || v.sectionId }}</span>
          </router-link>
          <span class="flex-none whitespace-nowrap text-[10px] text-gray-500 dark:text-gray-400">{{ formatRelative(v.ts) }}</span>
        </li>
      </ul>

      <!-- 分页控件：每页 20 条 -->
      <div class="mt-2 px-3 pb-3 flex items-center justify-between text-xs">
        <div class="text-gray-600 dark:text-gray-300">共 {{ filteredItems.length }} 条，每页 {{ query.size }} 条</div>
        <div class="flex items-center gap-2">
          <button
            class="rounded px-2 py-1 border dark:border-gray-700 dark:text-gray-200"
            :disabled="(query.page || 1) <= 1"
            @click="prevPage"
          >上一页</button>
          <span>第 {{ query.page || 1 }} / {{ totalPages }} 页</span>
          <button
            class="rounded px-2 py-1 border dark:border-gray-700 dark:text-gray-200"
            :disabled="(query.page || 1) >= totalPages"
            @click="nextPage"
          >下一页</button>
        </div>
      </div>
    </div>
    <div class="mt-3">
      <!-- 底部保留空白或后续操作入口，可根据需要扩展 -->
    </div>
  </div>
</template>

<style scoped>
</style>
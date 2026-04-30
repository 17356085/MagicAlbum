<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getAllRecentVisits, clearAllRecentVisits, pruneExpired, removeRecentVisit } from '@/composables/useRecentVisits'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { listSections } from '@/api/sections'
import { safeBack as navigateBackSafely } from '@/utils/router'
import type { RecentVisit, Section } from '@/types'

const router = useRouter()
interface RecentHistoryQuery {
  q: string
  sectionId: string
  page: number
  size: number
}

const items = ref<RecentVisit[]>([])
const query = ref<RecentHistoryQuery>({ q: '', sectionId: '', page: 1, size: 20 })
const inputPage = ref('1')
// 本地输入值：仅在触发搜索后同步到 query.q
const searchText = ref('')
const sections = ref<Section[]>([])
const authStore = useAuthStore()
const { isLoggedIn } = storeToRefs(authStore)

function load(): void {
  // 修剪过期记录并加载
  try { pruneExpired() } catch (_) {}
  items.value = getAllRecentVisits()
}

async function loadSections(): Promise<void> {
  try {
    const data = await listSections({ size: 200 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (_) {
    sections.value = []
  }
}

function clearAll(): void {
  clearAllRecentVisits()
  load()
}

function removeOne(item: RecentVisit): void {
  removeRecentVisit(item)
  load()
}

// 安全返回：若直接通过地址栏进入或无站内来源，则跳转到发现页
function safeBack(): void {
  navigateBackSafely(router)
}

// 仅在点击搜索或按下回车时执行搜索
function applySearch(): void {
  query.value.q = String(searchText.value || '').trim()
  query.value.page = 1
}

function formatRelative(ts: number | string | null | undefined): string {
  const diff = Date.now() - Number(ts || 0)
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  const d = Math.floor(h / 24)
  return `${d} 天前`
}

function formatVisitedAt(ts: number | string | null | undefined): string {
  const date = new Date(Number(ts || 0))
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}/${pad(date.getMonth() + 1)}/${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// 过滤、排序与分页（参考我的帖子/我的评论）
const filteredItems = computed<RecentVisit[]>(() => {
  const q = String(query.value.q || '').trim().toLowerCase()
  const sid = String(query.value.sectionId || '')
  const src = Array.isArray(items.value) ? items.value.slice() : []
  const filtered = src.filter((it) => {
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

const totalPages = computed<number>(() => {
  const size = Number(query.value.size || 20)
  const pages = Math.ceil((filteredItems.value.length || 0) / (size || 20))
  return Math.max(1, pages || 1)
})

const pagedItems = computed<RecentVisit[]>(() => {
  const size = Number(query.value.size || 20)
  const page = Math.min(Math.max(1, Number(query.value.page || 1)), totalPages.value)
  const start = (page - 1) * size
  return filteredItems.value.slice(start, start + size)
})

function setPage(p: number): void {
  const target = Math.min(Math.max(1, p), totalPages.value)
  query.value.page = target
}

function prevPage(): void { setPage((query.value.page || 1) - 1) }
function nextPage(): void { setPage((query.value.page || 1) + 1) }

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(query.value.page || 1)
    return
  }
  setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
}

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
watch(() => query.value.page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })
</script>

<template>
  <div>
    <div v-if="isLoggedIn" class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-gray-100 px-5 py-4 dark:border-gray-700">
        <div class="flex min-w-0 items-center gap-3">
          <button
            @click="safeBack()"
            class="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-gray-500 transition-colors hover:bg-gray-50 hover:text-brandDay-600 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-brandNight-400"
            aria-label="返回上一页"
            title="返回上一页"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5">
              <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
            </svg>
          </button>
          <div class="min-w-0">
            <h1 class="text-xl font-semibold text-gray-900 dark:text-gray-100">浏览记录</h1>
            <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">保留最近 90 天浏览过的帖子</p>
          </div>
        </div>
        <button
          class="rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-500 transition-colors hover:border-red-200 hover:bg-red-50 hover:text-red-500 dark:border-gray-700 dark:text-gray-400 dark:hover:border-red-900/60 dark:hover:bg-red-950/30 dark:hover:text-red-300"
          @click="clearAll"
        >
          清空
        </button>
      </div>

      <div class="border-b border-gray-100 px-5 py-4 dark:border-gray-700">
        <div class="flex flex-wrap items-end gap-3">
          <label class="min-w-[220px] flex-1">
            <span class="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">关键词</span>
            <input
              v-model="searchText"
              placeholder="搜索标题、路径"
              @keyup.enter="applySearch"
              class="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 focus:border-brandDay-500 focus:outline-none focus:ring-1 focus:ring-brandDay-500 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            />
          </label>
          <label class="w-full sm:w-48">
            <span class="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">分区</span>
            <select
              v-model="query.sectionId"
              @change="query.page=1"
              class="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-700 focus:border-brandDay-500 focus:outline-none focus:ring-1 focus:ring-brandDay-500 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            >
              <option value="">全部分区</option>
              <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name || ('#' + s.id) }}</option>
            </select>
          </label>
          <button
            class="rounded-lg bg-brandDay-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-500"
            @click="applySearch"
          >
            搜索
          </button>
        </div>
      </div>

      <div class="space-y-3 p-5">
        <div v-if="filteredItems.length === 0" class="rounded-lg border border-dashed border-gray-200 px-4 py-10 text-center text-sm text-gray-400 dark:border-gray-700">
          暂无浏览记录
        </div>
        <article
          v-for="v in pagedItems"
          :key="(v.id ?? v.path)"
          class="rounded-lg border border-gray-100 bg-white px-4 py-3 shadow-sm transition-colors hover:border-brandDay-200 hover:bg-brandDay-50/30 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700 dark:hover:bg-brandNight-950/20"
        >
          <div class="flex flex-wrap items-start justify-between gap-3">
            <router-link :to="v.path" class="min-w-0 flex-1">
              <h2 class="line-clamp-2 text-base font-semibold leading-6 text-gray-900 hover:text-brandDay-600 dark:text-gray-100 dark:hover:text-brandNight-300">
                {{ v.title || v.name || v.path }}
              </h2>
              <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
                <span v-if="v.sectionName || v.sectionId" class="rounded bg-gray-100 px-2 py-0.5 text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                  {{ v.sectionName || v.sectionId }}
                </span>
                <span>{{ formatRelative(v.ts) }}</span>
                <span v-if="formatVisitedAt(v.ts)">·</span>
                <span>{{ formatVisitedAt(v.ts) }}</span>
              </div>
              <div class="mt-2 truncate text-xs text-gray-400 dark:text-gray-500">{{ v.path }}</div>
            </router-link>
            <div class="flex shrink-0 items-center gap-2">
              <router-link
                :to="v.path"
                class="rounded-lg border border-gray-200 px-3 py-1.5 text-sm text-gray-600 transition-colors hover:border-brandDay-200 hover:bg-white hover:text-brandDay-600 dark:border-gray-700 dark:text-gray-300 dark:hover:border-brandNight-700 dark:hover:bg-gray-900 dark:hover:text-brandNight-300"
              >
                查看
              </router-link>
              <button
                class="rounded-lg border border-gray-200 px-3 py-1.5 text-sm text-gray-500 transition-colors hover:border-red-200 hover:bg-red-50 hover:text-red-500 dark:border-gray-700 dark:text-gray-400 dark:hover:border-red-900/60 dark:hover:bg-red-950/30 dark:hover:text-red-300"
                @click="removeOne(v)"
              >
                移除
              </button>
            </div>
          </div>
        </article>
      </div>

      <div class="flex flex-wrap items-center justify-between gap-3 border-t border-gray-100 px-5 py-4 dark:border-gray-700">
        <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ filteredItems.length }} 条 · 每页 {{ query.size }} 条</div>
        <div class="flex items-center gap-2">
          <button
            class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
            :disabled="(query.page || 1) <= 1"
            @click="prevPage"
          >上一页</button>
          <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
          <input
            v-model="inputPage"
            class="w-16 rounded border bg-white px-2 py-1 text-center text-sm focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:placeholder-gray-400 dark:focus:ring-accentCyan-400"
            inputmode="numeric"
            @keyup.enter="goToInputPage"
            @blur="goToInputPage"
          />
          <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ totalPages }} 页</span>
          <button
            class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
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

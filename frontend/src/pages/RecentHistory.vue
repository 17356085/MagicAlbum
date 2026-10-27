<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  captureRecentVisitsScope,
  clearAllRecentVisits,
  getAllRecentVisits,
  isRecentVisitsScopeCurrent,
  pruneExpired,
  removeRecentVisit,
} from '@/composables/useRecentVisits'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { listSections } from '@/api/sections'
import { safeBack as navigateBackSafely } from '@/utils/router'
import type { RecentVisit, Section } from '@/types'
import type { RecentVisitsIdentity, RecentVisitsScope } from '@/composables/useRecentVisits'

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
const displayedScope = ref<RecentVisitsScope | null>(null)
const scopeError = ref(false)
const authStore = useAuthStore()
const { isLoggedIn } = storeToRefs(authStore)
const hasValidDisplayedScope = computed(() => {
  const scope = displayedScope.value
  return !!scope && !scopeError.value && isRecentVisitsScopeCurrent(scope, currentIdentity())
})

function currentIdentity(): RecentVisitsIdentity {
  return {
    token: String(authStore.token || ''),
    ownerId: authStore.user?.id ?? authStore.user?.userId ?? null,
  }
}

function clearScopeView(showError: boolean): void {
  items.value = []
  displayedScope.value = null
  scopeError.value = showError
}

function load(): void {
  // 修剪过期记录并加载
  if (!isLoggedIn.value) {
    clearScopeView(false)
    return
  }

  const expected = currentIdentity()
  const scope = captureRecentVisitsScope(expected)
  if (!scope) {
    clearScopeView(true)
    return
  }

  try { pruneExpired(scope) } catch (_) {}
  if (!isRecentVisitsScopeCurrent(scope, currentIdentity())) {
    clearScopeView(true)
    return
  }

  const nextItems = getAllRecentVisits(scope)
  if (!isRecentVisitsScopeCurrent(scope, currentIdentity())) {
    clearScopeView(true)
    return
  }

  displayedScope.value = scope
  items.value = nextItems
  scopeError.value = false
}

async function loadSections(): Promise<void> {
  try {
    const data = await listSections({ size: 200 })
    sections.value = data.items
  } catch (_) {
    sections.value = []
  }
}

function clearAll(): void {
  const scope = displayedScope.value
  if (!scope || !isRecentVisitsScopeCurrent(scope, currentIdentity())) {
    clearScopeView(true)
    return
  }
  clearAllRecentVisits(scope)
  load()
}

function removeOne(item: RecentVisit): void {
  const scope = displayedScope.value
  if (!scope || !isRecentVisitsScopeCurrent(scope, currentIdentity())) {
    clearScopeView(true)
    return
  }
  removeRecentVisit(item, scope)
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

function onAuthStateChanged(): void {
  query.value.page = 1
  inputPage.value = '1'

  if (!isLoggedIn.value) {
    clearScopeView(false)
    router.replace({ name: 'discover' })
    return
  }

  load()
}

onMounted(load)
onMounted(loadSections)
// 未登录则跳转走
onMounted(() => {
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  if (!isLoggedIn.value) {
    router.replace({ name: 'discover' })
  }
})
watch(isLoggedIn, (v) => {
  if (!v) {
    clearScopeView(false)
    router.replace({ name: 'discover' })
  }
})
watch([() => authStore.token, () => authStore.user?.id ?? authStore.user?.userId], () => {
  query.value.page = 1
  inputPage.value = '1'
  if (isLoggedIn.value) load()
})
watch(() => query.value.page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

onBeforeUnmount(() => {
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

<template>
  <section class="ui-page">
    <section v-if="isLoggedIn" class="ui-panel overflow-hidden">
      <header class="ui-page-header border-b border-[var(--ui-border)] px-4 py-4 sm:px-6">
        <div class="flex min-w-0 items-center gap-3">
          <button type="button" class="ui-button shrink-0 px-3" @click="safeBack()" aria-label="返回上一页" title="返回上一页">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
              <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
            </svg>
          </button>
          <div class="min-w-0">
            <h1 class="ui-title">本机浏览记录</h1>
            <p class="ui-description">保留最近 90 天浏览过的帖子</p>
          </div>
        </div>
        <button type="button" class="ui-button text-red-600 hover:border-red-300 hover:bg-red-50 dark:text-red-300 dark:hover:bg-red-950/30" :disabled="!hasValidDisplayedScope" @click="clearAll">清空</button>
      </header>

      <div v-if="scopeError" class="ui-empty" role="alert">
        <h2>账号状态已变化</h2>
        <p>浏览记录暂不可用，请重新加载。</p>
        <button type="button" class="ui-button" @click="load">重新加载</button>
      </div>

      <template v-else>
      <div class="ui-toolbar items-end">
        <label class="min-w-[220px] flex-1">
          <span class="mb-1 block text-xs font-medium text-[var(--ui-muted)]">关键词</span>
          <input v-model="searchText" class="ui-field" placeholder="搜索标题、路径" aria-label="搜索标题或路径" @keyup.enter="applySearch" />
        </label>
        <label class="w-full sm:w-48">
          <span class="mb-1 block text-xs font-medium text-[var(--ui-muted)]">分区</span>
          <select v-model="query.sectionId" class="ui-field" aria-label="按分区筛选" @change="query.page=1">
            <option value="">全部分区</option>
            <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name || ('#' + s.id) }}</option>
          </select>
        </label>
        <button type="button" class="ui-button-primary" @click="applySearch">搜索</button>
      </div>

      <div>
        <div v-if="filteredItems.length === 0" class="ui-empty" role="status">
          <h2>暂无浏览记录</h2>
          <p>打开过的帖子会按时间显示在这里。</p>
        </div>
        <ul v-else>
          <li v-for="v in pagedItems" :key="(v.id ?? v.path)" class="ui-list-row">
            <div class="flex flex-wrap items-start justify-between gap-3">
              <router-link :to="v.path" class="min-w-0 flex-1">
                <h2 class="line-clamp-2 text-base font-semibold leading-6 text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">{{ v.title || v.name || v.path }}</h2>
                <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-[var(--ui-muted)]">
                  <span v-if="v.sectionName || v.sectionId" class="ui-tag">{{ v.sectionName || v.sectionId }}</span>
                  <span>{{ formatRelative(v.ts) }}</span>
                  <span v-if="formatVisitedAt(v.ts)">·</span>
                  <span>{{ formatVisitedAt(v.ts) }}</span>
                </div>
                <div class="mt-2 truncate text-xs text-[var(--ui-muted)]">{{ v.path }}</div>
              </router-link>
              <div class="flex shrink-0 items-center gap-2">
                <router-link :to="v.path" class="ui-button">查看</router-link>
                <button type="button" class="ui-button text-red-600 hover:border-red-300 hover:bg-red-50 dark:text-red-300 dark:hover:bg-red-950/30" @click="removeOne(v)">移除</button>
              </div>
            </div>
          </li>
        </ul>
      </div>

      <footer class="flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 text-sm sm:flex-row sm:items-center sm:justify-between sm:px-6">
        <div class="text-xs text-[var(--ui-muted)]">共 {{ filteredItems.length }} 条 · 每页 {{ query.size }} 条</div>
        <div class="flex flex-wrap items-center gap-2">
          <button type="button" class="ui-button" :disabled="(query.page || 1) <= 1" @click="prevPage">上一页</button>
          <span class="text-sm text-[var(--ui-muted)]">第</span>
          <input v-model="inputPage" aria-label="页码" class="ui-field w-16 text-center" inputmode="numeric" @keyup.enter="goToInputPage" @blur="goToInputPage" />
          <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
          <button type="button" class="ui-button" :disabled="(query.page || 1) >= totalPages" @click="nextPage">下一页</button>
        </div>
      </footer>
      </template>
    </section>
  </section>
</template>

<style scoped>
</style>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listUsers } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { getSingleQueryValue } from '@/utils/router'
import { formatAbsoluteDate } from '@/composables/time'
import type { User } from '@/types'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const items = ref<User[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const q = ref('')
const inputPage = ref('1')
const totalPages = computed(() => Math.max(1, Math.ceil((Number(total.value) || 0) / (Number(size.value) || 20))))
let loadRequestSeq = 0
let disposed = false

type RouteTarget = {
  q: string
  page: number
  key: string
}

type LoadRecord = {
  requestSeq: number
  target: RouteTarget
  promise: Promise<void>
}

type NavigationAttempt = {
  routeKey: string
  fromPage: number
  targetPage: number
}

let activeLoad: LoadRecord | null = null
let pendingNavigation: NavigationAttempt | null = null

function isCurrentLoad(requestSeq: number): boolean {
  return !disposed && requestSeq === loadRequestSeq
}

function readRouteTarget(): RouteTarget {
  const rq = route.query.q == null ? '' : getSingleQueryValue(route.query.q)
  const rawPage = route.query.page == null ? 1 : Number(getSingleQueryValue(route.query.page))
  const normalizedPage = Number.isSafeInteger(rawPage) && rawPage > 0 ? rawPage : 1
  return {
    q: rq,
    page: normalizedPage,
    key: JSON.stringify([rq, normalizedPage]),
  }
}

function sameLoadTarget(left: RouteTarget, right: RouteTarget): boolean {
  return left.key === right.key
}

async function executeLoad(target: RouteTarget, requestSeq: number): Promise<void> {
  loading.value = true
  error.value = ''
  q.value = target.q
  page.value = target.page
  try {
    const data = await listUsers({ q: target.q, page: target.page, size: size.value, fields: 'username,nickname' })
    if (!isCurrentLoad(requestSeq)) return
    items.value = data.items
    total.value = data.total
    page.value = data.page
    size.value = data.size
  } catch (_) {
    if (isCurrentLoad(requestSeq)) error.value = '加载用户失败'
  } finally {
    if (isCurrentLoad(requestSeq)) loading.value = false
    if (activeLoad?.requestSeq === requestSeq) activeLoad = null
  }
}

function load(): Promise<void> {
  if (disposed) return Promise.resolve()
  const target = readRouteTarget()
  if (loading.value && activeLoad && sameLoadTarget(activeLoad.target, target)) {
    return activeLoad.promise
  }

  const requestSeq = ++loadRequestSeq
  const record: LoadRecord = {
    requestSeq,
    target,
    promise: Promise.resolve(),
  }
  activeLoad = record
  record.promise = executeLoad(target, requestSeq)
  return record.promise
}

const showPagination = computed(() => !loading.value && !error.value && (items.value.length > 0 || page.value > 1))

function setPage(p: number) {
  if (disposed || loading.value || !Number.isSafeInteger(p) || p <= 0) return

  const routeTarget = readRouteTarget()
  const currentPage = Number.isSafeInteger(page.value) && page.value > 0 ? page.value : routeTarget.page
  const next = Math.min(Math.max(1, p), totalPages.value)
  if (!Number.isSafeInteger(next) || next === currentPage) return

  if (
    pendingNavigation
    && pendingNavigation.routeKey === routeTarget.key
    && pendingNavigation.fromPage === currentPage
  ) return

  const attempt: NavigationAttempt = {
    routeKey: routeTarget.key,
    fromPage: currentPage,
    targetPage: next,
  }
  pendingNavigation = attempt

  let pushResult: unknown
  try {
    pushResult = router.push({ name: 'users', query: { ...route.query, page: next } })
  } catch (_) {
    if (pendingNavigation === attempt) pendingNavigation = null
    return
  }

  void Promise.resolve(pushResult).then(
    (result) => {
      if (pendingNavigation !== attempt) return
      // A successful push is confirmed by the route watcher. A resolved
      // navigation failure must release the pending target so it can be retried.
      if (result !== undefined) {
        pendingNavigation = null
      }
    },
    () => {
      if (pendingNavigation !== attempt) return
      pendingNavigation = null
    },
  )
}

function prevPage() {
  setPage(Number(page.value) - 1)
}

function nextPage() {
  setPage(Number(page.value) + 1)
}

function goToInputPage(): void {
  if (disposed || loading.value) return
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  const parsed = Number(raw)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    inputPage.value = String(page.value || 1)
    return
  }
  setPage(Math.min(Math.max(1, parsed), totalPages.value))
}

onUnmounted(() => {
  disposed = true
  loadRequestSeq += 1
  activeLoad = null
  pendingNavigation = null
})
// A search navigation updates both query and page. Observe them together so
// Vue batches that navigation into one request.
watch([() => route.query.q, () => route.query.page], () => {
  const target = readRouteTarget()
  if (pendingNavigation && (target.key !== pendingNavigation.routeKey || target.page === pendingNavigation.targetPage)) {
    pendingNavigation = null
  }
  void load()
}, { immediate: true })
watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })
</script>

<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">搜索用户</h1>
        <p class="ui-description">按昵称或用户名找到社区里的成员。</p>
      </div>
      <span v-if="!loading && !error && total" class="ui-tag">共 {{ total }} 位用户</span>
    </header>

    <div v-if="q" class="ui-toolbar mb-4 rounded-lg border border-[var(--ui-border)]">
      <span class="text-sm text-[var(--ui-muted)]">搜索关键字：</span>
      <span class="ui-tag">{{ q }}</span>
    </div>

    <div v-if="loading" class="ui-panel ui-empty" role="status">
      <div class="ui-skeleton mx-auto mb-3 h-8 w-8 rounded-full"></div>
      <div>正在加载...</div>
    </div>

    <div v-else>
      <div v-if="error" class="ui-error flex items-center justify-between gap-3" role="alert">
        <span>{{ error }}</span>
        <button type="button" class="ui-button" @click="load">重试</button>
      </div>
      
      <div v-else-if="items.length === 0" class="ui-panel ui-empty" role="status">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-16 w-16 mx-auto mb-4 text-gray-300 dark:text-gray-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
        </svg>
        <h2>{{ page > 1 ? '当前页暂无用户' : '暂无匹配用户' }}</h2>
        <p>{{ page > 1 ? '可返回上一页查看。' : '换个关键词试试。' }}</p>
      </div>
      
      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <div v-for="u in items" :key="u.id"
             class="ui-panel group relative flex flex-col items-center p-6">
          
          <router-link :to="'/users/' + u.id" class="absolute inset-0 z-10" :aria-label="`查看 ${u.nickname || u.username || '用户'} 的主页`"></router-link>
          
          <!-- Avatar -->
          <div class="relative mb-4">
            <img v-if="u.avatarUrl" :src="normalizeImageUrl(u.avatarUrl)" alt="avatar" 
                 class="h-20 w-20 rounded-full border-4 border-[var(--ui-subtle)] object-cover" />
            <div v-else class="flex h-20 w-20 items-center justify-center rounded-full bg-[var(--ui-subtle)] text-2xl font-bold text-[var(--ui-muted)]">
              {{ String(u.nickname || u.username || 'U').slice(0,1).toUpperCase() }}
            </div>
          </div>
          
          <!-- Info -->
          <div class="w-full text-center">
            <h3 class="mb-0.5 truncate px-2 text-lg font-bold text-[var(--ui-ink)]">
              {{ u.nickname || u.username }}
            </h3>
            <div class="mb-3 truncate px-2 text-sm text-[var(--ui-muted)]">
              @{{ u.username }}
            </div>
            
            <div class="mb-3 h-px w-full bg-[var(--ui-border)]"></div>
            
            <div class="text-xs text-[var(--ui-muted)]">
              注册于 {{ formatAbsoluteDate(u.createdAt) || '未知日期' }}
            </div>
          </div>
          
          <!-- Hover Effect: View Profile Button (Visual cue) -->
          <div class="pointer-events-none mt-4">
             <span class="ui-tag">查看主页</span>
          </div>
        </div>
      </div>
      
      <!-- Pagination -->
      <div v-if="showPagination" class="mt-6 flex flex-col gap-3 border-t border-[var(--ui-border)] pt-4 sm:flex-row sm:items-center sm:justify-between">
        <div class="text-xs text-[var(--ui-muted)]">共 {{ total }} 条 · 每页 {{ size }} 条</div>
        <div class="flex flex-wrap items-center gap-2">
        <button 
          class="ui-button"
          :disabled="page<=1" 
          @click="prevPage"
        >
          上一页
        </button>
        <span class="text-sm text-[var(--ui-muted)]">第</span>
        <input
          v-model="inputPage"
          class="ui-field w-16 text-center"
          aria-label="页码"
          inputmode="numeric"
          @keyup.enter="goToInputPage"
          @blur="goToInputPage"
        />
        <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
        <button 
          class="ui-button"
          :disabled="page >= totalPages"
          @click="nextPage"
        >
          下一页
        </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
</style>

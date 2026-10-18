<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
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

function isCurrentLoad(requestSeq: number): boolean {
  return !disposed && requestSeq === loadRequestSeq
}

async function load(): Promise<void> {
  const requestSeq = ++loadRequestSeq
  loading.value = true
  error.value = ''
  try {
    const rq = route.query.q ? getSingleQueryValue(route.query.q) : ''
    const rp = route.query.page ? Number(getSingleQueryValue(route.query.page)) : 1
    page.value = isNaN(rp) ? 1 : rp
    q.value = rq
    const data = await listUsers({ q: rq, page: page.value, size: size.value })
    if (!isCurrentLoad(requestSeq)) return
    items.value = data.items
    total.value = data.total
    page.value = data.page
    size.value = data.size
  } catch (_) {
    if (isCurrentLoad(requestSeq)) error.value = '加载用户失败'
  } finally {
    if (isCurrentLoad(requestSeq)) loading.value = false
  }
}

const filteredItems = computed(() => {
  const kw = String(q.value || '').trim().toLowerCase()
  const src = items.value
  if (!kw) return src
  return src.filter(u => {
    const nameOk = String(u.username || '').toLowerCase().includes(kw)
    const nickOk = String(u.nickname || '').toLowerCase().includes(kw)
    return nameOk || nickOk
  })
})

function setPage(p: number) {
  const next = Math.min(Math.max(1, p), totalPages.value)
  page.value = next
  router.push({ name: 'users', query: { ...route.query, page: next } })
}

function prevPage() {
  setPage(Number(page.value) - 1)
}

function nextPage() {
  setPage(Number(page.value) + 1)
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
}

onMounted(load)
onUnmounted(() => {
  disposed = true
  loadRequestSeq += 1
})
watch(() => route.query.page, load)
watch(() => route.query.q, () => {
  page.value = 1
  load()
})
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
      <span v-if="total" class="ui-tag">共 {{ total }} 位用户</span>
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
      <div v-if="error" class="ui-error" role="alert">{{ error }}</div>
      
      <div v-else-if="filteredItems.length === 0" class="ui-panel ui-empty" role="status">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-16 w-16 mx-auto mb-4 text-gray-300 dark:text-gray-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
        </svg>
        <h2>暂无匹配用户</h2>
        <p>换个关键词试试。</p>
      </div>
      
      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        <div v-for="u in filteredItems" :key="u.id" 
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
      <div v-if="filteredItems.length > 0" class="mt-6 flex flex-col gap-3 border-t border-[var(--ui-border)] pt-4 sm:flex-row sm:items-center sm:justify-between">
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

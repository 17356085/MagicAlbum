<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listUsers } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { getSingleQueryValue } from '@/utils/router'
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const rq = route.query.q ? getSingleQueryValue(route.query.q) : ''
    const rp = route.query.page ? Number(getSingleQueryValue(route.query.page)) : 1
    page.value = isNaN(rp) ? 1 : rp
    q.value = rq
    const data = await listUsers({ q: rq, page: page.value, size: size.value })
    items.value = Array.isArray(data.items) ? data.items : []
    total.value = Number(data.total || items.value.length || 0)
    page.value = Number(data.page || page.value)
    size.value = Number(data.size || size.value)
  } catch (_) {
    error.value = '加载用户失败'
  } finally {
    loading.value = false
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
  <div class="max-w-6xl mx-auto px-4 py-6">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-2xl font-bold text-gray-800 dark:text-gray-100">搜索用户</h1>
      <div class="text-sm text-gray-500 dark:text-gray-400" v-if="total">共 {{ total }} 位用户</div>
    </div>
    
    <div v-if="q" class="mb-4 text-sm text-gray-600 dark:text-gray-300">
      搜索关键字：<span class="font-semibold text-brandDay-600 dark:text-brandNight-400">{{ q }}</span>
    </div>

    <div v-if="loading" class="py-12 text-center text-gray-500 dark:text-gray-400">
      <div class="inline-block animate-spin rounded-full h-8 w-8 border-4 border-gray-300 border-t-brandDay-500 mb-2"></div>
      <div>正在加载...</div>
    </div>
    
    <div v-else>
      <div v-if="error" class="p-4 rounded-lg bg-red-50 text-red-600 dark:bg-red-900/20 dark:text-red-400">{{ error }}</div>
      
      <div v-else-if="filteredItems.length === 0" class="py-12 text-center text-gray-500 dark:text-gray-400">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-16 w-16 mx-auto mb-4 text-gray-300 dark:text-gray-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0zm6 3a2 2 0 11-4 0 2 2 0 014 0zM7 10a2 2 0 11-4 0 2 2 0 014 0z" />
        </svg>
        暂无匹配用户
      </div>
      
      <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        <div v-for="u in filteredItems" :key="u.id" 
             class="group relative flex flex-col items-center p-6 rounded-xl border border-gray-100 bg-white shadow-sm hover:shadow-md hover:border-brandDay-200 dark:bg-gray-800 dark:border-gray-700 dark:hover:border-brandNight-700 transition-all duration-300">
          
          <router-link :to="'/users/' + u.id" class="absolute inset-0 z-10" aria-label="查看用户详情"></router-link>
          
          <!-- Avatar -->
          <div class="relative mb-4">
            <img v-if="u.avatarUrl" :src="normalizeImageUrl(u.avatarUrl)" alt="avatar" 
                 class="w-20 h-20 rounded-full object-cover border-4 border-gray-50 dark:border-gray-700 group-hover:scale-105 transition-transform duration-300" />
            <div v-else class="w-20 h-20 rounded-full bg-gradient-to-br from-gray-100 to-gray-200 dark:from-gray-700 dark:to-gray-800 flex items-center justify-center text-2xl font-bold text-gray-400 dark:text-gray-500 group-hover:scale-105 transition-transform duration-300">
              {{ String(u.nickname || u.username || 'U').slice(0,1).toUpperCase() }}
            </div>
          </div>
          
          <!-- Info -->
          <div class="text-center w-full">
            <h3 class="text-lg font-bold text-gray-800 dark:text-gray-100 mb-0.5 truncate px-2">
              {{ u.nickname || u.username }}
            </h3>
            <div class="text-sm text-gray-500 dark:text-gray-400 mb-3 truncate px-2">
              @{{ u.username }}
            </div>
            
            <div class="w-full h-px bg-gray-100 dark:bg-gray-700 mb-3"></div>
            
            <div class="text-xs text-gray-400 dark:text-gray-500">
              注册于 {{ new Date(u.createdAt).toLocaleDateString() }}
            </div>
          </div>
          
          <!-- Hover Effect: View Profile Button (Visual cue) -->
          <div class="absolute bottom-4 opacity-0 transform translate-y-2 group-hover:opacity-100 group-hover:translate-y-0 transition-all duration-300 z-20 pointer-events-none">
             <span class="px-3 py-1 rounded-full bg-brandDay-50 text-brandDay-600 text-xs font-medium dark:bg-brandNight-900 dark:text-brandNight-300">查看主页</span>
          </div>
        </div>
      </div>
      
      <!-- Pagination -->
      <div v-if="filteredItems.length > 0" class="mt-8 flex items-center justify-between">
        <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ total }} 条 · 每页 {{ size }} 条</div>
        <div class="flex items-center gap-2">
        <button 
          class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
          :disabled="page<=1" 
          @click="prevPage"
        >
          上一页
        </button>
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
          :disabled="page >= totalPages"
          @click="nextPage"
        >
          下一页
        </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>

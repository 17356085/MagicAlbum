<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listUsers } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const items = ref([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const q = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const rq = route.query.q ? String(route.query.q) : ''
    const rp = route.query.page ? Number(route.query.page) : 1
    page.value = isNaN(rp) ? 1 : rp
    q.value = rq
    // 后端已更新为返回 UserSummaryDto，包含 nickname 和 avatarUrl
    const data = await listUsers({ q: rq, page: page.value, size: size.value })
    const base = Array.isArray(data?.items) ? data.items : (Array.isArray(data) ? data : [])
    items.value = base
    total.value = Number(data?.total || (items.value?.length || 0))
    page.value = Number(data?.page || page.value)
    size.value = Number(data?.size || size.value)
  } catch (e) {
    error.value = '加载用户失败'
  } finally {
    loading.value = false
  }
}

// 仅支持用户名或昵称匹配的过滤列表
const filteredItems = computed(() => {
  const kw = String(q.value || '').trim().toLowerCase()
  const src = Array.isArray(items.value) ? items.value : []
  if (!kw) return src
  return src.filter(u => {
    const nameOk = String(u?.username || '').toLowerCase().includes(kw)
    const nickOk = String(u?.nickname || '').toLowerCase().includes(kw)
    return nameOk || nickOk
  })
})

function setPage(p) {
  const totalPages = Math.max(1, Math.ceil((Number(total.value)||0) / (Number(size.value)||20)))
  const next = Math.min(Math.max(1, p), totalPages)
  page.value = next
  router.push({ name: 'users', query: { ...route.query, page: next } })
}

function prevPage() { setPage(Number(page.value) - 1) }
function nextPage() { setPage(Number(page.value) + 1) }

onMounted(load)
watch(() => route.query.page, load)
watch(() => route.query.q, () => { page.value = 1; load() })
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
      <div v-if="filteredItems.length > 0" class="mt-8 flex items-center justify-center gap-4">
        <button 
          class="flex items-center gap-1 px-4 py-2 rounded-lg border border-gray-200 bg-white text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed dark:bg-gray-800 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700" 
          :disabled="page<=1" 
          @click="prevPage"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
          </svg>
          上一页
        </button>
        <span class="text-sm text-gray-600 dark:text-gray-400">第 {{ page }} 页</span>
        <button 
          class="flex items-center gap-1 px-4 py-2 rounded-lg border border-gray-200 bg-white text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed dark:bg-gray-800 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700" 
          :disabled="(page*size)>=total" 
          @click="nextPage"
        >
          下一页
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
</style>

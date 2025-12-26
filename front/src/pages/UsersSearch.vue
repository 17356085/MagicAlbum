<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listUsers } from '@/api/users'

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
    const data = await listUsers({ q: rq, page: page.value, size: size.value })
    items.value = Array.isArray(data) ? data : (data.items || [])
    if (!Array.isArray(data)) {
      total.value = Number(data.total || 0)
      page.value = Number(data.page || page.value)
      size.value = Number(data.size || size.value)
    }
  } catch (e) {
    error.value = '加载用户失败'
  } finally {
    loading.value = false
  }
}

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
  <div>
    <h1 class="text-lg font-semibold mb-3">搜索用户</h1>
    <div v-if="q" class="mb-2 text-xs text-gray-600 dark:text-gray-300">关键字：{{ q }}</div>
    <div v-if="loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-if="items.length === 0" class="text-gray-600 dark:text-gray-300">暂无匹配用户</div>
      <template v-else>
        <ul class="space-y-2">
          <li v-for="u in items" :key="u.id" class="rounded-md border border-gray-200 bg-white p-3 dark:bg-gray-800 dark:border-gray-700">
            <div class="flex items-center justify-between">
              <div>
                <div class="text-sm font-medium">{{ u.username }}</div>
                <div class="text-xs text-gray-500 dark:text-gray-400">{{ u.email }}</div>
              </div>
              <span class="text-xs text-gray-400 dark:text-gray-500">#{{ u.id }}</span>
            </div>
          </li>
        </ul>
        <div class="mt-4 flex items中心 justify-between text-xs text-gray-600 dark:text-gray-300">
          <div>总数：{{ total }} · 每页：{{ size }}</div>
          <div class="flex items-center gap-2">
            <button class="rounded px-2 py-1 border dark:border-gray-700 dark:text-gray-200" :disabled="page<=1" @click="prevPage">上一页</button>
            <span>第 {{ page }} 页</span>
            <button class="rounded px-2 py-1 border dark:border-gray-700 dark:text-gray-200" :disabled="(page*size)>=total" @click="nextPage">下一页</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
</style>
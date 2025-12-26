<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { listSections } from '@/api/sections'

const open = ref(false)
const loading = ref(false)
const error = ref('')
const sections = ref([])
const container = ref(null)

function toggle() { open.value = !open.value }
function close() { open.value = false }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listSections({ size: 100 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (e) {
    error.value = '分区加载失败'
  } finally {
    loading.value = false
  }
}

function onDocClick(e) {
  if (!container.value) return
  if (!container.value.contains(e.target)) close()
}

onMounted(() => {
  load()
  document.addEventListener('click', onDocClick)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
})
</script>

<template>
  <div ref="container" class="relative">
    <button
      class="rounded px-3 py-1 hover:bg-gray-100 inline-flex items-center gap-1"
      @click="toggle"
      aria-haspopup="true"
      :aria-expanded="open"
    >
      导航
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4"><path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 10.94l3.71-3.71a.75.75 0 011.08 1.04l-4.25 4.25a.75.75 0 01-1.08 0L5.21 8.27a.75.75 0 01.02-1.06z" clip-rule="evenodd"/></svg>
    </button>
    <div v-show="open" class="absolute right-0 top-full z-50 mt-2 w-80 max-h-72 overflow-auto rounded-md border border-gray-200 bg-white shadow dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium">分区列表</div>
      <div v-if="loading" class="p-3 text-sm text-gray-600">加载中...</div>
      <div v-else-if="error" class="p-3 text-sm text-red-600">{{ error }}</div>
      <ul v-else class="p-2 grid grid-cols-1 sm:grid-cols-2 gap-1 text-sm">
        <li v-for="s in sections" :key="s.id">
          <router-link :to="{ name: 'discover', query: { sectionId: s.id } }" class="block truncate rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700">
            {{ s.name || s.title }}
            <span v-if="s.slug" class="ml-1 text-[10px] text-gray-500">/{{ s.slug }}</span>
          </router-link>
        </li>
      </ul>
    </div>
  </div>
</template>
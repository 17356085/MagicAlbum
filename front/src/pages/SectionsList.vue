<script setup>
import { ref, onMounted } from 'vue'
import { listSections } from '@/api/sections'

const loading = ref(false)
const error = ref('')
const sections = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listSections({})
    const items = Array.isArray(data) ? data : (data.items || [])
    sections.value = items
  } catch (e) {
    error.value = '加载分区失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>

    <div v-if="loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-if="sections.length === 0" class="text-gray-600 dark:text-gray-300">暂无分区</div>
      <template v-else>
        <ul class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <li v-for="s in sections" :key="s.id" class="rounded-md border border-gray-200 bg-white hover:border-blue-300 transition dark:bg-gray-800 dark:border-gray-700">
            <router-link :to="{ name: 'discover', query: { sectionId: s.id } }" class="block p-4">
              <div class="flex items-center justify-between">
                <h2 class="text-lg font-medium">{{ s.name || s.title }}</h2>
                <span class="text-xs text-gray-500 dark:text-gray-400">{{ s.slug }}</span>
              </div>
              <p class="mt-2 text-sm text-gray-700 line-clamp-3 dark:text-gray-200">{{ s.description }}</p>
            </router-link>
          </li>
        </ul>
      </template>
    </div>
  </div>
</template>

<style scoped>
</style>
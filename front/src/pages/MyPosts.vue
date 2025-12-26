<script setup>
import { ref, onMounted } from 'vue'
import { listMyPosts, deletePost } from '@/api/my'
import { formatRelativeTime } from '@/composables/time'

const query = ref({ q: '', page: 1, size: 20, sort: 'createdAt' })
const loading = ref(false)
const error = ref('')
const list = ref({ items: [], page: 1, size: 20, total: 0 })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listMyPosts({ q: query.value.q, page: query.value.page, size: query.value.size, sort: query.value.sort })
    list.value = data
  } catch (e) {
    error.value = '加载我的评论失败'
  } finally { loading.value = false }
}

async function remove(id) {
  if (!confirm('确定删除该评论？')) return
  try {
    await deletePost(id)
    await load()
  } catch (e) {}
}

onMounted(load)
</script>

<template>
  <div class="rounded-md border border-gray-200 bg-white p-4 dark:bg-gray-800 dark:border-gray-700">
    <div class="text-sm font-medium mb-2">我的评论</div>
    <div class="flex items-center gap-2 text-xs mb-2">
      <input v-model="query.q" placeholder="关键词" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700" />
      <select v-model="query.sort" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
        <option value="createdAt">按创建</option>
        <option value="updatedAt">按更新</option>
      </select>
      <button class="rounded px-2 py-1 border text-xs dark:border-gray-700" @click="query.page=1; load()">搜索</button>
    </div>
    <div v-if="loading" class="text-xs text-gray-500">正在加载...</div>
    <div v-else-if="error" class="text-xs text-red-600">{{ error }}</div>
    <ul class="space-y-3">
      <li v-for="item in (list.items||[])" :key="item.id" class="rounded border p-3 dark:border-gray-700">
        <div class="text-xs text-gray-600 dark:text-gray-300">
          <span class="mr-2">#{{ item.id }}</span>
          <span>发布于：{{ formatRelativeTime(item.createdAt) }}</span>
          <span class="ml-2" v-if="item.updatedAt">更新：{{ formatRelativeTime(item.updatedAt) }}</span>
        </div>
        <div class="mt-2 text-sm">{{ item.content }}</div>
        <div class="mt-2 text-xs">
          所属帖子：
          <router-link :to="'/threads/' + item.threadId" class="hover:underline">{{ item.threadTitle || item.threadId }}</router-link>
        </div>
        <div class="mt-2 flex items-center gap-2 text-xs">
          <button class="rounded px-2 py-1 border dark:border-gray-700" @click="remove(item.id)">删除</button>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
</style>
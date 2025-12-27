<script setup>
import { ref, onMounted } from 'vue'
import { listMyPosts, deletePost } from '@/api/my'
import { listSections } from '@/api/sections'
import { formatRelativeTime } from '@/composables/time'
import ConfirmDialog from '@/components/ConfirmDialog.vue'

const query = ref({ q: '', sectionId: '', page: 1, size: 20, sort: 'createdAt' })
const loading = ref(false)
const error = ref('')
const list = ref({ items: [], page: 1, size: 20, total: 0 })
const sections = ref([])
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const pendingDeleteId = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listMyPosts({ q: query.value.q, sectionId: query.value.sectionId || undefined, page: query.value.page, size: query.value.size, sort: query.value.sort })
    list.value = data
  } catch (e) {
    error.value = '加载我的评论失败'
  } finally { loading.value = false }
}

async function loadSections() {
  try {
    const data = await listSections({ size: 200 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (e) {
    // 保持空列表即可
  }
}

function askRemove(id) {
  pendingDeleteId.value = id
  showDeleteConfirm.value = true
}

async function confirmDelete() {
  if (!pendingDeleteId.value) {
    showDeleteConfirm.value = false
    return
  }
  deleting.value = true
  try {
    await deletePost(pendingDeleteId.value)
    showDeleteConfirm.value = false
    pendingDeleteId.value = null
    await load()
  } catch (e) {
    // 可根据需要设置错误消息
  } finally {
    deleting.value = false
  }
}

function cancelDelete() {
  showDeleteConfirm.value = false
  pendingDeleteId.value = null
}

onMounted(async () => { await loadSections(); await load() })
</script>

<template>
  <div class="rounded-md border border-gray-200 bg-white p-4 dark:bg-gray-800 dark:border-gray-700">
    <div class="text-sm font-medium mb-2">我的评论</div>
    <div class="flex items-center gap-2 text-xs mb-2">
      <input v-model="query.q" placeholder="关键词" @keyup.enter="query.page=1; load()" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700" />
      <select v-model="query.sort" @change="query.page=1; load()" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
        <option value="createdAt">按创建</option>
        <option value="updatedAt">按更新</option>
      </select>
      <button class="rounded px-2 py-1 border text-xs dark:border-gray-700" @click="query.page=1; load()">搜索</button>
      <div class="ml-auto flex items-center gap-2">
        <label class="text-xs text-gray-600 dark:text-gray-300">分区</label>
        <select v-model="query.sectionId" @change="query.page=1; load()" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
          <option value="">全部</option>
          <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name || ('#' + s.id) }}</option>
        </select>
      </div>
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
          <router-link :to="'/threads/' + item.threadId + '#post-' + item.id" class="hover:underline">{{ item.threadTitle || ('#' + item.threadId) }}</router-link>
        </div>
        <div class="mt-2 flex items-center gap-2 text-xs">
          <button class="rounded px-2 py-1 border dark:border-gray-700" @click="askRemove(item.id)">删除</button>
        </div>
      </li>
    </ul>
    <ConfirmDialog
      v-if="showDeleteConfirm"
      title="删除评论"
      message="确定删除该评论？删除后不可恢复"
      :danger="true"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />
  </div>
</template>

<style scoped>
</style>
<script setup>
import { ref, onMounted, computed } from 'vue'
import { listMyThreads, updateThread, deleteThread } from '@/api/my'
import { listSections } from '@/api/sections'
import { formatRelativeTime } from '@/composables/time'
import ConfirmDialog from '@/components/ConfirmDialog.vue'

const query = ref({ q: '', sectionId: '', page: 1, size: 10, sort: 'updatedAt' })
const loading = ref(false)
const error = ref('')
const list = ref({ items: [], page: 1, size: 10, total: 0 })
const sections = ref([])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listMyThreads({ q: query.value.q, sectionId: query.value.sectionId || undefined, page: query.value.page, size: query.value.size, sort: query.value.sort })
    list.value = data
  } catch (e) {
    error.value = '加载我的帖子失败'
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

const editingId = ref(null)
const editForm = ref({ title: '', content: '', sectionId: '' })
function startEdit(item) {
  editingId.value = item.id
  editForm.value = { title: item.title, content: item.content || '', sectionId: item.sectionId }
}
async function saveEdit() {
  try {
    await updateThread(editingId.value, { title: editForm.value.title, content: editForm.value.content, sectionId: editForm.value.sectionId || undefined })
    editingId.value = null
    await load()
  } catch (e) {}
}
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const pendingDeleteId = ref(null)

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
    await deleteThread(pendingDeleteId.value)
    showDeleteConfirm.value = false
    pendingDeleteId.value = null
    await load()
  } catch (e) {
    // 可根据需要在此处设置错误消息
  } finally {
    deleting.value = false
  }
}

function cancelDelete() {
  showDeleteConfirm.value = false
  pendingDeleteId.value = null
}

onMounted(() => { load(); loadSections() })

// 分页计算与翻页方法
const totalPages = computed(() => {
  const s = Number(list.value.size || query.value.size || 10)
  const t = Number(list.value.total || 0)
  const pages = Math.ceil(t / (s || 10))
  return Math.max(1, pages || 1)
})

function setPage(p) {
  const target = Math.min(Math.max(1, p), totalPages.value)
  if (target === (query.value.page || 1)) return
  query.value.page = target
  load()
}

function prevPage() {
  if (loading.value) return
  setPage((query.value.page || 1) - 1)
}

function nextPage() {
  if (loading.value) return
  setPage((query.value.page || 1) + 1)
}
</script>

<template>
  <div class="rounded-md border border-gray-200 bg-white p-4 dark:bg-gray-800 dark:border-gray-700">
    <div class="text-sm font-medium mb-2">我的帖子</div>
    <div class="flex items-center gap-2 text-xs mb-2">
      <input v-model="query.q" placeholder="关键词" @keyup.enter="query.page=1; load()" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700" />
      <select v-model="query.sort" @change="query.page=1; load()" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
        <option value="updatedAt">按更新</option>
        <option value="createdAt">按创建</option>
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
        <div class="flex items-center justify-between">
          <div>
            <router-link :to="'/threads/' + item.id" class="font-medium hover:underline">{{ item.title }}</router-link>
            <span class="ml-2 text-xs text-gray-500">分区：{{ item.sectionName || item.sectionId }}</span>
          </div>
          <div class="text-xs text-gray-500">更新：{{ formatRelativeTime(item.updatedAt || item.createdAt) }}</div>
        </div>
        <div class="mt-2 text-xs text-gray-600 dark:text-gray-300">#{{ item.id }} · 创建：{{ formatRelativeTime(item.createdAt) }}</div>
        <div class="mt-2 flex items-center gap-2 text-xs">
          <button class="rounded px-2 py-1 border dark:border-gray-700" @click="startEdit(item)">编辑</button>
          <button class="rounded px-2 py-1 border dark:border-gray-700" @click="askRemove(item.id)">删除</button>
        </div>

        <div v-if="editingId===item.id" class="mt-3 rounded border p-3 dark:border-gray-700">
          <div class="grid grid-cols-1 md:grid-cols-2 gap-2">
            <label class="text-xs">标题
              <input v-model="editForm.title" class="mt-1 w-full rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700" />
            </label>
            <label class="text-xs">分区ID
              <input v-model="editForm.sectionId" class="mt-1 w-full rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700" />
            </label>
            <label class="text-xs md:col-span-2">内容
              <textarea
                v-model="editForm.content"
                rows="4"
                class="mt-1 w-full rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700 resize-none h-40"
              />
            </label>
          </div>
          <div class="mt-2 flex items-center gap-2">
<button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-1 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700" @click="saveEdit">保存</button>
            <button class="rounded px-3 py-1 text-xs border dark:border-gray-700" @click="editingId=null">取消</button>
          </div>
        </div>
      </li>
    </ul>

    <!-- 分页控件 -->
    <div class="mt-3 flex items-center justify-between text-xs">
      <div class="text-gray-600">共 {{ list.total || 0 }} 条，{{ totalPages }} 页</div>
      <div class="flex items-center gap-2">
        <button
          class="rounded px-2 py-1 border dark:border-gray-700"
          :disabled="loading || (query.page || 1) <= 1"
          @click="prevPage"
        >上一页</button>
        <span>第 {{ query.page || 1 }} / {{ totalPages }} 页</span>
        <button
          class="rounded px-2 py-1 border dark:border-gray-700"
          :disabled="loading || (query.page || 1) >= totalPages"
          @click="nextPage"
        >下一页</button>
      </div>
    </div>
  </div>

  <ConfirmDialog
    v-if="showDeleteConfirm"
    title="删除帖子"
    message="确定删除该帖子？删除后不可恢复"
    :danger="true"
    :loading="deleting"
    @confirm="confirmDelete"
    @cancel="cancelDelete"
  />
</template>

<style scoped>
</style>
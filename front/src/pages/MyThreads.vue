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
  <div class="rounded-xl border border-gray-100 bg-white p-5 shadow-sm dark:bg-gray-800 dark:border-gray-700">
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-bold text-gray-800 dark:text-gray-100">我的帖子</h2>
      <div class="flex items-center gap-3">
        <select v-model="query.sort" @change="query.page=1; load()" class="rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-600 focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-200">
          <option value="updatedAt">最近更新</option>
          <option value="createdAt">最新创建</option>
        </select>
        <div class="flex items-center gap-2">
          <select v-model="query.sectionId" @change="query.page=1; load()" class="rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-600 focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-200">
            <option value="">全部分区</option>
            <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </div>
      </div>
    </div>

    <div class="mb-4 flex gap-2">
      <input 
        v-model="query.q" 
        placeholder="搜索我的帖子..." 
        @keyup.enter="query.page=1; load()" 
        class="flex-1 rounded-lg border border-gray-200 px-3 py-2 text-sm focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-200 dark:placeholder-gray-400" 
      />
      <button class="rounded-lg bg-gray-100 px-4 py-2 text-sm font-medium text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600" @click="query.page=1; load()">
        搜索
      </button>
    </div>

    <div v-if="loading" class="py-8 text-center text-sm text-gray-500">正在加载...</div>
    <div v-else-if="error" class="py-8 text-center text-sm text-red-500">{{ error }}</div>
    <ul v-else class="space-y-3">
      <li v-for="item in (list.items||[])" :key="item.id" class="group rounded-lg border border-gray-100 bg-white p-4 transition-all hover:border-brandDay-200 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700">
        <div class="flex items-start justify-between gap-4">
          <div class="flex-1 min-w-0">
            <div class="flex items-center gap-2 mb-1">
              <span class="rounded bg-gray-100 px-2 py-0.5 text-[10px] font-medium text-gray-500 dark:bg-gray-700 dark:text-gray-400">
                {{ item.sectionName || ('#' + item.sectionId) }}
              </span>
              <span class="text-xs text-gray-400">{{ formatRelativeTime(item.createdAt) }}</span>
            </div>
            <router-link :to="'/threads/' + item.id" class="block">
              <h3 class="text-base font-bold text-gray-800 group-hover:text-brandDay-600 dark:text-gray-100 dark:group-hover:text-brandNight-400 transition-colors line-clamp-1">{{ item.title }}</h3>
            </router-link>
            <div class="mt-2 flex items-center gap-3 text-xs text-gray-400">
              <span>最后更新：{{ formatRelativeTime(item.updatedAt || item.createdAt) }}</span>
            </div>
          </div>
          <div class="flex flex-col gap-2 shrink-0">
            <button class="rounded px-3 py-1.5 text-xs font-medium text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200" @click="startEdit(item)">编辑</button>
            <button class="rounded px-3 py-1.5 text-xs font-medium text-red-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20 transition-colors" @click="askRemove(item.id)">删除</button>
          </div>
        </div>

        <!-- 编辑表单 -->
        <div v-if="editingId===item.id" class="mt-4 rounded-lg border border-brandDay-100 bg-brandDay-50/30 p-4 dark:border-gray-600 dark:bg-gray-700/30">
          <div class="grid grid-cols-1 gap-3">
            <div>
              <label class="mb-1 block text-xs font-medium text-gray-500">标题</label>
              <input v-model="editForm.title" class="w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-800 dark:border-gray-600" />
            </div>
            <div>
              <label class="mb-1 block text-xs font-medium text-gray-500">分区</label>
              <select v-model="editForm.sectionId" class="w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-800 dark:border-gray-600">
                <option value="" disabled>请选择分区</option>
                <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name }}</option>
              </select>
            </div>
            <div>
              <label class="mb-1 block text-xs font-medium text-gray-500">内容</label>
              <textarea
                v-model="editForm.content"
                rows="4"
                class="w-full rounded-md border border-gray-200 px-3 py-1.5 text-sm focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-800 dark:border-gray-600 resize-y min-h-[100px]"
              />
            </div>
          </div>
          <div class="mt-3 flex items-center justify-end gap-2">
            <button class="rounded-md px-3 py-1.5 text-xs font-medium text-gray-500 hover:bg-gray-200 dark:hover:bg-gray-600" @click="editingId=null">取消</button>
            <button class="rounded-md bg-brandDay-600 px-4 py-1.5 text-xs font-medium text-white hover:bg-brandDay-700 shadow-sm dark:bg-brandNight-600 dark:hover:bg-brandNight-700" @click="saveEdit">保存修改</button>
          </div>
        </div>
      </li>
    </ul>

    <!-- 分页控件 -->
    <div class="mt-6 flex items-center justify-between border-t border-gray-100 pt-4 dark:border-gray-700">
      <div class="text-xs text-gray-500">共 {{ list.total || 0 }} 条</div>
      <div class="flex items-center gap-2">
        <button
          class="rounded-md border border-gray-200 px-3 py-1.5 text-xs font-medium hover:bg-gray-50 disabled:opacity-50 disabled:hover:bg-white dark:border-gray-600 dark:hover:bg-gray-700"
          :disabled="loading || (query.page || 1) <= 1"
          @click="prevPage"
        >上一页</button>
        <span class="text-xs text-gray-600 dark:text-gray-300">{{ query.page || 1 }} / {{ totalPages }}</span>
        <button
          class="rounded-md border border-gray-200 px-3 py-1.5 text-xs font-medium hover:bg-gray-50 disabled:opacity-50 disabled:hover:bg-white dark:border-gray-600 dark:hover:bg-gray-700"
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
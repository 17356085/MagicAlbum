<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { listMyPosts, deletePost } from '@/api/my'
import { listSections } from '@/api/sections'
import { formatRelativeTime } from '@/composables/time'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import type { Id, PageResult, Post, Section } from '@/types'

type PostSort = 'createdAt' | 'updatedAt'

interface MyPostsQuery {
  q: string
  sectionId: string
  page: number
  size: number
  sort: PostSort
}

interface MyPostItem extends Post {
  threadTitle?: string
  sectionId?: Id
  sectionName?: string
}

function createEmptyPostList(): PageResult<MyPostItem> {
  return { items: [], page: 1, size: 10, total: 0 }
}

function normalizePostList(data: PageResult<Post> | Post[]): PageResult<MyPostItem> {
  if (Array.isArray(data)) {
    return {
      items: data as MyPostItem[],
      page: query.value.page,
      size: query.value.size,
      total: data.length,
    }
  }
  return (data as PageResult<MyPostItem>) || createEmptyPostList()
}

const query = ref<MyPostsQuery>({ q: '', sectionId: '', page: 1, size: 10, sort: 'createdAt' })
const loading = ref(false)
const error = ref('')
const list = ref<PageResult<MyPostItem>>(createEmptyPostList())
const sections = ref<Section[]>([])
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const pendingDeleteId = ref<Id | null>(null)
const inputPage = ref('1')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await listMyPosts({ q: query.value.q, sectionId: query.value.sectionId || undefined, page: query.value.page, size: query.value.size, sort: query.value.sort })
    list.value = normalizePostList(data)
  } catch (_) {
    error.value = '加载我的评论失败'
  } finally { loading.value = false }
}

async function loadSections(): Promise<void> {
  try {
    const data = await listSections({ size: 200 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (_) {
    // 保持空列表即可
  }
}

function askRemove(id: Id): void {
  pendingDeleteId.value = id
  showDeleteConfirm.value = true
}

async function confirmDelete(): Promise<void> {
  if (pendingDeleteId.value == null) {
    showDeleteConfirm.value = false
    return
  }
  deleting.value = true
  try {
    await deletePost(pendingDeleteId.value)
    showDeleteConfirm.value = false
    pendingDeleteId.value = null
    await load()
  } catch (_) {
    // 可根据需要设置错误消息
  } finally {
    deleting.value = false
  }
}

function cancelDelete(): void {
  showDeleteConfirm.value = false
  pendingDeleteId.value = null
}

onMounted(async () => { await loadSections(); await load() })

// 分页计算与翻页方法（每页 10 条）
const totalPages = computed<number>(() => {
  const s = Number(list.value.size || query.value.size || 10)
  const t = Number(list.value.total || 0)
  const pages = Math.ceil(t / (s || 10))
  return Math.max(1, pages || 1)
})

function setPage(p: number): void {
  const target = Math.min(Math.max(1, p), totalPages.value)
  if (target === (query.value.page || 1)) return
  query.value.page = target
  load()
}

function prevPage(): void {
  if (loading.value) return
  setPage((query.value.page || 1) - 1)
}

function nextPage(): void {
  if (loading.value) return
  setPage((query.value.page || 1) + 1)
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(query.value.page || 1)
    return
  }
  setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
}

watch(() => query.value.page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

function excerpt(item: MyPostItem): string {
  const text = String(item.content || item.contentMd || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^\)]+\)/g, '')
    .replace(/\[([^\]]+)\]\([^\)]+\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/[#>*_`~\-\[\]]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!text) return '暂无评论内容'
  return text.length > 180 ? `${text.slice(0, 180)}...` : text
}

function contextLink(item: MyPostItem): string {
  return `/threads/${item.threadId}#post-${item.id}`
}
</script>

<template>
  <div class="rounded-xl border border-gray-100 bg-white p-5 shadow-sm dark:bg-gray-800 dark:border-gray-700">
    <div class="mb-4 flex items-center justify-between">
      <h2 class="text-lg font-bold text-gray-800 dark:text-gray-100">我的评论</h2>
      <div class="flex items-center gap-3">
        <select v-model="query.sort" @change="query.page=1; load()" class="rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-600 focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-200">
          <option value="createdAt">最新发布</option>
          <option value="updatedAt">最近更新</option>
        </select>
        <div class="flex items-center gap-2">
          <select v-model="query.sectionId" @change="query.page=1; load()" class="rounded-lg border border-gray-200 px-3 py-1.5 text-xs text-gray-600 focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-200">
            <option value="">全部分区</option>
            <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name || ('#' + s.id) }}</option>
          </select>
        </div>
      </div>
    </div>

    <div class="mb-4 flex gap-2">
      <input 
        v-model="query.q" 
        placeholder="搜索我的评论..." 
        @keyup.enter="query.page=1; load()" 
        class="flex-1 rounded-lg border border-gray-200 px-3 py-2 text-sm focus:border-brandDay-500 focus:ring-1 focus:ring-brandDay-500 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-200 dark:placeholder-gray-400" 
      />
      <button class="rounded-lg bg-gray-100 px-4 py-2 text-sm font-medium text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600" @click="query.page=1; load()">
        搜索
      </button>
    </div>

    <div v-if="loading" class="py-8 text-center text-sm text-gray-500">正在加载...</div>
    <div v-else-if="error" class="py-8 text-center text-sm text-red-500">{{ error }}</div>
    <div v-else-if="(list.items || []).length === 0" class="rounded-lg border border-dashed border-gray-200 py-10 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
      暂无评论
    </div>
    <ul v-else class="space-y-3">
      <li v-for="item in (list.items||[])" :key="item.id" class="group overflow-hidden rounded-lg border border-gray-100 bg-white transition-all hover:border-brandDay-200 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700">
        <div class="p-4">
          <div class="mb-2 flex flex-wrap items-center gap-2">
            <span v-if="item.sectionName || item.sectionId" class="rounded bg-gray-100 px-2 py-0.5 text-[10px] font-medium text-gray-500 dark:bg-gray-700 dark:text-gray-400">
              {{ item.sectionName || ('#' + item.sectionId) }}
            </span>
            <span class="text-xs text-gray-400">发布 {{ formatRelativeTime(item.createdAt) }}</span>
            <span v-if="item.updatedAt && item.updatedAt !== item.createdAt" class="text-xs text-gray-400">编辑 {{ formatRelativeTime(item.updatedAt) }}</span>
          </div>

          <router-link :to="contextLink(item)" class="block">
            <h3 class="line-clamp-1 text-sm font-semibold text-gray-800 transition-colors group-hover:text-brandDay-600 dark:text-gray-100 dark:group-hover:text-brandNight-400">
              RE: {{ item.threadTitle || ('#' + item.threadId) }}
            </h3>
          </router-link>

          <p class="mt-2 line-clamp-3 whitespace-pre-wrap break-words text-sm leading-6 text-gray-600 dark:text-gray-300">
            {{ excerpt(item) }}
          </p>

          <div class="mt-3 flex flex-wrap items-center gap-3 text-xs text-gray-500 dark:text-gray-400">
            <span v-if="item.replyToPostId">回复 #{{ item.replyToPostId }}</span>
            <span v-if="item.parentAuthorNickname || item.parentAuthorUsername">回复 @{{ item.parentAuthorNickname || item.parentAuthorUsername }}</span>
            <span>点赞 {{ Number(item.likeCount || 0) }}</span>
          </div>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-gray-100 bg-gray-50/70 px-4 py-2 dark:border-gray-700 dark:bg-gray-900/20">
          <div class="text-xs text-gray-400">ID: {{ item.id }}</div>
          <div class="flex items-center gap-2">
            <router-link :to="contextLink(item)" class="rounded-md px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-white hover:text-brandDay-700 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-brandNight-300">
              查看上下文
            </router-link>
            <router-link :to="'/threads/' + item.threadId" class="rounded-md px-3 py-1.5 text-xs font-medium text-gray-600 hover:bg-white hover:text-gray-800 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-gray-100">
              查看帖子
            </router-link>
            <button class="rounded-md px-3 py-1.5 text-xs font-medium text-red-500 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20 transition-colors" @click="askRemove(item.id)">删除</button>
          </div>
        </div>
      </li>
    </ul>

    <!-- 分页控件 -->
    <div class="mt-6 flex items-center justify-between border-t border-gray-100 pt-4 dark:border-gray-700">
      <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ list.total || 0 }} 条 · 每页 {{ query.size }} 条</div>
      <div class="flex items-center gap-2">
        <button
          class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
          :disabled="loading || (query.page || 1) <= 1"
          @click="prevPage"
        >上一页</button>
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
          :disabled="loading || (query.page || 1) >= totalPages"
          @click="nextPage"
        >下一页</button>
      </div>
    </div>
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

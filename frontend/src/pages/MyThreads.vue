<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { listMyThreads, deleteThread } from '@/api/my'
import { listSections } from '@/api/sections'
import { formatRelativeTime } from '@/composables/time'
import { useOwnedContentList } from '@/composables/useOwnedContentList'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import type { Section, Thread } from '@/types'

const {
  query,
  loading,
  error,
  deleteError,
  list,
  inputPage,
  showDeleteConfirm,
  deleting,
  totalPages,
  deleteDialogTitle,
  deleteDialogMessage,
  deleteConfirmText,
  load,
  retryLoad,
  askRemove,
  confirmDelete,
  cancelDelete,
  prevPage,
  nextPage,
  goToInputPage,
} = useOwnedContentList<Thread>({
  initialSort: 'updatedAt',
  fetchPage: listMyThreads,
  deleteItem: deleteThread,
  messages: {
    loadError: '加载我的帖子失败',
    loginRequired: '请登录后再加载我的帖子',
    sessionChanged: '账号状态已变更，请刷新页面后重试',
    deleteError: '删除帖子失败，请检查网络后重试',
    confirmTitle: '删除帖子',
    confirmMessage: '确定删除该帖子？删除后不可恢复',
  },
})

const sections = ref<Section[]>([])
let sectionsRequestSeq = 0
let sectionsDisposed = false

async function loadSections(): Promise<void> {
  const requestSeq = ++sectionsRequestSeq
  try {
    const data = await listSections({ size: 200 })
    if (sectionsDisposed || requestSeq !== sectionsRequestSeq) return
    sections.value = data.items
  } catch (_) {
    // 保持空列表即可
  }
}

onMounted(() => {
  void loadSections()
})

onUnmounted(() => {
  sectionsDisposed = true
  sectionsRequestSeq += 1
})

function excerpt(item: Thread): string {
  const text = String(item.content || item.contentMd || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^)]+\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/[#>*_`~\-[\]]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!text) return '暂无内容摘要'
  return text.length > 160 ? `${text.slice(0, 160)}...` : text
}
</script>

<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">我的帖子</h1>
        <p class="ui-description">整理你发起的讨论，继续和社区交流。</p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <select v-model="query.sort" class="ui-field w-auto" aria-label="帖子排序" @change="query.page=1; load()">
          <option value="updatedAt">最近更新</option>
          <option value="createdAt">最新创建</option>
        </select>
        <select v-model="query.sectionId" class="ui-field w-auto" aria-label="按分区筛选" @change="query.page=1; load()">
          <option value="">全部分区</option>
          <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </div>
    </header>

    <section class="ui-panel overflow-hidden">
      <div class="ui-toolbar">
        <input v-model="query.q" class="ui-field min-w-[220px] flex-1" placeholder="搜索我的帖子..." aria-label="搜索我的帖子" @keyup.enter="query.page=1; load()" />
        <button type="button" class="ui-button-primary" @click="query.page=1; load()">搜索</button>
      </div>

      <div v-if="loading" class="ui-empty" role="status">正在加载...</div>
      <template v-else>
        <div v-if="error" class="ui-error m-4 flex flex-wrap items-center justify-between gap-3" role="alert">
          <span>{{ error }}</span>
          <button type="button" class="ui-button min-h-11" :disabled="loading" @click="retryLoad">重试</button>
        </div>
      </template>
      <div v-if="!loading && !error && (list.items || []).length === 0" class="ui-empty" role="status">
        <h2>暂无帖子</h2>
        <p>发布第一个主题，开始一段新的讨论。</p>
      </div>
      <ul v-else-if="!loading && (list.items || []).length > 0">
        <li v-for="item in (list.items||[])" :key="item.id" class="ui-list-row">
          <div class="min-w-0">
            <div class="mb-2 flex flex-wrap items-center gap-2 text-xs text-[var(--ui-muted)]">
              <span class="ui-tag">{{ item.sectionName || ('#' + item.sectionId) }}</span>
              <span>创建 {{ formatRelativeTime(item.createdAt) }}</span>
              <span>更新 {{ formatRelativeTime(item.updatedAt || item.createdAt) }}</span>
            </div>
            <router-link :to="'/threads/' + item.id" class="block">
              <h2 class="line-clamp-2 text-base font-bold text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">{{ item.title }}</h2>
            </router-link>
            <p class="mt-2 line-clamp-2 text-sm leading-6 text-[var(--ui-muted)]">{{ excerpt(item) }}</p>
            <div class="mt-3 flex flex-wrap items-center gap-3 text-xs text-[var(--ui-muted)]">
              <span>回复 {{ Number(item.replyCount || 0) }}</span>
              <span>点赞 {{ Number(item.likeCount || 0) }}</span>
            </div>
          </div>
          <div class="mt-4 flex flex-wrap items-center justify-between gap-2 border-t border-[var(--ui-border)] pt-3">
            <div class="text-xs text-[var(--ui-muted)]">ID: {{ item.id }}</div>
            <div class="flex flex-wrap items-center gap-2">
              <router-link :to="'/threads/' + item.id" class="ui-button">查看</router-link>
              <router-link :to="'/threads/' + item.id + '/edit'" class="ui-button">编辑</router-link>
              <button type="button" class="ui-button border-red-200 text-red-600 hover:bg-red-50 dark:border-red-900/60 dark:text-red-300 dark:hover:bg-red-950/30" @click="askRemove(item.id)">删除</button>
            </div>
          </div>
        </li>
      </ul>

      <footer class="flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 text-sm sm:flex-row sm:items-center sm:justify-between sm:px-6">
        <div class="text-xs text-[var(--ui-muted)]">共 {{ list.total || 0 }} 条 · 每页 {{ query.size }} 条</div>
        <div class="flex flex-wrap items-center gap-2">
          <button type="button" class="ui-button" :disabled="loading || (query.page || 1) <= 1" @click="prevPage">上一页</button>
          <span class="text-sm text-[var(--ui-muted)]">第</span>
          <input v-model="inputPage" aria-label="页码" class="ui-field w-16 text-center" inputmode="numeric" @keyup.enter="goToInputPage" @blur="goToInputPage" />
          <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
          <button type="button" class="ui-button" :disabled="loading || (query.page || 1) >= totalPages" @click="nextPage">下一页</button>
        </div>
      </footer>
    </section>
    <div v-if="deleteError" class="ui-error mt-3" role="alert">{{ deleteError }}</div>
    <ConfirmDialog
      v-if="showDeleteConfirm"
      :title="deleteDialogTitle"
      :message="deleteDialogMessage"
      :confirm-text="deleteConfirmText"
      :danger="true"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="cancelDelete"
    />
  </section>
</template>

<style scoped>
</style>

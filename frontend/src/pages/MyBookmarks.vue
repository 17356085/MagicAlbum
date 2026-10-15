<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listBookmarks, type BookmarkPage } from '@/api/bookmarks'
import Pagination from '@/components/Pagination.vue'

const page = ref(1)
const size = ref(10)
const loading = ref(true)
const error = ref('')
const bookmarks = ref<BookmarkPage>({ items: [], page: 1, size: 10, total: 0 })
let requestSequence = 0

const totalPages = computed(() => Math.max(1, Math.ceil(Number(bookmarks.value.total || 0) / Number(bookmarks.value.size || size.value))))

function formatBookmarkedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value || ''
  return date.toLocaleString()
}

async function load(nextPage = page.value): Promise<void> {
  const requestId = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const result = await listBookmarks({ page: nextPage, size: size.value })
    if (requestId !== requestSequence) return
    bookmarks.value = result
    page.value = Math.min(Math.max(1, Number(result.page || nextPage)), totalPages.value)
  } catch (_) {
    if (requestId === requestSequence) {
      error.value = '收藏加载失败，请稍后重试'
    }
  } finally {
    if (requestId === requestSequence) {
      loading.value = false
    }
  }
}

function retry(): void {
  void load(page.value)
}

function setPage(nextPage: number): void {
  if (loading.value) return
  const target = Math.min(Math.max(1, nextPage), totalPages.value)
  if (target === page.value) return
  page.value = target
  void load(target)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">我的收藏</h1>
        <p class="ui-description">收藏过的帖子会保存在你的账户中。</p>
      </div>
    </header>

    <section class="ui-panel overflow-hidden">
      <div v-if="loading" role="status" class="ui-empty">正在加载收藏...</div>

      <div v-else-if="error" role="alert" class="ui-error m-4">
        <p>{{ error }}</p>
        <button type="button" class="ui-button mt-3" @click="retry">重试</button>
      </div>

      <div v-else-if="bookmarks.items.length === 0" class="ui-empty">
        <h2>暂无收藏</h2>
        <p>收藏帖子后，它们会出现在这里。</p>
      </div>

      <ul v-else>
        <li v-for="item in bookmarks.items" :key="String(item.threadId)" class="ui-list-row">
          <router-link :to="`/threads/${item.threadId}`" class="block min-w-0">
            <h2 class="line-clamp-2 text-base font-semibold leading-6 text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
              {{ item.title }}
            </h2>
            <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-[var(--ui-muted)]">
              <span class="ui-tag">{{ item.sectionName || '未分区' }}</span>
              <span>收藏于 {{ formatBookmarkedAt(item.bookmarkedAt) }}</span>
            </div>
          </router-link>
        </li>
      </ul>

      <div v-if="!loading && !error && bookmarks.items.length > 0" class="border-t border-[var(--ui-border)] px-4 py-4 sm:px-6">
        <Pagination :page="page" :size="bookmarks.size || size" :total="bookmarks.total" @update:page="setPage" />
      </div>
    </section>
  </section>
</template>

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
  <section class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
    <div class="border-b border-gray-100 px-5 py-4 dark:border-gray-700">
      <h1 class="text-xl font-semibold text-gray-900 dark:text-gray-100">我的收藏</h1>
      <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">收藏过的帖子会保存在你的账户中。</p>
    </div>

    <div class="space-y-3 p-5">
      <div v-if="loading" role="status" class="py-8 text-center text-sm text-gray-500 dark:text-gray-400">
        正在加载收藏...
      </div>

      <div v-else-if="error" role="alert" class="rounded-lg border border-red-100 bg-red-50 px-4 py-5 text-center text-sm text-red-600 dark:border-red-900/50 dark:bg-red-950/30 dark:text-red-300">
        <p>{{ error }}</p>
        <button
          type="button"
          class="mt-3 rounded-lg border border-red-200 px-3 py-1.5 text-xs font-medium hover:bg-red-100 dark:border-red-800 dark:hover:bg-red-900/40"
          @click="retry"
        >
          重试
        </button>
      </div>

      <div v-else-if="bookmarks.items.length === 0" class="rounded-lg border border-dashed border-gray-200 px-4 py-10 text-center text-sm text-gray-400 dark:border-gray-700 dark:text-gray-500">
        暂无收藏
      </div>

      <ul v-else class="space-y-3">
        <li
          v-for="item in bookmarks.items"
          :key="String(item.threadId)"
          class="group rounded-lg border border-gray-100 bg-white px-4 py-3 shadow-sm transition-colors hover:border-brandDay-200 hover:bg-brandDay-50/30 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700 dark:hover:bg-brandNight-950/20"
        >
          <router-link :to="`/threads/${item.threadId}`" class="block min-w-0">
            <h2 class="line-clamp-2 text-base font-semibold leading-6 text-gray-900 group-hover:text-brandDay-600 dark:text-gray-100 dark:group-hover:text-brandNight-300">
              {{ item.title }}
            </h2>
            <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              <span class="rounded bg-gray-100 px-2 py-0.5 text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                {{ item.sectionName || '未分区' }}
              </span>
              <span>收藏于 {{ formatBookmarkedAt(item.bookmarkedAt) }}</span>
            </div>
          </router-link>
        </li>
      </ul>
    </div>

    <div v-if="!loading && !error && bookmarks.items.length > 0" class="border-t border-gray-100 px-5 py-4 dark:border-gray-700">
      <Pagination :page="page" :size="bookmarks.size || size" :total="bookmarks.total" @update:page="setPage" />
    </div>
  </section>
</template>

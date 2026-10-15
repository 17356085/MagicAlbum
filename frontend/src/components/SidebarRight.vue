<template>
  <aside class="space-y-3">
    <div class="rounded-md border border-indigo-100 bg-indigo-50 dark:bg-indigo-900/20 dark:border-indigo-800 p-3">
      <div class="flex items-center gap-2 mb-2">
        <span class="relative flex h-3 w-3">
          <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-indigo-400 opacity-75"></span>
          <span class="relative inline-flex rounded-full h-3 w-3 bg-indigo-500"></span>
        </span>
        <span class="text-sm font-bold text-indigo-700 dark:text-indigo-300">专属客服</span>
      </div>
      <p class="text-xs text-indigo-600 dark:text-indigo-400 mb-2">有问题？小祥随时为您解答！</p>
      <button
        type="button"
        @click="openChat"
        class="w-full py-1.5 text-xs font-medium text-white bg-indigo-600 hover:bg-indigo-700 rounded transition-colors"
      >
        找小祥聊聊
      </button>
    </div>

    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">热门话题</div>
      <div v-if="tagsLoading" role="status" aria-live="polite" class="p-3 text-xs text-gray-500 dark:text-gray-400">
        热门话题加载中...
      </div>
      <div v-else-if="tagsError" role="alert" class="flex items-center justify-between gap-3 p-3 text-xs text-red-600 dark:text-red-400">
        <span>{{ tagsError }}</span>
        <button
          type="button"
          class="shrink-0 rounded border border-red-200 px-2 py-1 font-medium hover:bg-red-50 dark:border-red-800 dark:hover:bg-red-900/30"
          @click="loadPopularTags"
        >
          重试
        </button>
      </div>
      <div v-else-if="popularTags.length === 0" role="status" class="p-3 text-xs text-gray-500 dark:text-gray-400">
        暂无热门话题
      </div>
      <ul v-else class="p-2 text-sm">
        <li v-for="tag in popularTags" :key="tag.name" class="flex justify-between gap-2 py-1">
          <RouterLink
            :to="{ name: 'discover', query: { tag: tag.name } }"
            class="truncate hover:underline"
          >
            #{{ tag.name }}
          </RouterLink>
          <span class="text-xs text-gray-500 dark:text-gray-400">{{ tag.threadCount }}</span>
        </li>
      </ul>
    </div>
    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">公告</div>
      <div class="p-3 text-xs text-gray-700 dark:text-gray-200">欢迎体验 {{ SITE_NAME }}，功能持续完善中。</div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { listPopularTags } from '@/api/tags'
import type { TagStats } from '@/types'
import { SITE_NAME } from '@/config/brand'

const popularTags = ref<TagStats[]>([])
const tagsLoading = ref(true)
const tagsError = ref('')
let tagsRequestSeq = 0
let isMounted = false

async function loadPopularTags(): Promise<void> {
  const requestSeq = ++tagsRequestSeq
  tagsLoading.value = true
  tagsError.value = ''

  try {
    const items = await listPopularTags()
    if (isMounted && requestSeq === tagsRequestSeq) {
      popularTags.value = items
    }
  } catch (_) {
    if (isMounted && requestSeq === tagsRequestSeq) {
      popularTags.value = []
      tagsError.value = '热门话题加载失败，请重试'
    }
  } finally {
    if (isMounted && requestSeq === tagsRequestSeq) {
      tagsLoading.value = false
    }
  }
}

function openChat(): void {
  window.dispatchEvent(new CustomEvent('open-ai-chat'))
}

onMounted(() => {
  isMounted = true
  void loadPopularTags()
})

onBeforeUnmount(() => {
  isMounted = false
  tagsRequestSeq += 1
})
</script>

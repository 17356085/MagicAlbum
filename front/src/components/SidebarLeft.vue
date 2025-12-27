<template>
  <aside class="space-y-3">
    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">导航</div>
      <ul class="p-2 text-sm">
        <li>
          <router-link
            to="/sections"
            class="block rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
            :class="isSectionsActive ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300' : ''"
          >分区</router-link>
        </li>
        <li>
          <router-link
            to="/discover"
            class="block rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
            :class="isDiscoverActive ? 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300' : ''"
          >发现</router-link>
        </li>
        <li><a href="#" class="block rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700">排行榜</a></li>
        <li v-if="isLoggedIn">
          <router-link
            to="/settings"
            class="block rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
          >设置</router-link>
        </li>
        <li v-if="isLoggedIn">
          <router-link
            to="/my/threads"
            class="block rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
          >我的帖子</router-link>
        </li>
        <li v-if="isLoggedIn">
          <router-link
            to="/my/posts"
            class="block rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
          >我的评论</router-link>
        </li>
      </ul>
    </div>
    <div class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <div class="border-b px-3 py-2 text-sm font-medium dark:border-gray-700 dark:text-gray-100">标签</div>
      <div class="p-2 flex flex-wrap gap-2 text-xs">
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">摄影</span>
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">绘画</span>
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">旅行</span>
        <span class="rounded bg-gray-100 px-2 py-1 dark:bg-gray-700 dark:text-gray-200">随笔</span>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuth } from '@/composables/useAuth'

const route = useRoute()
const { isLoggedIn } = useAuth()
const isSectionsActive = computed(() => route.name === 'sections' || route.path === '/sections')
const isDiscoverActive = computed(() => route.name === 'discover' || route.path === '/discover')
</script>
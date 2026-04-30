<template>
  <div class="hidden md:flex md:flex-1 md:mx-6 items-center gap-3">
    <div class="inline-flex rounded-md border border-gray-300 bg-white p-0.5 text-xs dark:bg-gray-800 dark:border-gray-700">
      <button
        class="rounded px-4 py-1 whitespace-nowrap"
        :class="searchType === 'threads' ? 'bg-brand-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
        @click="$emit('update:search-type', 'threads')"
      >搜帖子</button>
      <button
        class="rounded px-4 py-1 whitespace-nowrap"
        :class="searchType === 'users' ? 'bg-brand-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
        @click="$emit('update:search-type', 'users')"
      >搜用户</button>
    </div>
    <div class="relative flex-1">
      <input
        :value="searchQuery"
        type="text"
        :placeholder="searchType === 'users' ? '搜索用户名/昵称' : '搜索帖子标题或内容'"
        class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 pr-10 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
        @input="onSearchInput"
        @keydown="$emit('input-keydown', $event)"
        @focus="$emit('input-focus')"
        @blur="$emit('input-blur')"
      />
      <button
        class="absolute right-1 top-1/2 -translate-y-1/2 rounded bg-brandDay-600 dark:bg-brandNight-600 p-2 text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-colors motion-safe:transition-transform motion-safe:duration-150 active:scale-95 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
        aria-label="搜索"
        title="搜索"
        @click="$emit('search')"
      >
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4">
          <path fill-rule="evenodd" d="M12.9 14.32a8 8 0 111.41-1.41l4.39 4.39a1 1 0 01-1.42 1.42l-4.38-4.4zM14 8a6 6 0 11-12 0 6 6 0 0112 0z" clip-rule="evenodd"/>
        </svg>
        <span class="sr-only">搜索</span>
      </button>
      <div
        v-if="searchType === 'users' && suggestOpen && String(searchQuery || '').trim()"
        class="absolute z-50 mt-2 w-full rounded-md border border-gray-200 bg-white shadow dark:bg-gray-800 dark:border-gray-700"
      >
        <div class="border-b px-3 py-2 text-xs font-medium dark:border-gray-700">匹配的用户</div>
        <div v-if="suggestLoading" class="px-3 py-2 text-xs text-gray-600 dark:text-gray-300">加载中...</div>
        <div v-else-if="suggestError" class="px-3 py-2 text-xs text-red-600">{{ suggestError }}</div>
        <ul v-else class="p-1 text-sm max-h-64 overflow-auto">
          <li v-if="!visibleSuggestions.length" class="px-3 py-2 text-xs text-gray-500 dark:text-gray-400">无匹配</li>
          <li
            v-for="(u, idx) in visibleSuggestions.slice(0, 5)"
            :key="u.id"
            @mousemove="$emit('update:active-index', idx)"
          >
            <router-link
              :to="'/users/' + u.id"
              class="flex items-center justify-between rounded px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-700"
              :class="activeIndex === idx ? 'bg-brand-50 dark:bg-brand-900/30' : ''"
              @click="$emit('close-suggest')"
            >
              <div class="flex items-center gap-2 min-w-0">
                <template v-if="suggestProfiles[String(u.id)]?.avatarUrl">
                  <img :src="normalizeImageUrl(suggestProfiles[String(u.id)].avatarUrl)" alt="avatar" class="w-6 h-6 rounded-full object-cover border border-gray-300 dark:border-gray-700" />
                </template>
                <template v-else>
                  <div class="w-6 h-6 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-[10px] font-medium">
                    {{ String((suggestProfiles[String(u.id)]?.nickname || u.username || 'U')).slice(0, 1).toUpperCase() }}
                  </div>
                </template>
                <div class="truncate">
                  <span class="font-medium" v-html="renderHighlightedTextHtml(suggestProfiles[String(u.id)]?.nickname || u.username, searchQuery)"></span>
                  <span v-if="suggestProfiles[String(u.id)]?.nickname" class="ml-2 text-xs text-gray-500 dark:text-gray-400">{{ u.username }}</span>
                </div>
              </div>
              <span class="text-xs text-gray-400">#{{ u.id }}</span>
            </router-link>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { normalizeImageUrl } from '@/utils/image'
import { highlightText } from '@/utils/text'
import type { User, UserProfile } from '@/types'

type SearchType = 'threads' | 'users'
type SuggestProfile = Pick<UserProfile, 'avatarUrl' | 'nickname'>

defineProps<{
  searchType: SearchType
  searchQuery: string
  suggestOpen: boolean
  suggestLoading: boolean
  suggestError: string
  visibleSuggestions: User[]
  suggestProfiles: Record<string, SuggestProfile>
  activeIndex: number
}>()

const emit = defineEmits<{
  'update:search-type': [value: SearchType]
  'update:search-query': [value: string]
  'update:active-index': [value: number]
  search: []
  'input-keydown': [event: KeyboardEvent]
  'input-focus': []
  'input-blur': []
  'close-suggest': []
}>()

function renderHighlightedTextHtml(text: string | null | undefined, keyword: string | null | undefined): string {
  return highlightText(text, keyword)
}

function onSearchInput(event: Event): void {
  const target = event.target as HTMLInputElement | null
  emit('update:search-query', target?.value || '')
}
</script>

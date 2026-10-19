<template>
  <div ref="searchRoot" class="relative flex shrink-0 items-center lg:mx-4 lg:flex-1">
    <button
      ref="searchTrigger"
      type="button"
      class="inline-flex h-12 w-12 shrink-0 items-center justify-center rounded p-1.5 text-gray-700 hover:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:text-gray-200 dark:hover:bg-gray-700 dark:focus:ring-accentCyan-400 lg:hidden"
      aria-label="搜索"
      title="搜索"
      aria-controls="header-search-panel"
      :aria-expanded="mobileSearchOpen"
      @click="toggleMobileSearch"
    >
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-5 w-5" aria-hidden="true">
        <path fill-rule="evenodd" d="M12.9 14.32a8 8 0 111.41-1.41l4.39 4.39a1 1 0 01-1.42 1.42l-4.38-4.4zM14 8a6 6 0 11-12 0 6 6 0 0112 0z" clip-rule="evenodd" />
      </svg>
    </button>

    <div
      id="header-search-panel"
      @keydown.esc="onPanelEscape"
      class="mobile-search-panel"
      :class="[
        mobileSearchOpen
          ? 'fixed inset-x-2 top-14 z-50 mt-2 block w-auto max-w-none rounded-lg border border-gray-200 bg-white p-2 shadow-xl dark:border-gray-700 dark:bg-gray-900'
          : 'hidden',
        'lg:static lg:inset-x-auto lg:top-auto lg:mt-0 lg:block lg:w-auto lg:max-w-none lg:flex-1 lg:rounded-none lg:border-0 lg:bg-transparent lg:p-0 lg:shadow-none',
      ]"
    >
      <div class="flex flex-col gap-2 lg:flex-row lg:items-center lg:gap-3">
        <div class="inline-flex w-full rounded-lg bg-gray-50 p-1 text-xs dark:border-gray-700 dark:bg-gray-800 lg:w-auto">
          <button
            type="button"
            class="flex min-h-11 flex-1 items-center justify-center rounded px-3 py-1 whitespace-nowrap lg:flex-none lg:px-4"
            :class="searchType === 'threads' ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/40 dark:text-brandNight-200' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
            :aria-pressed="searchType === 'threads'"
            @click="emit('update:search-type', 'threads')"
          >搜帖子</button>
          <button
            type="button"
            class="flex min-h-11 flex-1 items-center justify-center rounded px-3 py-1 whitespace-nowrap lg:flex-none lg:px-4"
            :class="searchType === 'users' ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/40 dark:text-brandNight-200' : 'hover:bg-gray-100 dark:hover:bg-gray-700'"
            :aria-pressed="searchType === 'users'"
            @click="emit('update:search-type', 'users')"
          >搜用户</button>
        </div>

        <div class="relative min-w-0 flex-1">
          <input
            ref="searchInput"
            :value="searchQuery"
            type="text" aria-label="搜索关键词"
            :placeholder="searchType === 'users' ? '搜索用户名/昵称' : '搜索帖子标题或内容'"
            class="ui-field min-h-12 pr-14 text-base"
            @input="onSearchInput"
            @keydown="onInputKeydown"
            @focus="emit('input-focus')"
            @blur="emit('input-blur')"
          />
          <button
            type="button"
            class="absolute right-1 top-1/2 inline-flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded bg-brandDay-600 p-2 text-white hover:bg-brandDay-700 focus:outline-none focus:ring-2 focus:ring-brandDay-600 motion-safe:transition-colors motion-safe:transition-transform motion-safe:duration-150 active:scale-95 dark:bg-brandNight-600 dark:hover:bg-brandNight-700 dark:focus:ring-accentCyan-400"
            aria-label="搜索"
            title="搜索"
            @click="emitSearch"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-4 w-4" aria-hidden="true">
              <path fill-rule="evenodd" d="M12.9 14.32a8 8 0 111.41-1.41l4.39 4.39a1 1 0 01-1.42 1.42l-4.38-4.4zM14 8a6 6 0 11-12 0 6 6 0 0112 0z" clip-rule="evenodd" />
            </svg>
            <span class="sr-only">搜索</span>
          </button>

          <div
            v-if="searchType === 'users' && suggestOpen && String(searchQuery || '').trim()"
            class="absolute z-50 mt-2 w-full max-w-[calc(100vw-1rem)] rounded-md border border-gray-200 bg-white shadow dark:border-gray-700 dark:bg-gray-800"
          >
            <div class="border-b px-3 py-2 text-xs font-medium dark:border-gray-700">匹配的用户</div>
            <div v-if="suggestLoading" class="px-3 py-2 text-xs text-gray-600 dark:text-gray-300">加载中...</div>
            <div v-else-if="suggestError" class="px-3 py-2 text-xs text-red-600">{{ suggestError }}</div>
          <ul v-else class="ui-scroll max-h-64 overflow-auto p-1 text-sm">
              <li v-if="!visibleSuggestions.length" class="px-3 py-2 text-xs text-gray-500 dark:text-gray-400">无匹配</li>
              <li
                v-for="(u, idx) in visibleSuggestions.slice(0, 5)"
                :key="u.id"
                @mousemove="emit('update:active-index', idx)"
              >
                <router-link
                  :to="'/users/' + u.id"
                  class="flex items-center justify-between rounded px-3 py-2 hover:bg-gray-100 dark:hover:bg-gray-700"
                  :class="activeIndex === idx ? 'bg-brand-50 dark:bg-brand-900/30' : ''"
                  @click="emit('close-suggest')"
                >
                  <div class="flex min-w-0 items-center gap-2">
                    <template v-if="suggestProfiles[String(u.id)]?.avatarUrl">
                      <img :src="normalizeImageUrl(suggestProfiles[String(u.id)].avatarUrl)" alt="avatar" class="h-6 w-6 rounded-full border border-gray-300 object-cover dark:border-gray-700" />
                    </template>
                    <template v-else>
                      <div class="flex h-6 w-6 items-center justify-center rounded-full bg-gray-200 text-[10px] font-medium dark:bg-gray-700">
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
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
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

const searchRoot = ref<HTMLElement | null>(null)
const searchTrigger = ref<HTMLButtonElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const mobileSearchOpen = ref(false)

function renderHighlightedTextHtml(text: string | null | undefined, keyword: string | null | undefined): string {
  return highlightText(text, keyword)
}

function toggleMobileSearch(): void {
  mobileSearchOpen.value = !mobileSearchOpen.value
  if (mobileSearchOpen.value) {
    void nextTick(() => searchInput.value?.focus())
  }
}

function closeMobileSearch(restoreFocus = false): void {
  mobileSearchOpen.value = false
  if (restoreFocus) void nextTick(() => searchTrigger.value?.focus())
}

function onPanelEscape(event: KeyboardEvent): void {
  if (!mobileSearchOpen.value) return
  event.preventDefault()
  event.stopPropagation()
  closeMobileSearch(true)
}

function emitSearch(): void {
  emit('search')
  closeMobileSearch()
}

function onSearchInput(event: Event): void {
  const target = event.target as HTMLInputElement | null
  emit('update:search-query', target?.value || '')
}

function onInputKeydown(event: KeyboardEvent): void {
  emit('input-keydown', event)
  if (event.key === 'Enter') {
    closeMobileSearch()
  }
}

function onDocumentPointerDown(event: PointerEvent): void {
  const target = event.target
  if (!mobileSearchOpen.value || !(target instanceof Node)) return
  if (!searchRoot.value?.contains(target)) closeMobileSearch()
}

onMounted(() => document.addEventListener('pointerdown', onDocumentPointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointerDown))
</script>

<style scoped>
.mobile-search-panel {
  top: calc(56px + env(safe-area-inset-top, 0px));
}

@media (min-width: 1024px) {
  .mobile-search-panel {
    top: auto;
  }
}
</style>

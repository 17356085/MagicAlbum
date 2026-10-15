<template>
  <div class="sidebar-section p-3">
    <div class="mb-3 flex items-center justify-between gap-3">
      <span class="text-xs font-semibold ui-muted">{{ tagPanelTitle }}</span>
      <span v-if="currentTagSectionName" class="max-w-[7rem] truncate rounded bg-brandDay-50 px-2 py-0.5 text-[10px] font-medium text-brandDay-600 dark:bg-brandNight-900/30 dark:text-brandNight-300">
        {{ currentTagSectionName }}
      </span>
    </div>
    <div v-if="tagsLoading" class="py-2 text-xs text-gray-400">加载中...</div>
    <div v-else-if="tagsError" class="py-2 text-xs text-red-500">{{ tagsError }}</div>
    <div v-else-if="popularTags.length === 0" class="py-2 text-xs text-gray-400">暂无标签</div>
    <div v-else class="flex flex-wrap gap-2">
      <router-link
        v-for="tag in popularTags"
        :key="tag.id"
        :to="tagLink(tag.name)"
        :title="`${tag.name} · ${tag.threadCount} 篇帖子`"
        class="group inline-flex items-center gap-1 rounded-md bg-gray-50 px-2.5 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-brandDay-50 hover:text-brandDay-600 dark:bg-gray-700/50 dark:text-gray-300 dark:hover:bg-brandNight-900/30 dark:hover:text-brandNight-300"
      >
        <span>#{{ tag.name }}</span>
        <span class="text-[10px] text-gray-400 transition-colors group-hover:text-brandDay-400 dark:group-hover:text-brandNight-400">{{ tag.threadCount }}</span>
      </router-link>
    </div>
    <div class="mt-3 flex justify-end">
      <button
        type="button"
        class="inline-flex items-center gap-1 text-xs font-medium text-brandDay-700 hover:text-brandDay-800 dark:text-brandNight-400 dark:hover:text-brandNight-300"
        @click="toggleTagsExpanded"
      >
        <span>{{ tagsExpanded ? '收起标签' : '更多标签' }}</span>
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="h-3.5 w-3.5 transition-transform" :class="{ 'rotate-180': tagsExpanded }">
          <path fill-rule="evenodd" d="M5.23 7.21a.75.75 0 011.06.02L10 10.94l3.71-3.71a.75.75 0 111.08 1.04l-4.25 4.25a.75.75 0 01-1.08 0L5.21 8.27a.75.75 0 01.02-1.06z" clip-rule="evenodd" />
        </svg>
      </button>
    </div>

    <div
      class="grid overflow-hidden motion-safe:transition-[grid-template-rows,opacity] motion-safe:duration-200"
      :inert="!tagsExpanded" :class="tagsExpanded ? 'mt-3 grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'"
    >
      <div class="min-h-0">
        <div class="rounded-lg border border-gray-100 bg-gray-50/70 p-2.5 dark:border-gray-700 dark:bg-gray-900/30">
          <input
            v-model="tagSearch"
            type="text"
            class="w-full rounded-md border border-gray-200 bg-white px-2.5 py-1.5 text-xs text-gray-700 focus:border-brandDay-500 focus:outline-none focus:ring-1 focus:ring-brandDay-500 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100"
            :placeholder="currentTagSectionName ? `搜索${currentTagSectionName}标签` : '搜索标签'"
          />
          <div v-if="allTagsLoading" class="py-4 text-center text-xs text-gray-400">加载中...</div>
          <div v-else-if="allTagsError" class="py-4 text-center text-xs text-red-500">{{ allTagsError }}</div>
          <div v-else-if="allTags.length === 0" class="py-4 text-center text-xs text-gray-400">暂无匹配标签</div>
          <div v-else class="mt-2 flex flex-wrap gap-2">
            <router-link
              v-for="tag in allTags"
              :key="tag.id"
              :to="tagLink(tag.name)"
              :title="`${tag.name} · ${tag.threadCount} 篇帖子`"
              class="group inline-flex max-w-full items-center gap-1 rounded-md bg-white px-2 py-1 text-xs font-medium text-gray-600 transition-colors hover:bg-brandDay-50 hover:text-brandDay-600 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-brandNight-900/30 dark:hover:text-brandNight-300"
            >
              <span class="truncate">#{{ tag.name }}</span>
              <span class="text-[10px] text-gray-400 transition-colors group-hover:text-brandDay-400 dark:group-hover:text-brandNight-400">{{ tag.threadCount }}</span>
            </router-link>
          </div>
          <div class="mt-3 flex items-center justify-between text-[11px] text-gray-400">
            <span>共 {{ allTagsTotal }} 个标签</span>
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="rounded border border-gray-200 bg-white px-2 py-1 hover:text-gray-600 disabled:opacity-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:text-gray-200"
                :disabled="allTagsPage <= 1 || allTagsLoading"
                @click="setTagsPage(allTagsPage - 1)"
              >
                上页
              </button>
              <span>{{ allTagsPage }} / {{ allTagsTotalPages }}</span>
              <button
                type="button"
                class="rounded border border-gray-200 bg-white px-2 py-1 hover:text-gray-600 disabled:opacity-50 dark:border-gray-700 dark:bg-gray-800 dark:hover:text-gray-200"
                :disabled="allTagsPage >= allTagsTotalPages || allTagsLoading"
                @click="setTagsPage(allTagsPage + 1)"
              >
                下页
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listPopularTags, listTags } from '@/api/tags'
import { getSingleQueryValue } from '@/utils/router'
import type { Id, Section, TagStats } from '@/types'

const props = defineProps<{
  sections: Section[]
}>()

const route = useRoute()
const popularTags = ref<TagStats[]>([])
const tagsLoading = ref(false)
const tagsError = ref('')
let tagsRequestSeq = 0
const tagsExpanded = ref(false)
const tagSearch = ref('')
const allTags = ref<TagStats[]>([])
const allTagsLoading = ref(false)
const allTagsError = ref('')
const allTagsPage = ref(1)
const allTagsSize = 18
const allTagsTotal = ref(0)
let allTagsRequestSeq = 0
let tagSearchTimer: ReturnType<typeof setTimeout> | null = null

const currentTagSectionId = computed<Id | null>(() => {
  const raw = route.query.sectionId ? getSingleQueryValue(route.query.sectionId) : ''
  return raw || null
})

const currentTagSectionName = computed(() => {
  if (!currentTagSectionId.value) return ''
  return props.sections.find((section) => String(section.id) === String(currentTagSectionId.value))?.name || ''
})

const tagPanelTitle = computed(() => currentTagSectionId.value ? '分区热门标签' : '热门标签')
const allTagsTotalPages = computed(() => Math.max(1, Math.ceil(allTagsTotal.value / allTagsSize)))

function tagLink(name: string) {
  const query: Record<string, Id> = { tag: name, page: 1 }
  if (currentTagSectionId.value) query.sectionId = currentTagSectionId.value
  return { name: 'discover', query }
}

async function loadTagsData(): Promise<void> {
  const requestSeq = ++tagsRequestSeq
  tagsLoading.value = true
  tagsError.value = ''
  try {
    const items = await listPopularTags({ sectionId: currentTagSectionId.value, size: 12 })
    if (requestSeq === tagsRequestSeq) popularTags.value = items
  } catch (_) {
    if (requestSeq === tagsRequestSeq) {
      popularTags.value = []
      tagsError.value = '标签加载失败'
    }
  } finally {
    if (requestSeq === tagsRequestSeq) tagsLoading.value = false
  }
}

async function loadAllTagsData(): Promise<void> {
  const requestSeq = ++allTagsRequestSeq
  allTagsLoading.value = true
  allTagsError.value = ''
  try {
    const data = await listTags({
      q: tagSearch.value,
      sectionId: currentTagSectionId.value,
      page: allTagsPage.value,
      size: allTagsSize,
    })
    if (requestSeq === allTagsRequestSeq) {
      allTags.value = data.items
      allTagsTotal.value = data.total
    }
  } catch (_) {
    if (requestSeq === allTagsRequestSeq) {
      allTags.value = []
      allTagsTotal.value = 0
      allTagsError.value = '标签加载失败'
    }
  } finally {
    if (requestSeq === allTagsRequestSeq) allTagsLoading.value = false
  }
}

function toggleTagsExpanded(): void {
  tagsExpanded.value = !tagsExpanded.value
  if (tagsExpanded.value && allTags.value.length === 0 && !allTagsLoading.value) {
    allTagsPage.value = 1
    void loadAllTagsData()
  }
}

function setTagsPage(page: number): void {
  const next = Math.min(Math.max(1, page), allTagsTotalPages.value)
  if (next === allTagsPage.value) return
  allTagsPage.value = next
  void loadAllTagsData()
}

function onThreadTagsUpdated(): void {
  void loadTagsData()
  if (tagsExpanded.value) void loadAllTagsData()
}

onMounted(() => {
  void loadTagsData()
  window.addEventListener('thread-tags-updated', onThreadTagsUpdated)
})

watch(currentTagSectionId, () => {
  void loadTagsData()
  if (tagsExpanded.value) {
    allTagsPage.value = 1
    void loadAllTagsData()
  }
})

watch(tagSearch, () => {
  if (!tagsExpanded.value) return
  if (tagSearchTimer) clearTimeout(tagSearchTimer)
  tagSearchTimer = setTimeout(() => {
    allTagsPage.value = 1
    void loadAllTagsData()
  }, 250)
})

onBeforeUnmount(() => {
  tagsRequestSeq += 1
  allTagsRequestSeq += 1
  if (tagSearchTimer) clearTimeout(tagSearchTimer)
  window.removeEventListener('thread-tags-updated', onThreadTagsUpdated)
})
</script>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listSections } from '@/api/sections'
import { listThreadRanking } from '@/api/threads'
import { getSingleQueryValue } from '@/utils/router'
import { normalizeImageUrl } from '@/utils/image'
import type { Id, PageResult, Section, Thread } from '@/types'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const sectionsLoading = ref(false)
const error = ref('')
const items = ref<Thread[]>([])
const sections = ref<Section[]>([])
const sectionId = ref<Id | null>(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const inputPage = ref('1')

const totalPages = computed(() => Math.max(1, Math.ceil(Number(total.value || 0) / Number(size.value || 10))))
const topOne = computed(() => items.value[0] || null)
const topNext = computed(() => items.value.slice(1, 3))
const restItems = computed(() => items.value.slice(3))

function normalizeResult(data: PageResult<Thread> | Thread[]): PageResult<Thread> {
  if (Array.isArray(data)) {
    return { items: data, page: page.value, size: size.value, total: data.length }
  }
  return data || { items: [], page: page.value, size: size.value, total: 0 }
}

function readQuery(): void {
  const sidRaw = route.query.sectionId ? getSingleQueryValue(route.query.sectionId) : ''
  const pageRaw = route.query.page ? Number(getSingleQueryValue(route.query.page)) : 1
  sectionId.value = sidRaw ? Number(sidRaw) : null
  page.value = Number.isFinite(pageRaw) && pageRaw > 0 ? pageRaw : 1
}

function changeSection(next: Id | null): void {
  const query: Record<string, string | number> = { page: 1 }
  if (next) query.sectionId = next
  router.push({ name: 'ranking', query })
}

function setPage(next: number): void {
  const target = Math.min(Math.max(1, next), totalPages.value)
  const query: Record<string, string | number> = { page: target }
  if (sectionId.value) query.sectionId = sectionId.value
  router.push({ name: 'ranking', query })
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
}

async function loadSections(): Promise<void> {
  if (sections.value.length > 0) return
  sectionsLoading.value = true
  try {
    const data = await listSections({ size: 50 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (_) {
    sections.value = []
  } finally {
    sectionsLoading.value = false
  }
}

async function loadRanking(): Promise<void> {
  readQuery()
  loading.value = true
  error.value = ''
  try {
    const data = await listThreadRanking({
      sectionId: sectionId.value || undefined,
      page: page.value,
      size: size.value,
    })
    const normalized = normalizeResult(data)
    items.value = normalized.items || []
    total.value = Number(normalized.total || 0)
    page.value = Number(normalized.page || page.value)
    size.value = Number(normalized.size || size.value)
  } catch (_) {
    error.value = '加载排行榜失败'
  } finally {
    loading.value = false
  }
}

function excerpt(thread: Thread): string {
  const text = String(thread.content || thread.contentMd || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^\)]+\)/g, '')
    .replace(/\[([^\]]+)\]\([^\)]+\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/[#>*_`~\-\[\]]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return text.length > 120 ? `${text.slice(0, 120)}...` : text
}

function firstImageUrl(thread: Thread): string {
  const text = String(thread.content || thread.contentMd || '')
  const markdownMatch = text.match(/!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/)
  if (markdownMatch?.[1]) return normalizeImageUrl(markdownMatch[1])
  const htmlMatch = text.match(/<img[^>]+src=["']([^"']+)["']/i)
  if (htmlMatch?.[1]) return normalizeImageUrl(htmlMatch[1])
  return ''
}

function formatDate(value?: string): string {
  if (!value) return ''
  return new Date(value).toLocaleString()
}

function authorName(thread: Thread): string {
  return String(thread.authorNickname || thread.authorUsername || thread.authorId || '匿名用户')
}

function authorAvatarUrl(thread: Thread): string {
  const raw = (thread as Thread & { authorAvatar?: string }).authorAvatarUrl || (thread as Thread & { authorAvatar?: string }).authorAvatar || ''
  const normalized = normalizeImageUrl(raw)
  if (normalized) return normalized
  return `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(authorName(thread))}`
}

function authorProfileTo(thread: Thread): string {
  return thread.authorId ? `/users/${thread.authorId}` : '/users'
}

function rankAt(index: number): number {
  return (page.value - 1) * size.value + index + 1
}

function openThread(id: Id): void {
  router.push(`/threads/${id}`)
}

onMounted(async () => {
  await loadSections()
  await loadRanking()
})

watch(() => route.query, () => {
  loadRanking()
})

watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })
</script>

<template>
  <div class="space-y-4">
    <div v-if="sectionsLoading || loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else-if="error" class="text-red-600">{{ error }}</div>
    <div v-else-if="items.length === 0" class="rounded-lg border border-gray-100 bg-white p-6 text-center text-gray-500 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300">
      <div class="mb-4 flex gap-2 overflow-x-auto">
        <button
          class="shrink-0 rounded-md px-3 py-1.5 text-sm transition-colors"
          :class="!sectionId ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-gray-700/60 dark:hover:text-gray-100'"
          @click="changeSection(null)"
        >
          全区
        </button>
        <button
          v-for="section in sections"
          :key="section.id"
          class="shrink-0 rounded-md px-3 py-1.5 text-sm transition-colors"
          :class="String(sectionId || '') === String(section.id) ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-gray-700/60 dark:hover:text-gray-100'"
          @click="changeSection(section.id)"
        >
          {{ section.name }}
        </button>
      </div>
      <div>暂无排行数据</div>
    </div>
    <div v-else class="space-y-4">
      <div
        v-if="topOne"
        class="overflow-hidden rounded-lg border border-gray-100 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800"
      >
        <div class="border-b border-gray-100 px-4 py-3 dark:border-gray-700">
          <div class="flex gap-2 overflow-x-auto">
            <button
              class="shrink-0 rounded-md px-3 py-1.5 text-sm transition-colors"
              :class="!sectionId ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-gray-700/60 dark:hover:text-gray-100'"
              @click="changeSection(null)"
            >
              全区
            </button>
            <button
              v-for="section in sections"
              :key="section.id"
              class="shrink-0 rounded-md px-3 py-1.5 text-sm transition-colors"
              :class="String(sectionId || '') === String(section.id) ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/30 dark:text-brandNight-300' : 'text-gray-500 hover:bg-gray-50 hover:text-gray-800 dark:text-gray-400 dark:hover:bg-gray-700/60 dark:hover:text-gray-100'"
              @click="changeSection(section.id)"
            >
              {{ section.name }}
            </button>
          </div>
        </div>

        <div
          class="group block cursor-pointer transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/40"
          role="link"
          tabindex="0"
          @click="openThread(topOne.id)"
          @keyup.enter="openThread(topOne.id)"
        >
          <div class="grid grid-cols-[minmax(0,1fr)_80px] gap-3 p-4 pb-3 sm:grid-cols-[minmax(0,1fr)_96px]">
            <div class="min-h-[92px]">
              <div>
                <div class="mb-2 flex items-start gap-3">
                  <div class="flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-brandDay-600 text-base font-bold text-white dark:bg-brandNight-500 dark:text-gray-950">
                    {{ rankAt(0) }}
                  </div>
                  <div class="min-w-0">
                    <div class="text-xs font-medium text-gray-500 dark:text-gray-400">{{ topOne.sectionName || topOne.sectionId || '全区' }}</div>
                    <div class="line-clamp-2 text-base font-bold text-gray-900 transition-colors group-hover:text-brandDay-700 dark:text-gray-100 dark:group-hover:text-brandNight-300 sm:text-lg">
                      {{ topOne.title }}
                    </div>
                  </div>
                </div>
                <p class="line-clamp-2 text-xs leading-5 text-gray-600 dark:text-gray-300 sm:text-sm">{{ excerpt(topOne) }}</p>
              </div>
            </div>
            <div class="flex flex-col items-end justify-between gap-2">
              <div class="relative h-[60px] w-[80px] overflow-hidden rounded-md border border-gray-100 bg-gray-100 dark:border-gray-700 dark:bg-gray-900 sm:h-[72px] sm:w-[96px]">
                <template v-if="firstImageUrl(topOne)">
                  <img :src="firstImageUrl(topOne)" alt="" class="absolute inset-0 h-full w-full object-cover opacity-45 blur-sm scale-110" />
                  <img :src="firstImageUrl(topOne)" alt="封面" loading="lazy" class="relative z-10 h-full w-full object-cover" />
                </template>
                <div v-else class="flex h-full items-center justify-center px-2 text-center text-xs text-gray-400 dark:text-gray-500">
                  {{ topOne.sectionName || '热门帖子' }}
                </div>
              </div>
              <div class="text-right">
                <div class="text-base font-bold text-gray-950 dark:text-gray-100">{{ Number(topOne.hotScore || 0) }}</div>
                <div class="text-xs text-gray-500">热度</div>
              </div>
            </div>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-t border-gray-50 px-4 pb-3 pt-2 text-xs text-gray-500 dark:border-gray-700/60 dark:text-gray-400">
            <router-link
              :to="authorProfileTo(topOne)"
              class="flex min-w-0 items-center gap-1.5 rounded px-1 py-0.5 font-medium text-gray-600 hover:bg-gray-100 hover:text-brandDay-700 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-brandNight-300"
              @click.stop
            >
              <img :src="authorAvatarUrl(topOne)" alt="" class="h-5 w-5 shrink-0 rounded-full object-cover bg-gray-100 dark:bg-gray-700" />
              <span class="truncate">作者: {{ authorName(topOne) }}</span>
            </router-link>
            <span class="flex flex-wrap justify-end gap-x-3 gap-y-1">
              <span>回复 {{ Number(topOne.replyCount || 0) }}</span>
              <span>点赞 {{ Number(topOne.likeCount || 0) }}</span>
              <span>{{ formatDate(topOne.createdAt) }}</span>
            </span>
          </div>
        </div>
      </div>

      <div v-if="topNext.length" class="grid gap-4 md:grid-cols-2">
        <div
          v-for="(thread, index) in topNext"
          :key="thread.id"
          class="group cursor-pointer overflow-hidden rounded-lg border border-gray-100 bg-white shadow-sm transition-colors hover:border-brandDay-200 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700"
          role="link"
          tabindex="0"
          @click="openThread(thread.id)"
          @keyup.enter="openThread(thread.id)"
        >
          <div class="grid grid-cols-[minmax(0,1fr)_88px] gap-3 p-4 pb-3 sm:grid-cols-[minmax(0,1fr)_104px]">
            <div class="min-w-0">
              <div>
                <div class="mb-2 flex items-start gap-2">
                  <div class="flex h-8 w-8 shrink-0 items-center justify-center rounded-md border border-gray-100 bg-gray-50 text-sm font-bold text-gray-700 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-100">
                    {{ rankAt(index + 1) }}
                  </div>
                  <h2 class="line-clamp-2 min-w-0 text-base font-semibold text-gray-900 transition-colors group-hover:text-brandDay-700 dark:text-gray-100 dark:group-hover:text-brandNight-300">{{ thread.title }}</h2>
                </div>
                <p class="line-clamp-2 text-sm text-gray-500 dark:text-gray-400">{{ excerpt(thread) }}</p>
              </div>
            </div>
            <div class="flex flex-col items-end justify-between gap-2">
              <div class="relative h-[64px] w-[88px] overflow-hidden rounded-md border border-gray-100 bg-gray-100 dark:border-gray-700 dark:bg-gray-900 sm:h-[76px] sm:w-[104px]">
                <template v-if="firstImageUrl(thread)">
                  <img :src="firstImageUrl(thread)" alt="" class="absolute inset-0 h-full w-full object-cover opacity-40 blur-sm scale-110" />
                  <img :src="firstImageUrl(thread)" alt="封面" loading="lazy" class="relative z-10 h-full w-full object-cover" />
                </template>
                <div v-else class="flex h-full items-center justify-center px-2 text-center text-xs text-gray-400 dark:text-gray-500">{{ thread.sectionName || '热门帖子' }}</div>
              </div>
              <div class="text-right">
                <div class="text-base font-bold text-gray-950 dark:text-gray-100">{{ Number(thread.hotScore || 0) }}</div>
                <div class="text-xs text-gray-500">热度</div>
              </div>
            </div>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-x-2 gap-y-2 border-t border-gray-50 px-4 pb-3 pt-2 text-xs text-gray-500 dark:border-gray-700/60 dark:text-gray-400">
            <router-link
              :to="authorProfileTo(thread)"
              class="flex min-w-0 items-center gap-1.5 rounded px-1 py-0.5 font-medium text-gray-600 hover:bg-gray-100 hover:text-brandDay-700 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-brandNight-300"
              @click.stop
            >
              <img :src="authorAvatarUrl(thread)" alt="" class="h-5 w-5 shrink-0 rounded-full object-cover bg-gray-100 dark:bg-gray-700" />
              <span class="truncate">作者: {{ authorName(thread) }}</span>
            </router-link>
            <span class="flex flex-wrap justify-end gap-x-2 gap-y-1">
              <span class="rounded bg-gray-100 px-2 py-0.5 dark:bg-gray-700">{{ thread.sectionName || thread.sectionId || '全区' }}</span>
              <span>回复 {{ Number(thread.replyCount || 0) }}</span>
              <span>点赞 {{ Number(thread.likeCount || 0) }}</span>
            </span>
          </div>
        </div>
      </div>

      <div v-if="restItems.length" class="overflow-hidden rounded-lg border border-gray-100 bg-white dark:border-gray-700 dark:bg-gray-800">
        <ul class="divide-y divide-gray-100 dark:divide-gray-700">
          <li v-for="(thread, index) in restItems" :key="thread.id" class="p-4 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/40">
            <router-link :to="`/threads/${thread.id}`" class="flex gap-4">
              <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-md border text-base font-bold text-gray-500 dark:border-gray-700 dark:text-gray-300">
                {{ rankAt(index + 3) }}
              </div>
              <div
                v-if="firstImageUrl(thread)"
                class="relative hidden h-24 w-36 shrink-0 overflow-hidden rounded-md border border-gray-100 bg-gray-50 dark:border-gray-700 dark:bg-gray-900 sm:block"
              >
                <img :src="firstImageUrl(thread)" alt="" class="absolute inset-0 h-full w-full object-cover opacity-45 blur-sm scale-110" />
                <img :src="firstImageUrl(thread)" alt="封面" loading="lazy" class="relative z-10 h-full w-full object-contain" />
              </div>
              <div class="min-w-0 flex-1">
                <div class="flex flex-col gap-2 md:flex-row md:items-start md:justify-between">
                  <div class="min-w-0">
                    <h2 class="truncate text-base font-semibold text-gray-900 dark:text-gray-100">{{ thread.title }}</h2>
                    <p class="mt-1 line-clamp-2 text-sm text-gray-500 dark:text-gray-400">{{ excerpt(thread) }}</p>
                  </div>
                  <div class="shrink-0 text-left md:text-right">
                    <div class="text-lg font-bold text-brandDay-700 dark:text-brandNight-300">{{ Number(thread.hotScore || 0) }}</div>
                    <div class="text-xs text-gray-400">热度</div>
                  </div>
                </div>
                <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
                  <span class="rounded bg-gray-100 px-2 py-0.5 dark:bg-gray-700">{{ thread.sectionName || thread.sectionId || '全区' }}</span>
                  <span>{{ thread.authorNickname || thread.authorUsername || thread.authorId }}</span>
                  <span>回复 {{ Number(thread.replyCount || 0) }}</span>
                  <span>点赞 {{ Number(thread.likeCount || 0) }}</span>
                  <span>{{ formatDate(thread.createdAt) }}</span>
                </div>
              </div>
            </router-link>
          </li>
        </ul>
        <div class="flex items-center justify-between border-t border-gray-100 px-5 py-4 dark:border-gray-700">
          <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ total }} 条 · 每页 {{ size }} 条</div>
          <div class="flex items-center gap-2">
            <button
              class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
              :disabled="loading || page <= 1"
              @click="setPage(page - 1)"
            >
              上一页
            </button>
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
              :disabled="loading || page >= totalPages"
              @click="setPage(page + 1)"
            >
              下一页
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

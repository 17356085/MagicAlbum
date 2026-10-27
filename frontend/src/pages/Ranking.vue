<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listSections } from '@/api/sections'
import { listThreadRanking } from '@/api/threads'
import { getSingleQueryValue } from '@/utils/router'
import { normalizeImageUrl } from '@/utils/image'
import type { Id, Section, Thread } from '@/types'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const sectionsLoading = ref(false)
const error = ref('')
const sectionsError = ref('')
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

let rankingRequestSeq = 0
let sectionsRequestSeq = 0
let disposed = false
let sectionsLoaded = false

function normalizePage(value: Parameters<typeof getSingleQueryValue>[0]): number {
  const raw = getSingleQueryValue(value)
  const parsed = raw ? Number(raw) : 1
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 1
}

function normalizeSectionId(value: Parameters<typeof getSingleQueryValue>[0]): Id | null {
  const raw = getSingleQueryValue(value).trim()
  if (!/^\d+$/.test(raw)) return null
  const canonical = raw.replace(/^0+/, '')
  if (!canonical) return null
  const parsed = Number(canonical)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : canonical
}

function readQuery(): { sectionId: Id | null; page: number } {
  const nextSectionId = normalizeSectionId(route.query.sectionId)
  const nextPage = normalizePage(route.query.page)
  sectionId.value = nextSectionId
  page.value = nextPage
  return { sectionId: nextSectionId, page: nextPage }
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
  if (disposed || sectionsLoaded || sectionsLoading.value) return
  const requestSeq = ++sectionsRequestSeq
  sectionsLoading.value = true
  sectionsError.value = ''
  try {
    const data = await listSections({ size: 50 })
    if (disposed || requestSeq !== sectionsRequestSeq) return
    sections.value = data.items
    sectionsLoaded = true
  } catch (_) {
    if (!disposed && requestSeq === sectionsRequestSeq) sectionsError.value = '加载分区失败'
  } finally {
    if (!disposed && requestSeq === sectionsRequestSeq) sectionsLoading.value = false
  }
}

async function loadRanking(): Promise<void> {
  if (disposed) return
  const requestSeq = ++rankingRequestSeq
  const query = readQuery()
  loading.value = true
  error.value = ''
  try {
    const data = await listThreadRanking({
      sectionId: query.sectionId || undefined,
      page: query.page,
      size: size.value,
    })
    if (disposed || requestSeq !== rankingRequestSeq) return
    items.value = data.items
    total.value = data.total
    page.value = data.page
    size.value = data.size
  } catch (_) {
    if (!disposed && requestSeq === rankingRequestSeq) error.value = '加载排行榜失败'
  } finally {
    if (!disposed && requestSeq === rankingRequestSeq) loading.value = false
  }
}

function retrySections(): void {
  void loadSections()
}

function retryRanking(): void {
  if (!loading.value) void loadRanking()
}

function excerpt(thread: Thread): string {
  const text = String(thread.content || thread.contentMd || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^)]+\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/[#>*_`~\-[\]]/g, '')
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

onMounted(() => {
  void loadSections()
})

watch(() => {
  const normalizedSectionId = normalizeSectionId(route.query.sectionId)
  return `${normalizedSectionId === null ? '' : String(normalizedSectionId)}|${normalizePage(route.query.page)}`
}, () => {
  void loadRanking()
}, { immediate: true })

watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

onUnmounted(() => {
  disposed = true
  rankingRequestSeq += 1
  sectionsRequestSeq += 1
})
</script>

<template>
  <section class="ui-page ranking-page space-y-4">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">排行榜</h1>
        <p class="ui-description hidden lg:block">看看社区里正在获得回应和关注的帖子。</p>
      </div>
    </header>
    <div class="ui-panel overflow-hidden" data-testid="ranking-filters">
      <div class="flex flex-wrap items-center gap-3 border-b border-gray-100 px-4 py-3 dark:border-gray-700">
        <div class="flex min-w-0 flex-1 gap-2 overflow-x-auto ui-scroll pb-1">
        <button
          type="button"
          class="ui-button shrink-0"
          :aria-pressed="!sectionId"
          :class="!sectionId ? 'border-[var(--ui-accent)] bg-[var(--ui-accent-soft)] text-[var(--ui-accent)]' : ''"
          @click="changeSection(null)"
        >
          全区
        </button>
        <button
          v-for="section in sections"
          :key="section.id"
          type="button"
          class="ui-button shrink-0"
          :aria-pressed="String(sectionId || '') === String(section.id)"
          :class="String(sectionId || '') === String(section.id) ? 'border-[var(--ui-accent)] bg-[var(--ui-accent-soft)] text-[var(--ui-accent)]' : ''"
          @click="changeSection(section.id)"
        >
          {{ section.name }}
        </button>
        </div>
        <span v-if="sectionsLoading" class="text-xs text-[var(--ui-muted)]" role="status">正在加载分区...</span>
        <div v-if="sectionsError" class="flex items-center gap-2 text-sm text-[var(--ui-danger)]" data-testid="sections-error" role="alert">
          <span>{{ sectionsError }}</span>
          <button type="button" class="ui-button" @click="retrySections">重试分区</button>
        </div>
      </div>
    </div>
    <div v-if="loading" class="ui-panel ui-empty" role="status">正在加载...</div>
    <div v-else-if="error" class="ui-error" data-testid="ranking-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="ui-button ml-3" @click="retryRanking">重试排行榜</button>
    </div>
    <div v-else class="space-y-4">
      <div v-if="items.length === 0" class="ui-panel p-6 text-center text-[var(--ui-muted)]">暂无排行数据</div>
      <div
        v-if="topOne"
        class="ui-panel overflow-hidden"
      >
        <div
          class="group block cursor-pointer transition-colors hover:bg-[var(--ui-subtle)]"
          role="link"
          tabindex="0"
          @click="openThread(topOne.id)"
          @keyup.enter="openThread(topOne.id)"
        >
          <div class="grid gap-3 p-4 pb-3 sm:grid-cols-[minmax(0,1fr)_96px]" :class="firstImageUrl(topOne) ? 'grid-cols-[minmax(0,1fr)_80px]' : 'grid-cols-1'">
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
              <div class="relative h-[60px] w-[80px] overflow-hidden rounded-md border border-gray-100 bg-gray-100 dark:border-gray-700 dark:bg-gray-900 sm:h-[72px] sm:w-[96px]" :class="!firstImageUrl(topOne) ? 'hidden sm:block' : ''">
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
          class="ui-panel group cursor-pointer overflow-hidden"
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

      <div v-if="restItems.length" class="ui-panel overflow-hidden">
        <ul>
          <li v-for="(thread, index) in restItems" :key="thread.id" class="ui-list-row">
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
                <div class="flex flex-col gap-2 lg:flex-row md:items-start md:justify-between">
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
      </div>
      <div v-if="items.length || page > 1 || total > 0" class="ui-panel overflow-hidden flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-5">
        <div class="text-xs text-[var(--ui-muted)]">共 {{ total }} 条 · 每页 {{ size }} 条</div>
        <div class="ranking-pagination flex items-center gap-2">
          <button
            type="button"
            class="ui-button"
            :disabled="loading || page <= 1"
            @click="setPage(page - 1)"
          >
            上一页
          </button>
          <span class="hidden text-sm text-[var(--ui-muted)] lg:inline">第</span>
          <input
            v-model="inputPage"
            class="ui-field hidden w-16 text-center md:block"
            aria-label="页码"
            inputmode="numeric"
            @keyup.enter="goToInputPage"
            @blur="goToInputPage"
          />
          <span class="text-sm text-[var(--ui-muted)]"><span class="lg:hidden">{{ page }}</span> / {{ totalPages }} 页</span>
          <button
            type="button"
            class="ui-button"
            :disabled="loading || page >= totalPages"
            @click="setPage(page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
@media (max-width: 1023px) {
  .ranking-page [role="link"] { overflow-wrap: anywhere; }
  .ranking-pagination { justify-content: space-between; }
}
</style>

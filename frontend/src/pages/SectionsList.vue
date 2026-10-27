<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { listSections } from '@/api/sections'
import { listSectionPreviews } from '@/api/sectionPreviews'
import type { SectionPreviewItem } from '@/api/sectionPreviews'
import { renderInlineMarkdown, createBaseMarkdownRenderer } from '@/utils/markdownBase'
import { normalizeImageUrl } from '@/utils/image'
import type { Section } from '@/types'

const loading = ref(false)
const error = ref('')
const sections = ref<Section[]>([])
const previews = ref<Record<string, SectionPreviewItem[]>>({})
const totals = ref<Record<string, number>>({})
const previewErrors = ref<Record<string, string>>({})
const previewLoading = ref<Record<string, boolean>>({})
const previewRequestSequence = new Map<string, number>()
let loadRequestSequence = 0
let disposed = false
const titleRenderer = createBaseMarkdownRenderer({ html: false, breaks: false })
const sectionEmojiMap: Record<string, string> = {
  reading: '📚',
  tech: '💻',
  coding: '⌨️',
  food: '🍜',
  model: '🤖',
  anime: '🌸',
  music: '🎧',
  game: '🎮',
  f1: '🏁',
}

function sectionKey(section: Section): string {
  return String(section.id).replace(/^0+/, '') || '0'
}

function sectionLink(section: Section) {
  return { name: 'discover', query: { sectionId: section.id, page: 1 } }
}

function renderTitle(text: string | undefined): string {
  return renderInlineMarkdown(titleRenderer, text)
}

function sectionEmoji(section: Section): string {
  const slug = String(section.slug || '').toLowerCase()
  const name = String(section.name || '')
  if (sectionEmojiMap[slug]) return sectionEmojiMap[slug]
  if (name.includes('阅读')) return '📚'
  if (name.includes('科技') || name.includes('数码')) return '💻'
  if (name.includes('编程')) return '⌨️'
  if (name.includes('美食')) return '🍜'
  if (name.includes('模型')) return '🤖'
  if (name.includes('动画')) return '🌸'
  if (name.includes('音乐')) return '🎧'
  if (name.includes('游戏')) return '🎮'
  if (name.toLowerCase().includes('f1')) return '🏁'
  return '✨'
}

function formatTime(raw: string | undefined): string {
  if (!raw) return ''
  const time = new Date(raw)
  if (Number.isNaN(time.getTime())) return ''
  return time.toLocaleDateString()
}

function previewImageUrl(item: SectionPreviewItem): string {
  return normalizeImageUrl(item.coverUrl)
}

function isCurrentLoad(requestId: number): boolean {
  return !disposed && requestId === loadRequestSequence
}

async function loadPreview(section: Section, loadRequestId: number): Promise<void> {
  if (!isCurrentLoad(loadRequestId)) return
  const key = sectionKey(section)
  if (previewLoading.value[key]) return
  const requestId = (previewRequestSequence.get(key) || 0) + 1
  previewRequestSequence.set(key, requestId)
  previewLoading.value[key] = true
  previewErrors.value[key] = ''
  try {
    const data = await listSectionPreviews([section.id])
    if (!isCurrentLoad(loadRequestId) || previewRequestSequence.get(key) !== requestId) return
    const preview = data[0]
    if (!preview) throw new Error('分区预览响应缺少目标分区')
    previews.value[key] = preview.items
    totals.value[key] = preview.total
  } catch (_) {
    if (!isCurrentLoad(loadRequestId) || previewRequestSequence.get(key) !== requestId) return
    delete previews.value[key]
    delete totals.value[key]
    previewErrors.value[key] = '该分区帖子加载失败，请点击重试'
  } finally {
    if (isCurrentLoad(loadRequestId) && previewRequestSequence.get(key) === requestId) {
      previewLoading.value[key] = false
    }
  }
}

function retryPreview(section: Section): void {
  if (previewLoading.value[sectionKey(section)]) return
  void loadPreview(section, loadRequestSequence)
}

async function loadPreviews(sectionsToLoad: Section[], loadRequestId: number): Promise<void> {
  if (!isCurrentLoad(loadRequestId) || sectionsToLoad.length === 0) return

  const batchRequestIds = new Map<string, number>()
  sectionsToLoad.forEach((section) => {
    const key = sectionKey(section)
    const requestId = (previewRequestSequence.get(key) || 0) + 1
    previewRequestSequence.set(key, requestId)
    batchRequestIds.set(key, requestId)
    previewLoading.value[key] = true
    previewErrors.value[key] = ''
  })

  try {
    const data = await listSectionPreviews(sectionsToLoad.map((section) => section.id))
    if (!isCurrentLoad(loadRequestId)) return
    data.forEach((preview) => {
      const key = String(preview.sectionId)
      if (previewRequestSequence.get(key) !== batchRequestIds.get(key)) return
      previews.value[key] = preview.items
      totals.value[key] = preview.total
      previewLoading.value[key] = false
    })
  } catch (_) {
    if (!isCurrentLoad(loadRequestId)) return
    batchRequestIds.forEach((requestId, key) => {
      if (previewRequestSequence.get(key) !== requestId) return
      delete previews.value[key]
      delete totals.value[key]
      previewErrors.value[key] = '该分区帖子加载失败，请点击重试'
      previewLoading.value[key] = false
    })
  }
}

async function load(): Promise<void> {
  const requestId = ++loadRequestSequence
  loading.value = true
  error.value = ''
  previews.value = {}
  totals.value = {}
  previewErrors.value = {}
  previewLoading.value = {}
  previewRequestSequence.clear()
  try {
    const data = await listSections({})
    if (!isCurrentLoad(requestId)) return
    sections.value = data.items
    void loadPreviews(data.items, requestId)
  } catch (_) {
    if (isCurrentLoad(requestId)) error.value = '加载分区失败'
  } finally {
    if (isCurrentLoad(requestId)) loading.value = false
  }
}

onMounted(load)
onUnmounted(() => {
  disposed = true
  loadRequestSequence += 1
  previewRequestSequence.clear()
})
</script>

<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">分区</h1>
        <p class="ui-description">按兴趣浏览社区里的讨论与最新帖子。</p>
      </div>
    </header>

    <div v-if="loading" class="ui-panel ui-empty" role="status">正在加载...</div>
    <div v-else-if="error" class="ui-error" role="alert">
      <p>{{ error }}</p>
      <button type="button" class="ui-button mt-3" @click="load">重试</button>
    </div>
    <div v-else-if="sections.length === 0" class="ui-panel ui-empty" role="status">
      <h2>暂无分区</h2>
      <p>稍后再来看看新的讨论空间。</p>
    </div>

    <ul v-else class="ui-panel overflow-hidden">
      <li
        v-for="section in sections"
        :key="section.id"
        class="ui-list-row"
      >
        <div class="grid gap-4 md:grid-cols-[220px_minmax(0,1fr)] xl:grid-cols-[260px_minmax(0,1fr)]">
          <div class="min-w-0 md:pr-1">
            <div class="flex items-center gap-3">
              <span class="shrink-0 text-2xl leading-none">{{ sectionEmoji(section) }}</span>
              <router-link :to="sectionLink(section)" class="truncate text-lg font-bold text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
                {{ section.name }}
              </router-link>
              <span v-if="section.slug" class="ui-tag shrink-0">
                {{ section.slug }}
              </span>
            </div>
              <p class="mt-2 line-clamp-2 text-sm leading-6 text-[var(--ui-muted)]">
              {{ section.description || '暂无分区简介' }}
            </p>
            <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-[var(--ui-muted)]">
              <span v-if="previewLoading[sectionKey(section)]">帖子数加载中...</span>
              <span v-else-if="previewErrors[sectionKey(section)]">帖子数暂不可用</span>
              <span v-else-if="totals[sectionKey(section)] !== undefined">{{ totals[sectionKey(section)] }} 篇帖子</span>
              <router-link
                :to="sectionLink(section)"
                class="ui-button"
              >
                进入分区
              </router-link>
            </div>
          </div>

          <div class="min-w-0 px-1 py-1 md:border-l md:border-[var(--ui-border)] md:pl-4">
            <div class="mb-2 flex items-center justify-between text-xs text-[var(--ui-muted)]">
              <span class="font-medium">最新帖子</span>
              <router-link :to="sectionLink(section)" class="font-medium text-[var(--ui-accent)] hover:underline">查看更多</router-link>
            </div>
            <div v-if="previewLoading[sectionKey(section)]" class="border-t border-dashed border-[var(--ui-border)] px-2 py-5 text-center text-sm text-[var(--ui-muted)]" role="status">
              正在加载帖子...
            </div>
            <div v-else-if="previewErrors[sectionKey(section)]" class="ui-error mt-2" role="alert">
              <p>{{ previewErrors[sectionKey(section)] }}</p>
              <button type="button" class="ui-button mt-3" @click="retryPreview(section)">重试</button>
            </div>
            <ul v-else-if="(previews[sectionKey(section)] || []).length" class="grid divide-y divide-[var(--ui-border)] sm:grid-cols-2 sm:divide-x sm:divide-y-0">
              <li
                v-for="thread in previews[sectionKey(section)]"
                :key="thread.id"
                class="min-w-0"
              >
                <router-link
                  :to="`/threads/${thread.id}`"
                  class="group flex min-w-0 items-center gap-3 px-2 py-3 hover:bg-[var(--ui-subtle)] sm:px-3 sm:py-2"
                >
                  <div
                    v-if="previewImageUrl(thread)"
                    class="relative h-11 w-14 shrink-0 overflow-hidden rounded border border-[var(--ui-border)] bg-[var(--ui-subtle)]"
                  >
                    <img :src="previewImageUrl(thread)" alt="" class="absolute inset-0 h-full w-full object-cover opacity-40 blur-sm scale-110" />
                    <img :src="previewImageUrl(thread)" alt="封面" loading="lazy" class="relative z-10 h-full w-full object-contain" />
                  </div>
                  <div class="min-w-0 flex-1">
                     <div class="block truncate text-sm font-medium leading-5 text-[var(--ui-ink)] group-hover:text-[var(--ui-accent)]" v-html="renderTitle(thread.title)" />
                     <div class="mt-0.5 flex items-center gap-2 text-xs leading-4 text-[var(--ui-muted)]">
                      <span class="truncate">{{ thread.authorNickname || thread.authorUsername || thread.authorId || '匿名用户' }}</span>
                      <span v-if="formatTime(thread.createdAt)">·</span>
                      <span>{{ formatTime(thread.createdAt) }}</span>
                    </div>
                  </div>
                </router-link>
              </li>
            </ul>
             <div v-else class="border-t border-dashed border-[var(--ui-border)] px-2 py-5 text-center text-sm text-[var(--ui-muted)]">
              暂无帖子
            </div>
          </div>
        </div>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listSections } from '@/api/sections'
import { listThreads } from '@/api/threads'
import { renderInlineMarkdown, createMarkdownRenderer } from '@/utils/markdown'
import { normalizeImageUrl } from '@/utils/image'
import type { PageResult, Section, Thread } from '@/types'

const loading = ref(false)
const error = ref('')
const sections = ref<Section[]>([])
const previews = ref<Record<string, Thread[]>>({})
const totals = ref<Record<string, number>>({})
const titleRenderer = createMarkdownRenderer({ html: false, breaks: false, highlight: false })
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

function normalizePage(data: PageResult<Thread> | Thread[]): PageResult<Thread> {
  if (Array.isArray(data)) {
    return { items: data, page: 1, size: data.length, total: data.length }
  }
  return data || { items: [], page: 1, size: 0, total: 0 }
}

function sectionKey(section: Section): string {
  return String(section.id)
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

function firstImageUrl(thread: Thread): string {
  const text = String(thread.content || thread.contentMd || '')
  const markdown = text.match(/!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/)
  if (markdown?.[1]) return normalizeImageUrl(markdown[1])
  const html = text.match(/<img[^>]+src=["']([^"']+)["']/i)
  if (html?.[1]) return normalizeImageUrl(html[1])
  return ''
}

function formatTime(raw: string | undefined): string {
  if (!raw) return ''
  const time = new Date(raw)
  if (Number.isNaN(time.getTime())) return ''
  return time.toLocaleDateString()
}

async function loadPreview(section: Section): Promise<void> {
  const key = sectionKey(section)
  try {
    const data = await listThreads({ sectionId: section.id, page: 1, size: 4 })
    const page = normalizePage(data)
    previews.value[key] = page.items || []
    totals.value[key] = Number(page.total || 0)
  } catch (_) {
    previews.value[key] = []
    totals.value[key] = 0
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  previews.value = {}
  totals.value = {}
  try {
    const data = await listSections({})
    const items = Array.isArray(data) ? data : (data.items || [])
    sections.value = items
    await Promise.all(items.map((section) => loadPreview(section)))
  } catch (_) {
    error.value = '加载分区失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-4">
    <div v-if="loading" class="rounded-xl bg-white py-12 text-center text-sm text-gray-500 shadow-sm dark:bg-gray-800 dark:text-gray-300">
      正在加载...
    </div>
    <div v-else-if="error" class="rounded-xl bg-white py-12 text-center text-sm text-red-600 shadow-sm dark:bg-gray-800">
      {{ error }}
    </div>
    <div v-else-if="sections.length === 0" class="rounded-xl bg-white py-12 text-center text-sm text-gray-500 shadow-sm dark:bg-gray-800 dark:text-gray-300">
      暂无分区
    </div>

    <ul v-else class="space-y-3">
      <li
        v-for="section in sections"
        :key="section.id"
        class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm transition-colors hover:border-brandDay-200 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700"
      >
        <div class="grid gap-4 md:grid-cols-[220px_minmax(0,1fr)] xl:grid-cols-[260px_minmax(0,1fr)]">
          <div class="min-w-0 md:pr-1">
            <div class="flex items-center gap-3">
              <span class="shrink-0 text-2xl leading-none">{{ sectionEmoji(section) }}</span>
              <router-link
                :to="sectionLink(section)"
                class="truncate text-lg font-bold text-gray-900 transition-colors hover:text-brandDay-600 dark:text-gray-100 dark:hover:text-brandNight-300"
              >
                {{ section.name }}
              </router-link>
              <span v-if="section.slug" class="shrink-0 rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-500 dark:bg-gray-700 dark:text-gray-300">
                {{ section.slug }}
              </span>
            </div>
            <p class="mt-2 line-clamp-2 text-sm leading-6 text-gray-600 dark:text-gray-300">
              {{ section.description || '暂无分区简介' }}
            </p>
            <div class="mt-3 flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              <span>{{ totals[sectionKey(section)] || 0 }} 篇帖子</span>
              <router-link
                :to="sectionLink(section)"
                class="rounded border border-gray-200 px-2.5 py-1 text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700"
              >
                进入分区
              </router-link>
            </div>
          </div>

          <div class="min-w-0 rounded-lg bg-gray-50 px-3 py-2.5 dark:bg-gray-900/40">
            <div class="mb-2 flex items-center justify-between text-xs text-gray-500 dark:text-gray-400">
              <span class="font-medium">最新帖子</span>
              <router-link :to="sectionLink(section)" class="hover:text-brandDay-600 dark:hover:text-brandNight-300">查看更多</router-link>
            </div>
            <ul v-if="(previews[sectionKey(section)] || []).length" class="grid gap-2 sm:grid-cols-2">
              <li
                v-for="thread in previews[sectionKey(section)]"
                :key="thread.id"
                class="min-w-0"
              >
                <router-link
                  :to="`/threads/${thread.id}`"
                  class="group flex min-w-0 items-center gap-3 rounded-md bg-white px-3 py-2 transition-colors hover:bg-gray-50 dark:bg-gray-800 dark:hover:bg-gray-700/60"
                >
                  <div
                    v-if="firstImageUrl(thread)"
                    class="relative h-11 w-14 shrink-0 overflow-hidden rounded border border-gray-100 bg-gray-100 dark:border-gray-700 dark:bg-gray-900"
                  >
                    <img :src="firstImageUrl(thread)" alt="" class="absolute inset-0 h-full w-full object-cover opacity-40 blur-sm scale-110" />
                    <img :src="firstImageUrl(thread)" alt="封面" loading="lazy" class="relative z-10 h-full w-full object-contain" />
                  </div>
                  <div class="min-w-0 flex-1">
                    <div class="block truncate text-sm font-medium leading-5 text-gray-800 transition-colors group-hover:text-brandDay-600 dark:text-gray-100 dark:group-hover:text-brandNight-300" v-html="renderTitle(thread.title)" />
                    <div class="mt-0.5 flex items-center gap-2 text-xs leading-4 text-gray-400">
                      <span class="truncate">{{ thread.authorNickname || thread.authorUsername || thread.authorId || '匿名用户' }}</span>
                      <span v-if="formatTime(thread.createdAt)">·</span>
                      <span>{{ formatTime(thread.createdAt) }}</span>
                    </div>
                  </div>
                </router-link>
              </li>
            </ul>
            <div v-else class="rounded-md bg-white px-3 py-5 text-center text-sm text-gray-400 dark:bg-gray-800">
              暂无帖子
            </div>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>

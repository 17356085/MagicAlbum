<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listThreads } from '@/api/threads'
import { normalizeImageUrl } from '@/utils/image'
import { createMarkdownRenderer, renderInlineMarkdown } from '@/utils/markdown'
import { getSingleQueryValue } from '@/utils/router'
import { formatAbsoluteTime } from '@/composables/time'
import type { Id, Thread } from '@/types'

const loading = ref(false)
const error = ref('')
const items = ref<Thread[]>([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const route = useRoute()
const router = useRouter()
const sectionId = ref<Id | null>(null)
const currentSectionName = ref('')
const q = ref('')
const tag = ref('')
// 手动页码输入与校验
const inputPage = ref('')

const totalPages = computed(() => {
  const s = Number(size.value || 20)
  const t = Number(total.value || 0)
  return Math.max(1, Math.ceil(t / s))
})

function setPage(p: number) {
  const next = Math.min(Math.max(1, p), totalPages.value)
  page.value = next
  router.push({ name: 'discover', query: { ...route.query, page: next, sectionId: route.query.sectionId } })
}

function prevPage() {
  setPage(Number(page.value) - 1)
}

function nextPage() {
  setPage(Number(page.value) + 1)
}

function goToInputPage() {
  const raw = String(inputPage.value || '').trim()
  if (!raw) {
    return
  }
  if (!/^\d+$/.test(raw)) {
    return
  }
  const n = Number(raw)
  if (!Number.isInteger(n)) {
    return
  }
  if (n < 1) {
    return
  }
  const target = Math.min(n, totalPages.value)
  setPage(target)
}

function sortThreadsByCreatedAt(list: Thread[]): Thread[] {
  return [...list].sort((a, b) => {
    const ta = a?.createdAt ? new Date(a.createdAt).getTime() : 0
    const tb = b?.createdAt ? new Date(b.createdAt).getTime() : 0
    const diff = tb - ta
    if (diff !== 0) return diff
    return Number(b?.id || 0) - Number(a?.id || 0)
  })
}

let loadRequestSeq = 0

async function load() {
  const requestSeq = ++loadRequestSeq
  loading.value = true
  error.value = ''
  try {
    const sidRaw = route.query.sectionId ? getSingleQueryValue(route.query.sectionId) : ''
    const sid = sidRaw ? Number(sidRaw) : undefined
    const rp = route.query.page ? Number(getSingleQueryValue(route.query.page)) : 1
    const rq = route.query.q ? getSingleQueryValue(route.query.q) : ''
    const rt = route.query.tag ? getSingleQueryValue(route.query.tag) : ''
    page.value = isNaN(rp) ? 1 : rp
    inputPage.value = String(page.value || '')
    sectionId.value = sid || null
    q.value = rq
    tag.value = rt
    const data = await listThreads({ q: rq, tag: rt, page: page.value, size: size.value, sectionId: sid })
    if (requestSeq !== loadRequestSeq) return
    items.value = sortThreadsByCreatedAt(data.items)
    currentSectionName.value = data.items.length > 0 ? (data.items[0].sectionName || '') : ''
    total.value = data.total
    page.value = data.page
    size.value = data.size
  } catch (_) {
    if (requestSeq === loadRequestSeq) error.value = '加载帖子失败'
  } finally {
    if (requestSeq === loadRequestSeq) loading.value = false
  }
}

onMounted(load)
watch(page, (val) => {
  inputPage.value = String(val || '')
})
watch(() => route.query.sectionId, () => {
  page.value = 1
  load()
})
watch(() => route.query.page, () => {
  load()
})
watch(() => route.query.q, () => {
  page.value = 1
  load()
})
watch(() => route.query.tag, () => {
  page.value = 1
  load()
})

function firstImageUrl(mdText: string | undefined): string | undefined {
  if (!mdText) return undefined
  const text = String(mdText)
  // Markdown 图片语法 ![alt](url "title")
  const m1 = text.match(/!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/)
  if (m1 && m1[1]) return normalizeImageUrl(m1[1])
  // HTML <img src="url">
  const m2 = text.match(/<img[^>]+src=["']([^"']+)["']/i)
  if (m2 && m2[1]) return normalizeImageUrl(m2[1])
  return undefined
}

// 提取纯文本摘要：移除图片、链接与 HTML 标签
function textExcerpt(mdText: string | undefined, maxLen = 180) {
  if (!mdText) return ''
  let s = String(mdText)
  // 移除 Markdown 代码块（```...``` 或 ~~~...~~~）与缩进代码块、行内代码
  s = s.replace(/```[\s\S]*?```/g, '')
  s = s.replace(/~~~[\s\S]*?~~~/g, '')
  s = s.replace(/^(?: {4}|\t).*$\n?/gm, '')
  s = s.replace(/`[^`]*`/g, '')
  // 移除 Markdown 图片
  s = s.replace(/!\[[^\]]*\]\([^)]+\)/g, '')
  // 将 Markdown 链接替换为可读文本
  s = s.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '$1')
  // 摘要保持纯文本：去掉标题、引用、列表和强调标记，避免正文结构泄露到列表层级。
  s = s.replace(/^\s{0,3}(?:#{1,6}\s+|>\s?)/gm, '')
  s = s.replace(/^\s{0,3}(?:[-*+]\s+|\d+[.)]\s+)/gm, '')
  s = s.replace(/^\s{0,3}(?:-{3,}|_{3,}|\*{3,})\s*$/gm, '')
  s = s.replace(/(\*\*|__|~~)/g, '')
  s = s.replace(/(^|[\s([{])[*_](?=\S)/gm, '$1')
  s = s.replace(/[*_](?=$|[\s)\]}.,!?;:])/gm, '')
  // 移除 HTML 图片与标签
  s = s.replace(/<img[^>]*>/gi, '')
  s = s.replace(/<[^>]+>/g, '')
  // 折叠空白
  s = s.replace(/\s+/g, ' ').trim()
  if (s.length <= maxLen) return s
  return s.slice(0, maxLen) + '...'
}

// 预览图不再支持展开/收起，统一在固定最大尺寸内完整显示

// 标题行内 Markdown 渲染（仅文本行内，支持删除线），并进行清理
const mdTitle = createMarkdownRenderer({ html: false, breaks: false, highlight: false })
function renderTitleMarkdownHtml(text: string | undefined) {
  return renderInlineMarkdown(mdTitle, text)
}

function getAuthorAvatarUrl(thread: Thread): string {
  return normalizeImageUrl(thread.authorAvatarUrl || '')
}
</script>

<template>
  <div class="ui-page mx-auto max-w-5xl">
    <header class="ui-page-header">
      <div class="min-w-0">
        <h1 class="ui-title">发现</h1>
        <p class="ui-description">浏览社区里正在发生的讨论，按分区、关键词或标签找到下一篇想读的帖子。</p>
      </div>
      <div v-if="sectionId !== null && currentSectionName" class="ui-muted text-sm">当前分区：{{ currentSectionName }}</div>
    </header>

    <div v-if="q || tag" class="ui-toolbar mb-4 rounded-xl border border-[var(--ui-border)] bg-[var(--ui-surface)]" aria-label="当前筛选">
      <span class="ui-muted text-sm font-medium">当前筛选</span>
      <span v-if="q" class="ui-tag">搜索关键字：{{ q }}</span>
      <span v-if="tag" class="ui-tag">标签：#{{ tag }}</span>
    </div>

    <div v-if="loading" class="ui-panel" role="status" aria-live="polite">
      <div class="ui-empty">
        <h2>正在加载...</h2>
        <div class="mx-auto mt-4 max-w-2xl space-y-3 text-left" aria-hidden="true">
          <div class="ui-skeleton h-5 w-2/3" />
          <div class="ui-skeleton h-4 w-full" />
          <div class="ui-skeleton h-4 w-5/6" />
        </div>
      </div>
    </div>
    <div v-else-if="error" class="ui-error" role="alert">{{ error }}</div>
    <div v-else-if="items.length === 0" class="ui-panel ui-empty">
      <h2>暂无帖子</h2>
      <p>换一个关键词或标签试试。</p>
    </div>
    <div v-else class="ui-panel overflow-hidden">
      <ul>
        <li v-for="t in items" :key="t.id" class="ui-list-row">
          <div class="flex min-w-0 flex-col items-start gap-5 md:flex-row">
            <div v-if="firstImageUrl(t.content)" class="h-40 w-full shrink-0 overflow-hidden rounded-lg border border-[var(--ui-border)] bg-[var(--ui-subtle)] md:w-56">
              <img :src="firstImageUrl(t.content)" alt="帖子图片" loading="lazy" class="h-full w-full object-contain" />
            </div>

            <div class="min-w-0 flex-1">
              <router-link :to="`/threads/${t.id}`" class="group block">
                <h2 class="mb-2 line-clamp-2 break-words text-lg font-semibold leading-snug text-[var(--ui-ink)] group-hover:text-[var(--ui-accent)]" v-html="renderTitleMarkdownHtml(t.title)" />
              </router-link>
              <p class="line-clamp-3 text-sm leading-6 text-[var(--ui-muted)]">{{ textExcerpt(t.content) }}</p>
              <div v-if="t.tags?.length" class="mt-3 flex flex-wrap gap-2">
                <router-link
                  v-for="tag in t.tags"
                  :key="tag"
                  :to="{ name: 'discover', query: { tag, page: 1, sectionId: t.sectionId } }"
                  class="ui-tag"
                >
                  #{{ tag }}
                </router-link>
              </div>

              <div class="mt-4 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2 text-xs text-[var(--ui-muted)]">
                <router-link :to="t.authorId ? ('/users/' + t.authorId) : '/users'" class="inline-flex min-w-0 items-center gap-2 text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
                  <img
                    :src="getAuthorAvatarUrl(t) || `https://api.dicebear.com/7.x/initials/svg?seed=${t.authorNickname || t.authorUsername || t.authorId || 'U'}`"
                    class="h-6 w-6 rounded-full bg-[var(--ui-subtle)] object-cover"
                    alt=""
                  />
                  <span class="truncate">{{ t.authorNickname || t.authorUsername || t.authorId }}</span>
                </router-link>
                <span aria-hidden="true">·</span>
                <router-link :to="{ name: 'discover', query: { sectionId: t.sectionId, page: 1 } }" class="ui-tag">
                  {{ t.sectionName || t.sectionId }}
                </router-link>
                <span aria-hidden="true">·</span>
                <time :datetime="t.createdAt">{{ formatAbsoluteTime(t.createdAt) || '时间未知' }}</time>
              </div>
            </div>
          </div>
        </li>
      </ul>
      <div class="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--ui-border)] px-4 py-4 sm:px-6">
        <div class="ui-muted text-xs">共 {{ total }} 条 · 每页 {{ size }} 条</div>
        <nav class="flex min-w-0 flex-wrap items-center gap-2" aria-label="帖子分页">
          <button class="ui-button min-h-9 px-3 py-1" :disabled="page <= 1" @click="prevPage">上一页</button>
          <label class="ui-muted flex items-center gap-2 text-sm">
            <span>第</span>
            <input
              v-model="inputPage"
              type="text"
              inputmode="numeric"
              pattern="[0-9]*"
              aria-label="页码"
              class="ui-field w-16 py-1 text-center"
              @keyup.enter="goToInputPage"
              @blur="goToInputPage"
            />
            <span>/ {{ totalPages }} 页</span>
          </label>
          <button class="ui-button min-h-9 px-3 py-1" :disabled="page >= totalPages" @click="nextPage">下一页</button>
        </nav>
      </div>
    </div>
  </div>
  </template>

<style scoped>
</style>

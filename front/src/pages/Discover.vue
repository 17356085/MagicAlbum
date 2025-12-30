<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { useRoute, useRouter } from 'vue-router'
import { listThreads } from '@/api/threads'

const loading = ref(false)
const error = ref('')
const items = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const route = useRoute()
const router = useRouter()
const sectionId = ref(null)
const currentSectionName = ref('')
const q = ref('')
// 手动页码输入与校验
const inputPage = ref('')
// 用户资料缓存：按用户ID存储 { nickname }
const profiles = ref({})
const totalPages = computed(() => {
  const s = Number(size.value || 20)
  const t = Number(total.value || 0)
  return Math.max(1, Math.ceil(t / s))
})

function setPage(p) {
  const next = Math.min(Math.max(1, p), totalPages.value)
  page.value = next
  router.push({ name: 'discover', query: { ...route.query, page: next, sectionId: route.query.sectionId } })
}

function prevPage() { setPage(Number(page.value) - 1) }
function nextPage() { setPage(Number(page.value) + 1) }

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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const sid = route.query.sectionId ? Number(route.query.sectionId) : undefined
    const rp = route.query.page ? Number(route.query.page) : 1
    const rq = route.query.q ? String(route.query.q) : ''
    page.value = isNaN(rp) ? 1 : rp
    inputPage.value = String(page.value || '')
    sectionId.value = sid || null
    q.value = rq
    const data = await listThreads({ q: rq, page: page.value, size: size.value, sectionId: sid })
    const arr = Array.isArray(data) ? data : (data.items || [])
    // 稳定倒序（同一时间戳下按 id 倒序）
    items.value = [...arr].sort((a, b) => {
      const ta = a?.createdAt ? new Date(a.createdAt).getTime() : 0
      const tb = b?.createdAt ? new Date(b.createdAt).getTime() : 0
      const diff = tb - ta
      if (diff !== 0) return diff
      return (b?.id || 0) - (a?.id || 0)
    })
    currentSectionName.value = arr.length > 0 ? (arr[0].sectionName || '') : ''
    if (!Array.isArray(data)) {
      total.value = Number(data.total || 0)
      page.value = Number(data.page || page.value)
      size.value = Number(data.size || size.value)
    }
    // 异步补充作者昵称（不阻塞列表展示）
    const authorIds = [...new Set((items.value || []).map(t => t?.authorId).filter(Boolean))]
    for (const uid of authorIds) {
      if (profiles.value[uid]?.nickname !== undefined) continue
      try {
        const p = await getUserProfile(uid)
        profiles.value[uid] = { nickname: p?.nickname || '' }
      } catch (_) {
        profiles.value[uid] = { nickname: '' }
      }
    }
  } catch (e) {
    error.value = '加载帖子失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(page, (val) => { inputPage.value = String(val || '') })
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

// 提取首张图片 URL（支持 Markdown 与 <img>）
function normalizeImageUrl(u) {
  if (!u) return null
  const url = String(u).trim()
  if (!url) return null
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
    return url
  }
  // 相对路径：拼接后端基础地址（将 VITE_API_BASE_URL 去掉 /api/v1）
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
  const backendBase = apiBase.replace(/\/api\/v1$/, '')
  if (url.startsWith('/')) return backendBase + url
  return backendBase + '/' + url
}

function firstImageUrl(mdText) {
  if (!mdText) return null
  const text = String(mdText)
  // Markdown 图片语法 ![alt](url "title")
  const m1 = text.match(/!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/)
  if (m1 && m1[1]) return normalizeImageUrl(m1[1])
  // HTML <img src="url">
  const m2 = text.match(/<img[^>]+src=["']([^"']+)["']/i)
  if (m2 && m2[1]) return normalizeImageUrl(m2[1])
  return null
}

// 提取纯文本摘要：移除图片、链接与 HTML 标签
function textExcerpt(mdText, maxLen = 180) {
  if (!mdText) return ''
  let s = String(mdText)
  // 移除 Markdown 代码块（```...``` 或 ~~~...~~~）与缩进代码块、行内代码
  s = s.replace(/```[\s\S]*?```/g, '')
  s = s.replace(/~~~[\s\S]*?~~~/g, '')
  s = s.replace(/^(?: {4}|\t).*$\n?/gm, '')
  s = s.replace(/`[^`]*`/g, '')
  // 移除 Markdown 图片
  s = s.replace(/!\[[^\]]*\]\([^\)]+\)/g, '')
  // 将 Markdown 链接替换为可读文本
  s = s.replace(/\[([^\]]+)\]\(([^\)]+)\)/g, '$1')
  // 移除 HTML 图片与标签
  s = s.replace(/<img[^>]*>/gi, '')
  s = s.replace(/<[^>]+>/g, '')
  // 折叠空白
  s = s.replace(/\s+/g, ' ').trim()
  if (s.length <= maxLen) return s
  return s.slice(0, maxLen) + '...'
}

// 预览图不再支持展开/收起，统一在固定最大尺寸内完整显示

// Markdown 预览：移除代码块与图片，仅渲染文本、行内元素
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
// 启用 GFM 删除线支持：~~text~~
try { md.enable(['strikethrough']) } catch (_) {}
function mdPreview(mdText) {
  if (!mdText) return ''
  let s = String(mdText)
  // 移除代码块（```/~~~/缩进）与行内代码，避免预览过长与样式干扰
  s = s.replace(/```[\s\S]*?```/g, '')
  s = s.replace(/~~~[\s\S]*?~~~/g, '')
  s = s.replace(/^(?: {4}|\t).*$/gm, '')
  s = s.replace(/`[^`]*`/g, '')
  // 移除图片（Markdown与HTML），避免与左侧预览图重复
  s = s.replace(/!\[[^\]]*\]\([^\)]+\)/g, '')
  s = s.replace(/<img[^>]*>/gi, '')
  const html = md.render(s)
  return DOMPurify.sanitize(html)
}

// 标题行内 Markdown 渲染（仅文本行内，支持删除线），并进行清理
const mdTitle = new MarkdownIt({ html: false, linkify: true, breaks: false })
try { mdTitle.enable(['strikethrough']) } catch (_) {}
function renderTitle(text) {
  const safe = String(text || '')
  return DOMPurify.sanitize(mdTitle.renderInline(safe))
}
</script>

<template>
  <div>
    <div v-if="sectionId" class="mb-4 text-sm text-gray-600 dark:text-gray-300">当前分区：{{ currentSectionName || ('#' + sectionId) }}</div>
    <div v-if="q" class="mb-2 text-xs text-gray-600 dark:text-gray-300">搜索关键字：{{ q }}</div>
    <div v-if="loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-else-if="items.length === 0" class="text-gray-600 dark:text-gray-300">暂无帖子</div>
      <div v-else class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm dark:bg-gray-800 dark:border-gray-700">
        <ul class="space-y-4">
          <li v-for="t in items" :key="t.id" class="group rounded-lg border border-gray-100 bg-white p-4 transition-all hover:border-brandDay-200 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700">
            <router-link :to="`/threads/${t.id}`" class="block">
              <div class="flex flex-col md:flex-row items-start gap-5">
                
                <!-- 缩略图 (左侧) -->
                <div v-if="firstImageUrl(t.content)" class="shrink-0 w-full md:w-64 h-48 md:h-40 overflow-hidden rounded-lg border border-gray-100 dark:border-gray-700 bg-gray-50 dark:bg-gray-800 relative group/img">
                   <!-- 背景：高斯模糊填充 -->
                   <img :src="firstImageUrl(t.content)" alt="" class="absolute inset-0 h-full w-full object-cover opacity-50 blur-sm scale-110" />
                   <!-- 前景：完整显示 -->
                   <img :src="firstImageUrl(t.content)" alt="封面" loading="lazy" class="relative z-10 h-full w-full object-contain transition-transform duration-500 group-hover:scale-105" style="-webkit-mask-image: radial-gradient(circle, black 60%, transparent 100%); mask-image: radial-gradient(circle, black 60%, transparent 100%);" />
                </div>

                <!-- 内容 (右侧) -->
                <div class="flex-1 min-w-0 w-full">
                  <h2 class="text-lg font-bold text-gray-800 transition-colors group-hover:text-brandDay-600 dark:text-gray-100 dark:group-hover:text-brandNight-400 line-clamp-2 mb-2.5" v-html="renderTitle(t.title)"></h2>
                  <div class="text-sm text-gray-600 line-clamp-3 dark:text-gray-300" v-html="mdPreview(t.content)"></div>
                  
                  <div class="mt-3 flex items-center gap-3 text-xs text-gray-400">
                    <router-link :to="t.authorId ? ('/users/' + t.authorId) : '/users'" class="flex items-center gap-1.5 hover:text-gray-600 dark:hover:text-gray-200">
                      <img 
                        :src="t.authorAvatar ? normalizeImageUrl(t.authorAvatar) : `https://api.dicebear.com/7.x/initials/svg?seed=${t.authorNickname || t.authorUsername || 'U'}`" 
                        class="h-5 w-5 rounded-full object-cover bg-gray-100 dark:bg-gray-700" 
                        alt=""
                      />
                      {{ t.authorNickname || t.authorUsername || t.authorId }}
                    </router-link>
                    <span>·</span>
                    <router-link :to="{ name: 'discover', query: { sectionId: t.sectionId, page: 1 } }" class="rounded bg-gray-100 px-2 py-0.5 font-medium hover:bg-gray-200 dark:bg-gray-700 dark:hover:bg-gray-600 transition-colors">
                      {{ t.sectionName || t.sectionId }}
                    </router-link>
                    <span>·</span>
                    <span>{{ new Date(t.createdAt).toLocaleString() }}</span>
                  </div>
                </div>
                
              </div>
            </router-link>
          </li>
        </ul>
        <div class="mt-4 flex items-center justify-between">
          <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ total }} 条 · 每页 {{ size }} 条</div>
          <div class="flex items-center gap-2">
            <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="page <= 1" @click="prevPage">上一页</button>
            <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
            <input
              v-model="inputPage"
              type="text"
              inputmode="numeric"
              pattern="[0-9]*"
              class="rounded border px-2 py-1 text-sm w-16 text-center bg-white dark:bg-gray-800 dark:border-gray-700 dark:text-gray-200 dark:placeholder-gray-400 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
              @keyup.enter="goToInputPage"
              @blur="goToInputPage"
            />
            <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ totalPages }} 页</span>
            <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="page >= totalPages" @click="nextPage">下一页</button>
          </div>
        </div>
      </div>
    </div>
  </div>
  </template>

<style scoped>
</style>
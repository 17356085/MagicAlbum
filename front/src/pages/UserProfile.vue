<template>
  <div class="px-0">
    <div v-if="loading" class="text-center text-gray-500">加载中...</div>
    <div v-else-if="error" class="text-center text-red-500">{{ error }}</div>
    <div v-else class="space-y-6 rounded-xl border border-gray-200 bg-white/90 p-6 shadow-sm dark:bg-gray-800/80 dark:border-gray-700">
      <!-- 返回按钮：单独一行显示在卡片顶部 -->
      <div>
<button @click="safeBack()" class="inline-flex items-center p-1 rounded text-brandDay-600 dark:text-brandNight-400 hover:bg-brandDay-50 dark:hover:bg-gray-700" aria-label="返回上一页" title="返回上一页">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5">
            <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
      <div class="flex items-center gap-4">
        
        <template v-if="profile.avatarUrl">
          <img :src="normalizeImageUrl(profile.avatarUrl)" alt="头像" class="w-20 h-20 rounded-full object-cover" />
        </template>
        <template v-else>
          <div class="w-20 h-20 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-2xl text-gray-600 dark:text-gray-300">
            {{ String(profile.nickname || profile.username || 'U').slice(0,1).toUpperCase() }}
          </div>
        </template>
        <div>
          <div class="text-xl font-semibold">{{ profile.nickname || profile.username || '未命名用户' }}</div>
          <div v-if="profile.username" class="text-sm text-gray-600 dark:text-gray-300 mt-0.5">{{ profile.username }}</div>
<div v-if="profile.homepageUrl" class="text-sm text-brandDay-600 dark:text-brandNight-400 mt-1">
            <span class="mr-1 text-gray-600 dark:text-gray-300">主页链接：</span>
            <a :href="profile.homepageUrl" target="_blank" rel="noopener">{{ profile.homepageUrl }}</a>
          </div>
          <div v-if="profile.location" class="text-sm text-gray-600 dark:text-gray-300 mt-1">所在地：{{ profile.location }}</div>
        </div>
      </div>

      <div>
        <div class="text-sm text-gray-500 mb-1">个人介绍</div>
        <template v-if="profile.bio && String(profile.bio).trim()">
          <div class="prose max-w-none dark:prose-invert text-gray-800 dark:text-gray-200" v-html="renderBio(profile.bio)"></div>
        </template>
        <template v-else>
          <div class="text-gray-800 dark:text-gray-200">这个人很神秘，什么都没有写。</div>
        </template>
        <div v-if="isMe" class="mt-3">
<router-link to="/settings" class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-1 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-500">编辑资料</router-link>
        </div>
      </div>

      <div v-if="profile.links && profile.links.length" class="space-y-2">
        <div class="text-sm text-gray-500">相关链接</div>
        <ul class="list-disc pl-6">
          <li v-for="(l, idx) in profile.links" :key="idx">
            <a :href="l.url || l" target="_blank" rel="noopener" class="text-brandDay-600 dark:text-brandNight-400">{{ l.title || l.url || l }}</a>
          </li>
        </ul>
      </div>

      <!-- Ta 的主题帖列表（每页10条，分页） -->
      <div class="pt-4">
        <div class="text-sm text-gray-500 mb-2">Ta的帖子</div>
        <div v-if="threadsLoading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
        <template v-else>
          <div v-if="threadsError" class="text-red-600 mb-3">{{ threadsError }}</div>
          <template v-if="threads.items && threads.items.length">
            <ul class="space-y-2">
              <li v-for="t in threads.items" :key="t.id" class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
                <router-link :to="`/threads/${t.id}`" class="block p-3">
                  <div class="flex items-center justify-between">
                    <div class="font-medium">{{ t.title }}</div>
                    <span class="text-xs text-gray-400">#{{ t.id }}</span>
                  </div>
                  <div class="mt-1 text-xs text-gray-500">更新于：{{ formatRelativeTime(t.updatedAt || t.createdAt) }}</div>
                </router-link>
              </li>
            </ul>
          </template>
          <div v-else class="text-gray-600 dark:text-gray-300">暂无帖子</div>
          <div class="mt-3 flex items-center gap-2 text-xs text-gray-600 dark:text-gray-300">
            <button class="rounded px-2 py-1 border dark:border-gray-700 dark:text-gray-200" :disabled="page<=1" @click="prevPage">上一页</button>
            <div>第 {{ page }} 页 / 共 {{ totalPages }} 页</div>
            <button class="rounded px-2 py-1 border dark:border-gray-700 dark:text-gray-200" :disabled="page>=totalPages" @click="nextPage">下一页</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getUserProfile, listUserThreads } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { useAuth } from '@/composables/useAuth'
import { formatRelativeTime } from '@/composables/time'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import markdownItKatex from 'markdown-it-katex'
import 'katex/dist/katex.min.css'

const route = useRoute()
const router = useRouter()
const userId = computed(() => route.params.id)
const loading = ref(true)
const error = ref('')
const profile = ref({})
const { user } = useAuth()
const isMe = computed(() => String(user?.value?.id || '') === String(userId.value || ''))

// 与帖子/评论一致的 Markdown 渲染配置：支持 HTML、代码高亮、数学公式、图片懒加载/响应式
const md = new MarkdownIt({
  html: true,
  linkify: true,
  breaks: true,
  langPrefix: 'language-',
  highlight: (str, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        const out = hljs.highlight(str, { language: lang, ignoreIllegals: true }).value
        return '<pre><code class="hljs language-' + lang + '">' + out + '</code></pre>'
      } catch (_) {}
    } else {
      try {
        const auto = hljs.highlightAuto(str)
        const langGuess = auto.language ? (' language-' + auto.language) : ''
        return '<pre><code class="hljs' + langGuess + '">' + auto.value + '</code></pre>'
      } catch (_) {}
    }
    return '<pre><code class="hljs">' + md.utils.escapeHtml(str) + '</code></pre>'
  }
})
md.use(markdownItKatex)
// 启用 GFM 删除线支持：~~text~~
try { md.enable(['strikethrough']) } catch (_) {}
const defaultImageRule = md.renderer.rules.image || function(tokens, idx, options, env, self) { return self.renderToken(tokens, idx, options) }
md.renderer.rules.image = function(tokens, idx, options, env, self) {
  const token = tokens[idx]
  const loadingIdx = token.attrIndex('loading')
  if (loadingIdx < 0) token.attrPush(['loading', 'lazy'])
  const clsIdx = token.attrIndex('class')
  if (clsIdx < 0) token.attrPush(['class', 'max-w-full h-auto'])
  else token.attrs[clsIdx][1] += ' max-w-full h-auto'
  const srcIdx = token.attrIndex('src')
  if (srcIdx >= 0) token.attrs[srcIdx][1] = normalizeImageUrl(token.attrs[srcIdx][1])
  return defaultImageRule(tokens, idx, options, env, self)
}

// 安全返回：若直接通过地址栏进入或无站内来源，则跳转到发现页
function safeBack() {
  const ref = document.referrer || ''
  const sameOrigin = ref && ref.startsWith(location.origin)
  if (!sameOrigin || window.history.length <= 1) {
    router.replace({ name: 'discover' })
  } else {
    router.back()
  }
}

function renderBio(raw) {
  const s = String(raw || '')
  const html = md.render(s)
  return DOMPurify.sanitize(html)
}

// 主题帖分页状态
const threadsLoading = ref(false)
const threadsError = ref('')
const threads = ref({ items: [], page: 1, size: 10, total: 0 })
const page = ref(1)
const size = ref(10)
const totalPages = computed(() => {
  const s = Math.max(1, Number(size.value || 10))
  const total = Number(threads.value.total || 0)
  return Math.max(1, Math.ceil(total / s))
})

function prevPage() { if (page.value > 1) { page.value -= 1; loadThreads() } }
function nextPage() { if (page.value < totalPages.value) { page.value += 1; loadThreads() } }

async function loadThreads() {
  threadsLoading.value = true
  threadsError.value = ''
  try {
    const data = await listUserThreads(userId.value, { page: page.value, size: size.value })
    threads.value = data || { items: [], page: page.value, size: size.value, total: 0 }
  } catch (e) {
    threadsError.value = e?.message || '加载帖子失败'
  } finally {
    threadsLoading.value = false
  }
}

onMounted(async () => {
  try {
    const data = await getUserProfile(userId.value)
    profile.value = data || {}
  } catch (e) {
    error.value = (e && e.message) || '获取用户资料失败'
  } finally {
    loading.value = false
  }
  // 加载主题帖列表（默认每页10条）
  await loadThreads()
})

// 当路由 id 变化时，刷新个人资料并重置分页后重新加载帖子
watch(userId, async (newId, oldId) => {
  if (String(newId) !== String(oldId)) {
    // 刷新个人资料
    loading.value = true
    error.value = ''
    try {
      const data = await getUserProfile(userId.value)
      profile.value = data || {}
    } catch (e) {
      error.value = (e && e.message) || '获取用户资料失败'
    } finally {
      loading.value = false
    }
    // 重置分页并加载对应用户的帖子
    page.value = 1
    await loadThreads()
  }
})
</script>

<style scoped>
</style>
<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getThread } from '@/api/threads'
import { formatRelativeTime } from '@/composables/time'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import markdownItKatex from 'markdown-it-katex'
import 'katex/dist/katex.min.css'
import Comments from '@/components/Comments.vue'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const t = ref(null)
// 将相对图片路径转换为后端完整URL
function normalizeImageUrl(u) {
  if (!u) return ''
  const url = String(u).trim()
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
    return url
  }
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
  const backendBase = apiBase.replace(/\/api\/v1$/, '')
  if (url.startsWith('/')) return backendBase + url
  return backendBase + '/' + url
}
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

// 数学公式支持
md.use(markdownItKatex)

// 图片懒加载与响应式宽度
const defaultImageRule = md.renderer.rules.image || function(tokens, idx, options, env, self) {
  return self.renderToken(tokens, idx, options)
}
md.renderer.rules.image = function(tokens, idx, options, env, self) {
  const token = tokens[idx]
  const loadingIdx = token.attrIndex('loading')
  if (loadingIdx < 0) token.attrPush(['loading', 'lazy'])
  const clsIdx = token.attrIndex('class')
  if (clsIdx < 0) token.attrPush(['class', 'max-w-full h-auto'])
  else token.attrs[clsIdx][1] += ' max-w-full h-auto'
  // 重写 src 为完整后端URL（解决 /uploads/... 在前端相对路径的问题）
  const srcIdx = token.attrIndex('src')
  if (srcIdx >= 0) {
    const srcVal = token.attrs[srcIdx][1]
    token.attrs[srcIdx][1] = normalizeImageUrl(srcVal)
  }
  return defaultImageRule(tokens, idx, options, env, self)
}
const contentHtml = computed(() => {
  // 优先使用服务端渲染的 HTML（若提供），否则在前端渲染
  const serverHtml = t.value?.contentHtml
  if (serverHtml && typeof serverHtml === 'string') {
    return DOMPurify.sanitize(serverHtml)
  }
  const raw = t.value?.content || ''
  const rendered = md.render(raw)
  return DOMPurify.sanitize(rendered)
})

// 针对服务端返回的 HTML 或未走 markdown-it highlight 的情况，渲染后执行 hljs
const contentRef = ref(null)
function applyRuntimeHighlight() {
  const el = contentRef.value
  if (!el) return
  const nodes = el.querySelectorAll('pre code')
  nodes.forEach((node) => {
    try {
      hljs.highlightElement(node)
    } catch (_) {}
    const pre = node.closest('pre')
    if (pre && !pre.querySelector('.code-copy-btn')) {
      const btn = document.createElement('button')
      btn.type = 'button'
      btn.className = 'code-copy-btn'
      btn.setAttribute('aria-label', '复制代码')
      btn.textContent = '复制'
      btn.addEventListener('click', async (e) => {
        e.preventDefault()
        try {
          const text = node.innerText || node.textContent || ''
          await navigator.clipboard.writeText(text)
          btn.textContent = '已复制'
          setTimeout(() => { btn.textContent = '复制' }, 1500)
        } catch (err) {
          btn.textContent = '复制失败'
          setTimeout(() => { btn.textContent = '复制' }, 1500)
        }
      })
      // 将按钮插入 pre 内部
      pre.style.position = 'relative'
      pre.appendChild(btn)
    }
  })
  // 运行时重写服务端HTML中的图片src
  const imgs = el.querySelectorAll('img')
  imgs.forEach((img) => {
    try {
      const src = img.getAttribute('src') || ''
      const fixed = normalizeImageUrl(src)
      if (fixed && fixed !== src) img.setAttribute('src', fixed)
      // 保证响应式类名
      img.classList.add('max-w-full', 'h-auto')
      img.setAttribute('loading', img.getAttribute('loading') || 'lazy')
    } catch (_) {}
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const id = route.params.id
    const data = await getThread(id)
    t.value = data
  } catch (e) {
    error.value = '加载帖子详情失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await load()
  await nextTick()
  applyRuntimeHighlight()
})

// 内容变化时重新应用高亮
watch(contentHtml, async () => {
  await nextTick()
  applyRuntimeHighlight()
})
</script>

<template>
  <div>
    <div v-if="loading" class="text-gray-600">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-else-if="!t" class="text-gray-600">未找到帖子</div>
      <div v-else class="rounded-md border border-gray-200 bg-white p-4 dark:bg-gray-800 dark:border-gray-700">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <!-- 返回上一页：图标按钮，内联到标题左侧 -->
            <button @click="router.back()" class="inline-flex items-center p-1 rounded text-blue-600 hover:bg-blue-50 dark:hover:bg-gray-700" aria-label="返回上一页" title="返回上一页">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5">
                <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
              </svg>
            </button>
            <h1 class="text-xl font-semibold">{{ t.title }}</h1>
          </div>
          <span class="text-xs text-gray-500 dark:text-gray-400">#{{ t.id }}</span>
        </div>
        <div class="mt-2 text-xs text-gray-500 dark:text-gray-400">
          <span>发布者: {{ t.authorUsername || t.authorId }}</span>
          <span class="mx-2">·</span>
          <span>分区: {{ t.sectionName || t.sectionId }}</span>
          <span class="mx-2">·</span>
          <span>发布于: {{ formatRelativeTime(t.createdAt) }}</span>
        </div>
        <div class="mt-4 prose max-w-none dark:prose-invert" v-html="contentHtml" ref="contentRef"></div>
      </div>
    </div>
    <Comments v-if="t?.id" :thread-id="Number(t.id)" />
  </div>
</template>

<style scoped>
/* 图片在容器内自适应宽度（v-html 内容需使用 :deep 选择器） */
.prose :deep(img) { max-width: 100%; height: auto; }
/* 代码块主题通过全局引入并支持暗色切换 */
/* 代码块可横向滚动、留出内边距与圆角背景，提升可读性 */
.prose :deep(pre) { overflow-x: auto; padding: 0.75rem; border-radius: 0.375rem; }
.prose :deep(code) { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; }
.prose :deep(code.hljs) { display: block; }
/* 亮色/暗色下的代码块背景与文字颜色 */
.prose :deep(pre) { background: #f3f4f6; color: #111827; border: 1px solid #e5e7eb; }
.prose :deep(code) { color: #111827; }
.dark .prose :deep(pre) { background: #1f2937; color: #e5e7eb; border-color: #374151; }
.dark .prose :deep(code) { color: #e5e7eb; }
/* 行内代码微背景，随主题适配 */
.prose :deep(p code:not(.hljs)) { background-color: rgba(31,41,55,0.06); padding: 0.125rem 0.25rem; border-radius: 0.25rem; }
.dark .prose :deep(p code:not(.hljs)) { background-color: rgba(255,255,255,0.10); }
/* 代码复制按钮样式 */
.prose :deep(pre) { position: relative; }
.prose :deep(.code-copy-btn) {
  position: absolute;
  top: 0.5rem;
  right: 0.5rem;
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  border-radius: 0.25rem;
  background: rgba(255, 255, 255, 0.8);
  color: #111827;
  border: 1px solid #e5e7eb;
}
.dark :deep(.code-copy-btn) {
  background: rgba(31, 41, 55, 0.7);
  color: #e5e7eb;
  border-color: #374151;
}
</style>
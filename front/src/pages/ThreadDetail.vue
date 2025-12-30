<script setup>
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getThread } from '@/api/threads'
import { getSummary, triggerSummary } from '@/api/ai'
import { formatRelativeTime } from '@/composables/time'
import { normalizeImageUrl } from '@/utils/image'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import markdownItKatex from 'markdown-it-katex'
import 'katex/dist/katex.min.css'
import Comments from '@/components/Comments.vue'
import { updateVisitTitleByPath, updateVisitTitleById, updateVisitSectionById } from '@/composables/useRecentVisits'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const error = ref('')
const t = ref(null)
const authorNickname = ref('')
const aiSummary = ref('')
const aiStatus = ref('')
const aiLoading = ref(false)

// 从 URL hash 中解析需要滚动定位的评论 ID（格式：#post-<id>）
const anchorPostId = ref(null)
function updateAnchorFromHash() {
  const h = String(route.hash || '')
  const m = h.match(/^#post-(\d+)$/)
  anchorPostId.value = m ? Number(m[1]) : null
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

// 启用 GFM 删除线支持：~~text~~
try { md.enable(['strikethrough']) } catch (_) {}

// 数学公式支持
md.use(markdownItKatex)

// 标题使用行内 Markdown 渲染（更安全），统一支持删除线
const mdTitle = new MarkdownIt({ html: false, linkify: true, breaks: false })
try { mdTitle.enable(['strikethrough']) } catch (_) {}
function renderTitle(text) {
  const safe = String(text || '')
  return DOMPurify.sanitize(mdTitle.renderInline(safe))
}

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
  // 为保持与列表页一致的 Markdown 行为（含删除线），优先使用前端渲染
  const raw = t.value?.content
  if (typeof raw === 'string' && raw.length > 0) {
    const rendered = md.render(raw)
    return DOMPurify.sanitize(rendered)
  }
  // 若无原始内容（仅返回已渲染的 HTML），再使用服务端 HTML
  const serverHtml = t.value?.contentHtml
  if (typeof serverHtml === 'string' && serverHtml.length > 0) {
    return DOMPurify.sanitize(serverHtml)
  }
  return ''
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
async function load() {
  loading.value = true
  error.value = ''
  try {
    const id = route.params.id
    const data = await getThread(id)
    t.value = data
    // 更新最近浏览的分区信息，便于历史页分区筛选
    try {
      const sid = t.value?.sectionId
      const sname = t.value?.sectionName
      updateVisitSectionById(Number(route.params.id), sid, sname)
    } catch (_) {}
    // 设定页面标题，并更新“最近浏览”记录的标题
    try {
      const titleText = String(t.value?.title || '').trim()
      if (titleText) {
        document.title = titleText
        const path = route.fullPath || route.path
        updateVisitTitleByPath(path, titleText)
        // 同步按帖子 ID 更新，保证在 path 不一致（含 hash/query）时也能更新到正确记录
        try { updateVisitTitleById(Number(route.params.id), titleText) } catch (_) {}
      }
    } catch (_) {}
    // 补充作者昵称
    const uid = t.value?.authorId
    if (uid) {
      try {
        const p = await getUserProfile(uid)
        authorNickname.value = p?.nickname || ''
      } catch (_) {
        authorNickname.value = ''
      }
    } else {
      authorNickname.value = ''
    }
  } catch (e) {
    error.value = '加载帖子详情失败'
  } finally {
    loading.value = false
  }
}

async function loadSummary(id) {
  try {
    const res = await getSummary(id)
    if (res.data) {
      aiSummary.value = res.data.summary
      aiStatus.value = res.data.status
    }
  } catch (e) {
    // ignore
  }
}

async function handleGenerateSummary() {
  if (!t.value?.id) return
  aiLoading.value = true
  try {
    await triggerSummary(t.value.id, true)
    aiStatus.value = 'PENDING'
    // 轮询几次检查结果
    let checks = 0
    const interval = setInterval(async () => {
      checks++
      await loadSummary(t.value.id)
      if (aiStatus.value === 'COMPLETED' || checks > 10) {
        clearInterval(interval)
        aiLoading.value = false
      }
    }, 2000)
  } catch (e) {
    aiLoading.value = false
  }
}

onMounted(async () => {
  await load()
  await nextTick()
  applyRuntimeHighlight()
  updateAnchorFromHash()
})

// 内容变化时重新应用高亮
watch(contentHtml, async () => {
  await nextTick()
  applyRuntimeHighlight()
})
// 监听 hash 变化，允许在同页面内切换定位到不同评论
watch(() => route.hash, () => updateAnchorFromHash())
</script>

<template>
  <div>
    <div v-if="loading" class="text-gray-600">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-else-if="!t" class="text-gray-600">未找到帖子</div>
      <div v-else class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
        <!-- 头部信息 -->
        <div class="border-b border-gray-100 bg-gray-50/50 p-5 dark:border-gray-700 dark:bg-gray-800/50">
          <div class="flex items-start gap-4">
            <button @click="safeBack()" class="mt-1 shrink-0 rounded-full p-1.5 text-gray-400 hover:bg-gray-200 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-200 transition-colors" aria-label="返回">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
                <path fill-rule="evenodd" d="M11.03 3.97a.75.75 0 010 1.06l-6.22 6.22H21a.75.75 0 010 1.5H4.81l6.22 6.22a.75.75 0 11-1.06 1.06l-7.5-7.5a.75.75 0 010-1.06l7.5-7.5a.75.75 0 011.06 0z" clip-rule="evenodd" />
              </svg>
            </button>
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-3 text-xs text-gray-500 mb-2">
                <router-link :to="{ name: 'discover', query: { sectionId: t.sectionId, page: 1 } }" class="rounded bg-brandDay-50 px-2 py-0.5 font-medium text-brandDay-600 hover:bg-brandDay-100 dark:bg-brandNight-900/30 dark:text-brandNight-300 dark:hover:bg-brandNight-900/50 transition-colors">
                  {{ t.sectionName || t.sectionId }}
                </router-link>
                <span>·</span>
                <span>{{ formatRelativeTime(t.createdAt) }}</span>
                <span v-if="t.updatedAt && t.updatedAt !== t.createdAt" class="text-gray-400">
                  (编辑于 {{ formatRelativeTime(t.updatedAt) }})
                </span>
              </div>
              <h1 class="text-2xl font-bold text-gray-900 dark:text-gray-100 break-words leading-tight" v-html="renderTitle(t.title)"></h1>
              
              <div class="mt-4 flex items-center gap-3">
                <router-link :to="t.authorId ? ('/users/' + t.authorId) : '/users'" class="flex items-center gap-2 group">
                  <img 
                    :src="t.authorAvatar ? normalizeImageUrl(t.authorAvatar) : `https://api.dicebear.com/7.x/initials/svg?seed=${t.authorNickname || t.authorUsername || 'U'}`" 
                    class="h-8 w-8 rounded-full object-cover ring-2 ring-transparent group-hover:ring-brandDay-100 dark:group-hover:ring-brandNight-900 transition-all bg-gray-100 dark:bg-gray-700"
                    alt=""
                  />
                  <span class="text-sm font-medium text-gray-700 group-hover:text-brandDay-600 dark:text-gray-300 dark:group-hover:text-brandNight-400 transition-colors">
                    {{ t.authorNickname || t.authorUsername || t.authorId }}
                  </span>
                </router-link>
              </div>
            </div>
          </div>
        </div>

        <!-- AI 摘要区域 -->
        <div v-if="aiSummary || aiLoading" class="mx-5 mt-5 rounded-lg border border-indigo-100 bg-indigo-50/50 p-4 dark:border-indigo-900/50 dark:bg-indigo-900/10">
          <div class="flex items-center justify-between mb-2">
            <div class="flex items-center gap-2">
              <span class="flex h-6 w-6 items-center justify-center rounded-full bg-indigo-100 text-indigo-600 dark:bg-indigo-900 dark:text-indigo-300">
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-3.5 w-3.5">
                  <path fill-rule="evenodd" d="M9.315 7.584C12.195 3.883 16.695 1.5 21.75 1.5a.75.75 0 0 1 .75.75c0 5.056-2.383 9.555-6.084 12.436A6.753 6.753 0 0 1 9.75 22.5a.75.75 0 0 1-.75-.75v-4.131A15.838 15.838 0 0 1 6.382 15H2.25a.75.75 0 0 1-.75-.75 6.75 6.75 0 0 1 7.815-6.666ZM15 6.75a2.25 2.25 0 1 0 0 4.5 2.25 2.25 0 0 0 0-4.5Z" clip-rule="evenodd" />
                </svg>
              </span>
              <span class="text-sm font-bold text-indigo-900 dark:text-indigo-100">AI 智能摘要</span>
            </div>
            <button 
              @click="handleGenerateSummary" 
              :disabled="aiLoading || aiStatus === 'PENDING'"
              class="text-xs font-medium text-indigo-600 hover:text-indigo-700 dark:text-indigo-400 dark:hover:text-indigo-300 disabled:opacity-50"
            >
              {{ aiLoading || aiStatus === 'PENDING' ? '生成中...' : '重新生成' }}
            </button>
          </div>
          <div class="pl-8 text-sm leading-relaxed text-indigo-900/80 dark:text-indigo-100/80">
            {{ aiSummary || '正在分析帖子内容，请稍候...' }}
          </div>
        </div>
        <div v-else class="mx-5 mt-2 flex justify-end">
           <button @click="handleGenerateSummary" class="flex items-center gap-1 text-xs text-gray-400 hover:text-indigo-500 transition-colors">
             <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-3 w-3">
               <path fill-rule="evenodd" d="M9.315 7.584C12.195 3.883 16.695 1.5 21.75 1.5a.75.75 0 0 1 .75.75c0 5.056-2.383 9.555-6.084 12.436A6.753 6.753 0 0 1 9.75 22.5a.75.75 0 0 1-.75-.75v-4.131A15.838 15.838 0 0 1 6.382 15H2.25a.75.75 0 0 1-.75-.75 6.75 6.75 0 0 1 7.815-6.666ZM15 6.75a2.25 2.25 0 1 0 0 4.5 2.25 2.25 0 0 0 0-4.5Z" clip-rule="evenodd" />
             </svg>
             生成 AI 摘要
           </button>
        </div>

        <!-- 正文内容 -->
        <div class="p-5 sm:p-8">
          <div class="prose prose-lg max-w-none dark:prose-invert prose-headings:font-bold prose-a:text-brandDay-600 dark:prose-a:text-brandNight-400 prose-img:rounded-xl prose-img:shadow-sm" v-html="contentHtml" ref="contentRef"></div>
        </div>
      </div>
    </div>
    <Comments v-if="t?.id" :thread-id="Number(t.id)" :scroll-to-post-id="anchorPostId" />
  </div>
</template>

<style scoped>
/* 图片尺寸由全局 .prose img 统一控制 */
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
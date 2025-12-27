<script setup>
import { ref, onMounted, watch, computed, onBeforeUnmount, nextTick } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/common'
import markdownItKatex from 'markdown-it-katex'
import { listPosts, createPost, deletePost, updatePost } from '@/api/posts'
import { uploadImage } from '@/api/uploads'
import { formatRelativeTime } from '@/composables/time'
import { useAuth } from '@/composables/useAuth'

const props = defineProps({
  threadId: { type: Number, required: true },
  // 自动折叠阈值：子回复数量超过该值则折叠
  autoCollapseCountThreshold: { type: Number, default: 5 },
  // 自动折叠宽度阈值：容器宽度超过该像素值则折叠
  autoCollapseWidthThreshold: { type: Number, default: 720 },
  // 自动折叠高度阈值：容器高度超过该像素值则折叠
  autoCollapseHeightThreshold: { type: Number, default: 480 },
  // 楼中楼子回复分页大小
  childPageSize: { type: Number, default: 10 },
  // 可选：用于外部传入需要滚动定位的评论ID（例如通过 URL hash）
  scrollToPostId: { type: Number, default: null },
})

const loading = ref(false)
const error = ref('')
const items = ref([])
// 当后端未返回分页结构（仅返回数组）时，使用本地分页：allItems 作为全量数据源
const allItems = ref(null)
// 将评论分组为：每个顶层评论一个容器，子回复在同一容器内按层级缩进，并生成楼层号
const groups = computed(() => {
  const list = Array.isArray(allItems.value) ? allItems.value.slice() : (Array.isArray(items.value) ? items.value.slice() : [])
  const nodes = list.map(p => ({ ...p, children: [] }))
  const byId = new Map(nodes.map(n => [n.id, n]))
  nodes.forEach(n => {
    const pid = n.replyToPostId
    if (pid && byId.has(pid)) {
      const parent = byId.get(pid)
      parent.children.push(n)
      n.parentAuthorUsername = parent.authorUsername || parent.authorId
    }
  })
  const sortByCreated = (a, b) => new Date(a.createdAt) - new Date(b.createdAt)
  const roots = nodes.filter(n => !n.replyToPostId || !byId.has(n.replyToPostId)).sort(sortByCreated)
  const result = []
  const walk = (n, depth, acc, path) => {
    acc.push({ ...n, depth, floorLabel: path.join('-') })
    n.children.sort(sortByCreated).forEach((c, idx) => walk(c, depth + 1, acc, [...path, idx + 1]))
  }
  roots.forEach((r, rIdx) => {
    const acc = []
    // 仅把子孙放进 acc，root 自己作为容器顶部单独渲染（楼层号在后续全局排序中统一覆盖）
    r.floorLabel = `${rIdx + 1}楼`
    r.children.sort(sortByCreated).forEach((c, idx) => walk(c, 1, acc, [rIdx + 1, idx + 1]))
    result.push({ root: r, items: acc })
  })
  return result
})

// 排序：按时间或按回复数；方向升序/降序分开
const sortKey = ref('time') // 'time' | 'replies'
const sortOrder = ref('desc') // 'asc' | 'desc'
const sortedGroups = computed(() => {
  const arr = (groups.value || []).slice()
  arr.sort((a, b) => {
    const va = (sortKey.value === 'replies')
      ? (a.items?.length || 0)
      : new Date(a?.root?.createdAt || 0).getTime()
    const vb = (sortKey.value === 'replies')
      ? (b.items?.length || 0)
      : new Date(b?.root?.createdAt || 0).getTime()
    if (va === vb) {
      // 二级排序：按时间降序/升序作为可见的次序变化
      const ta = new Date(a?.root?.createdAt || 0).getTime()
      const tb = new Date(b?.root?.createdAt || 0).getTime()
      const timeCmp = tb - ta
      if (timeCmp !== 0) return sortOrder.value === 'asc' ? -timeCmp : timeCmp
      // 三级排序：按 id 保证稳定
      const ia = Number(a?.root?.id || 0)
      const ib = Number(b?.root?.id || 0)
      return sortOrder.value === 'asc' ? (ia - ib) : (ib - ia)
    }
    return sortOrder.value === 'asc' ? (va - vb) : (vb - va)
  })
  return arr
})

// 连续楼层号与分页：基于排序后的全量分组计算
const pagedGroups = computed(() => {
  const arr = (sortedGroups.value || []).slice()
  const start = (Math.max(1, Number(page.value || 1)) - 1) * Math.max(1, Number(size.value || 15))
  const end = start + Math.max(1, Number(size.value || 15))
  return arr.slice(start, end)
})

// 顶层楼层总数与页面总数（在全量模式下基于 sortedGroups，后端分页模式下基于 total）
const totalGroups = computed(() => (sortedGroups.value || []).length)
const pageCount = computed(() => {
  const sz = Math.max(1, Number(size.value || 15))
  if (Array.isArray(allItems.value)) {
    return Math.max(1, Math.ceil(totalGroups.value / sz))
  }
  return Math.max(1, Math.ceil(Number(total.value || 0) / sz))
})
const hasPagination = computed(() => {
  const sz = Math.max(1, Number(size.value || 15))
  if (Array.isArray(allItems.value)) return totalGroups.value > sz
  return Number(total.value || 0) > sz
})

// 独立下拉菜单状态与选择函数
const showTimeMenu = ref(false)
const showRepliesMenu = ref(false)
function selectTimeOrder(order) {
  sortKey.value = 'time'
  sortOrder.value = order === 'asc' ? 'asc' : 'desc'
  showTimeMenu.value = false
}
function selectRepliesOrder(order) {
  sortKey.value = 'replies'
  sortOrder.value = order === 'asc' ? 'asc' : 'desc'
  showRepliesMenu.value = false
}

// 顶层容器的折叠/展开状态管理
const collapsedMap = ref({})
function toggleCollapse(rootId) {
  const cur = !!collapsedMap.value[rootId]
  collapsedMap.value[rootId] = !cur
}
// 每个顶层楼层的子回复分页页码
const groupPageMap = ref({})

// 持久化与恢复（按线程维度）：子回复页码与折叠状态
function storageKeyPages() { return 'comments_group_pages_' + String(props.threadId || '') }
function storageKeyCollapsed() { return 'comments_collapsed_map_' + String(props.threadId || '') }
function restoreStateFromStorage() {
  try {
    const rawPages = localStorage.getItem(storageKeyPages())
    if (rawPages) {
      const obj = JSON.parse(rawPages)
      if (obj && typeof obj === 'object') groupPageMap.value = obj
    }
  } catch (_) {}
  try {
    const rawCollapsed = localStorage.getItem(storageKeyCollapsed())
    if (rawCollapsed) {
      const obj = JSON.parse(rawCollapsed)
      if (obj && typeof obj === 'object') collapsedMap.value = obj
    }
  } catch (_) {}
}
function persistStateToStorage() {
  try { localStorage.setItem(storageKeyPages(), JSON.stringify(groupPageMap.value || {})) } catch (_) {}
  try { localStorage.setItem(storageKeyCollapsed(), JSON.stringify(collapsedMap.value || {})) } catch (_) {}
}
// 初次挂载时恢复状态，之后每次变更时持久化
onMounted(() => { restoreStateFromStorage() })
watch(groupPageMap, () => { persistStateToStorage() }, { deep: true })
watch(collapsedMap, () => { persistStateToStorage() }, { deep: true })

// 记录每个楼层容器的元素引用，用于测量宽度
const groupEls = ref({})
let resizeObserver = null
function setGroupEl(rootId, el) {
  if (el) groupEls.value[rootId] = el
}

function updateAutoCollapse() {
  const countTh = Number(props.autoCollapseCountThreshold || 5)
  const widthTh = Number(props.autoCollapseWidthThreshold || 720)
  const heightTh = Number(props.autoCollapseHeightThreshold || 480)
  const currentGroups = groups.value || []
  currentGroups.forEach(g => {
    const el = groupEls.value[g.root.id]
    const width = el?.clientWidth || 0
    const height = el?.scrollHeight || el?.clientHeight || 0
    const shouldCollapse = (g.items.length >= countTh) || (width > widthTh) || (height > heightTh)
    // 仅在首次或尚未定义时设置自动折叠，避免覆盖用户手动切换
    if (typeof collapsedMap.value[g.root.id] === 'undefined') {
      collapsedMap.value[g.root.id] = shouldCollapse
    }
    // 初始化子回复分页页码
    if (typeof groupPageMap.value[g.root.id] === 'undefined') {
      groupPageMap.value[g.root.id] = 1
    }
  })
}
const page = ref(1)
const size = ref(15)
const total = ref(0)
const content = ref('')
const replyToPostId = ref(null)
const isLoggedIn = computed(() => !!localStorage.getItem('accessToken'))
const previewMode = ref(false)
const { user } = useAuth()

// 资料更新事件：当我更换头像后，更新当前页面中我发表的评论头像
function onProfileUpdated(evt) {
  try {
    const next = evt?.detail?.avatarUrl || ''
    const myId = Number(user?.value?.id || 0)
    if (!myId || !next) return
    const apply = (arr) => (arr || []).map(it => (Number(it?.authorId || 0) === myId ? { ...it, authorAvatarUrl: next } : it))
    if (Array.isArray(allItems.value)) {
      allItems.value = apply(allItems.value)
      const start = (page.value - 1) * size.value
      const end = start + size.value
      items.value = allItems.value.slice(start, end)
    } else {
      items.value = apply(items.value)
    }
  } catch (_) {}
}

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

const md = new MarkdownIt({ html: true, linkify: true, breaks: true, langPrefix: 'language-',
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
const defaultImageRule = md.renderer.rules.image || function(tokens, idx, options, env, self) { return self.renderToken(tokens, idx, options) }
md.renderer.rules.image = function(tokens, idx, options, env, self) {
  const token = tokens[idx]
  const loadingIdx = token.attrIndex('loading')
  if (loadingIdx < 0) token.attrPush(['loading', 'lazy'])
  const clsIdx = token.attrIndex('class')
  if (clsIdx < 0) token.attrPush(['class', 'max-w-full h-auto'])
  else token.attrs[clsIdx][1] += ' max-w-full h-auto'
  const srcIdx = token.attrIndex('src')
  if (srcIdx >= 0) {
    token.attrs[srcIdx][1] = normalizeImageUrl(token.attrs[srcIdx][1])
  }
  return defaultImageRule(tokens, idx, options, env, self)
}

function render(mdText) {
  const html = md.render(mdText || '')
  return DOMPurify.sanitize(html)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listPosts(props.threadId, { page: page.value, size: size.value })
    if (Array.isArray(data)) {
      // 后端未分页：使用本地分页
      allItems.value = data
      total.value = data.length
      const max = Math.max(1, Math.ceil(total.value / size.value))
      page.value = Math.min(Math.max(1, page.value || 1), max)
      const start = (page.value - 1) * size.value
      const end = start + size.value
      items.value = allItems.value.slice(start, end)
    } else {
      // 后端已分页：直接使用后端数据
      allItems.value = null
      items.value = data.items || []
      total.value = Number(data.total || 0)
      page.value = Number(data.page || page.value)
      size.value = Number(data.size || size.value)
    }
  } catch (e) {
    error.value = '加载评论失败'
  } finally {
    loading.value = false
  }
}

// 聚合全量评论用于全局排序与连续楼层编号
async function loadAllForGlobalSort() {
  loading.value = true
  error.value = ''
  try {
    const pageSize = 50
    const first = await listPosts(props.threadId, { page: 1, size: pageSize })
    const totalCount = Array.isArray(first) ? first.length : Number(first.total || 0)
    let acc = Array.isArray(first) ? first.slice() : ((first.items || []).slice())
    const totalPages = Math.max(1, Math.ceil(totalCount / pageSize))
    for (let p = 2; p <= totalPages; p++) {
      const res = await listPosts(props.threadId, { page: p, size: pageSize })
      const arr = Array.isArray(res) ? res : (res.items || [])
      acc = acc.concat(arr)
    }
    allItems.value = acc
    total.value = acc.length
    // 重置到第 1 页，避免页码越界
    page.value = 1
    // 同步当前页 items（供依赖 items 的逻辑使用，不影响渲染）
    const start = (page.value - 1) * size.value
    const end = start + size.value
    items.value = allItems.value.slice(start, end)
    // 加载完成后尝试滚动到指定评论
    tryScrollToId(props.scrollToPostId)
  } catch (e) {
    error.value = '加载全部评论失败'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!isLoggedIn.value) {
    error.value = '请先登录再评论'
    return
  }
  const text = String(content.value || '').trim()
  if (!text) return
  if (text.length > 3000) { error.value = '内容过长'; return }
  const optimistic = {
    id: 'temp_' + Math.random().toString(36).slice(2),
    threadId: props.threadId,
    authorId: 0,
    authorUsername: '我',
    content: text,
    replyToPostId: replyToPostId.value || null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    _optimistic: true,
  }
  // 乐观更新：在当前页尾部追加
  items.value = [...items.value, optimistic]
  if (Array.isArray(allItems.value)) {
    allItems.value = [...allItems.value, optimistic]
    total.value = allItems.value.length
  } else {
    total.value = Number(total.value || 0) + 1
  }
  try {
    const created = await createPost(props.threadId, { contentMd: text, replyToPostId: replyToPostId.value || null })
    if (Array.isArray(allItems.value)) {
      allItems.value = allItems.value.map(it => (it.id === optimistic.id ? created : it))
      const start = (page.value - 1) * size.value
      const end = start + size.value
      items.value = allItems.value.slice(start, end)
    } else {
      items.value = items.value.map(it => (it.id === optimistic.id ? created : it))
    }
    content.value = ''
    replyToPostId.value = null
  } catch (e) {
    error.value = e?.response?.data?.message || '发布失败'
    if (Array.isArray(allItems.value)) {
      allItems.value = allItems.value.filter(it => it.id !== optimistic.id)
      total.value = allItems.value.length
      const start = (page.value - 1) * size.value
      const end = start + size.value
      items.value = allItems.value.slice(start, end)
    } else {
      items.value = items.value.filter(it => it.id !== optimistic.id)
      total.value = Math.max(0, Number(total.value || 0) - 1)
    }
  }
}

async function handleUploadImage(file, onProgress) {
  const token = localStorage.getItem('accessToken') || ''
  const resp = await uploadImage(file, token, onProgress)
  const url = resp?.url || resp?.path || resp
  const normalized = normalizeImageUrl(url)
  const insert = `\n\n![](${normalized})\n\n`
  content.value = (content.value || '') + insert
}

function setReplyTo(id, rootId) {
  replyToPostId.value = id
  // 如果传入了 rootId（子回复），确保所在页可见，并展开楼层
  if (rootId) {
    const g = (groups.value || []).find(x => x.root?.id === rootId)
    if (g) {
      const size = Number(props.childPageSize || 10)
      const idx = g.items.findIndex(x => x.id === id)
      if (idx >= 0) {
        const pageOfChild = Math.floor(idx / size) + 1
        groupPageMap.value[rootId] = pageOfChild
      }
      collapsedMap.value[rootId] = false
    }
  }
  nextTick(() => {
    try {
      const el = document.getElementById('post-' + id)
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' })
        const ta = el.querySelector('textarea')
        if (ta && typeof ta.focus === 'function') ta.focus()
      }
    } catch (_) {}
  })
}
function cancelReply() { replyToPostId.value = null }

// 外部滚动定位到指定评论：展开所在楼层、切换到包含该评论的子分页，然后滚动到视图中
function tryScrollToId(id) {
  const targetId = Number(id || 0)
  if (!targetId) return
  const gs = groups.value || []
  let targetGroup = null
  let isRoot = false
  for (const g of gs) {
    if (Number(g?.root?.id || 0) === targetId) { targetGroup = g; isRoot = true; break }
    const idx = (g.items || []).findIndex(c => Number(c?.id || 0) === targetId)
    if (idx >= 0) { targetGroup = g; break }
  }
  if (!targetGroup) return
  const rootId = Number(targetGroup.root?.id || 0)
  if (!rootId) return
  // 展开所在容器
  collapsedMap.value[rootId] = false
  // 若是子回复，切换到包含该子回复的页码
  if (!isRoot) {
    const idx = (targetGroup.items || []).findIndex(c => Number(c?.id || 0) === targetId)
    const sizeChild = Math.max(1, Number(props.childPageSize || 10))
    if (idx >= 0) groupPageMap.value[rootId] = Math.floor(idx / sizeChild) + 1
  }
  nextTick(() => {
    try {
      const el = document.getElementById('post-' + targetId)
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    } catch (_) {}
  })
}

// 当外部传入的定位评论ID变化时，尝试滚动到该评论
watch(() => props.scrollToPostId, (nid, oid) => {
  if (nid && nid !== oid) {
    // 若尚未加载完 groups，等待下一次 tick 后尝试；否则直接尝试
    nextTick(() => tryScrollToId(nid))
  }
})

onMounted(loadAllForGlobalSort)
onMounted(() => { window.addEventListener('profile-updated', onProfileUpdated) })
watch(() => props.threadId, () => { page.value = 1; loadAllForGlobalSort() })

// 在挂载后与分组变化时，基于数量与宽度自动折叠
onMounted(() => {
  updateAutoCollapse()
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      updateAutoCollapse()
    })
  }
  nextTick(() => {
    if (resizeObserver) {
      Object.values(groupEls.value).forEach(el => {
        if (el) resizeObserver.observe(el)
      })
    }
  })
})

watch(groups, () => {
  nextTick(() => {
    updateAutoCollapse()
    if (resizeObserver) {
      resizeObserver.disconnect()
      Object.values(groupEls.value).forEach(el => {
        if (el) resizeObserver.observe(el)
      })
    }
  })
})

onBeforeUnmount(() => {
  if (resizeObserver) resizeObserver.disconnect()
  try { window.removeEventListener('profile-updated', onProfileUpdated) } catch (_) {}
})

// 当后端未提供分页结构时，前端基于 allItems 本地分页
watch([page, size], () => {
  nextTick(() => {
    if (Array.isArray(allItems.value)) {
      const max = Math.max(1, Math.ceil(totalGroups.value / size.value))
      page.value = Math.min(Math.max(1, page.value || 1), max)
      const start = (page.value - 1) * size.value
      const end = start + size.value
      items.value = allItems.value.slice(start, end)
    } else {
      // 后端已分页：切页时重新拉取
      load()
    }
  })
})

// 全局分页按钮：根据是否已聚合全量数据决定是否调用后端
function goPrevPage() {
  const max = Array.isArray(allItems.value)
    ? Math.max(1, Math.ceil(totalGroups.value / Math.max(1, Number(size.value || 15))))
    : Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15))))
  page.value = Math.min(Math.max(1, Number(page.value || 1) - 1), max)
  if (!Array.isArray(allItems.value)) {
    load()
  }
}

function goNextPage() {
  const max = Array.isArray(allItems.value)
    ? Math.max(1, Math.ceil(totalGroups.value / Math.max(1, Number(size.value || 15))))
    : Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15))))
  page.value = Math.min(max, Number(page.value || 1) + 1)
  if (!Array.isArray(allItems.value)) {
    load()
  }
}

function applyPageInput() {
  const max = Array.isArray(allItems.value)
    ? Math.max(1, Math.ceil(totalGroups.value / Math.max(1, Number(size.value || 15))))
    : Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15))))
  page.value = Math.min(Math.max(1, Number(page.value || 1)), max)
  if (!Array.isArray(allItems.value)) {
    load()
  }
}

// 排序变化时触发全量聚合（确保对所有评论生效）
watch([sortKey, sortOrder], async () => {
  // 若已聚合过并有全量数据，则不重复请求，仅重置页码即可
  if (Array.isArray(allItems.value) && allItems.value.length > 0) {
    page.value = 1
    const start = 0
    const end = size.value
    items.value = allItems.value.slice(start, end)
    return
  }
  await loadAllForGlobalSort()
})

// 计算指定顶层楼层的当前页子回复列表
function getChildrenPage(g) {
  const size = Number(props.childPageSize || 10)
  const pg = Number(groupPageMap.value[g.root.id] || 1)
  const max = Math.max(1, Math.ceil((g.items?.length || 0) / size))
  const cur = Math.min(Math.max(1, pg), max)
  const start = (cur - 1) * size
  const end = start + size
  return (g.items || []).slice(start, end)
}
</script>

<template>
  <div class="mt-8">
    <!-- 全局评论输入框：移动到“评论”标题上方，仅在未回复楼中楼时显示 -->
    <div class="mb-3" v-if="replyToPostId == null">
      <div v-if="!isLoggedIn" class="text-xs text-gray-500">登录后可发表评论</div>
      <div v-else>
          <textarea v-if="!previewMode" v-model="content" class="w-full h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100" placeholder="支持基础 Markdown（图片请使用上方上传功能）"></textarea>
          <div v-else class="w-full h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 prose max-w-none dark:prose-invert" v-html="render(content)"></div>
        <div class="mt-2 flex items-center gap-2">
          <label class="inline-flex items-center gap-2 text-xs cursor-pointer">
            <input type="file" accept="image/*" class="hidden" @change="(e) => { const f=e.target.files?.[0]; if (f) handleUploadImage(f) }" />
            <span class="rounded px-2 py-1 border dark:border-gray-700">添加图片</span>
          </label>
          <button class="rounded px-2 py-1 border dark:border-gray-700" @click="previewMode = !previewMode">{{ previewMode ? '退出预览' : '预览' }}</button>
          <button class="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700" @click="submit">发送评论</button>
        </div>
      </div>
    </div>
    <div class="text-sm font-medium mb-2 flex items-center justify-between">
      <span>评论</span>
      <div class="text-xs flex items-center gap-2">
        <label>排序：</label>
        <!-- 按时间下拉菜单 -->
        <div class="relative">
          <button class="rounded border px-2 py-1 dark:bg-gray-800 dark:border-gray-700 hover:bg-gray-100 dark:hover:bg-gray-700"
                  :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey==='time' }"
                  @click="showTimeMenu = !showTimeMenu">
            按时间 <span v-if="sortKey==='time'">（{{ sortOrder==='asc' ? '升序' : '降序' }}）</span>
          </button>
          <div v-if="showTimeMenu" class="absolute right-0 z-10 mt-1 w-24 rounded border bg-white text-gray-700 shadow-md dark:bg-gray-800 dark:text-gray-100 dark:border-gray-700">
            <button class="block w-full text-left px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="selectTimeOrder('asc')">升序</button>
            <button class="block w-full text-left px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="selectTimeOrder('desc')">降序</button>
          </div>
        </div>
        <!-- 按回复数下拉菜单 -->
        <div class="relative">
          <button class="rounded border px-2 py-1 dark:bg-gray-800 dark:border-gray-700 hover:bg-gray-100 dark:hover:bg-gray-700"
                  :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey==='replies' }"
                  @click="showRepliesMenu = !showRepliesMenu">
            按回复数 <span v-if="sortKey==='replies'">（{{ sortOrder==='asc' ? '升序' : '降序' }}）</span>
          </button>
          <div v-if="showRepliesMenu" class="absolute right-0 z-10 mt-1 w-24 rounded border bg-white text-gray-700 shadow-md dark:bg-gray-800 dark:text-gray-100 dark:border-gray-700">
            <button class="block w-full text-left px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="selectRepliesOrder('asc')">升序</button>
            <button class="block w-full text-left px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="selectRepliesOrder('desc')">降序</button>
          </div>
        </div>
      </div>
    </div>
    <div v-if="loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <ul class="space-y-3">
<li v-for="g in pagedGroups" :key="g.root.id" class="rounded-md border border-gray-200 bg-white p-3 dark:bg-gray-800 dark:border-gray-700" :ref="el => setGroupEl(g.root.id, el)">
          <!-- 顶层评论（容器顶部） -->
          <div :id="'post-' + g.root.id" class="">
              <div class="flex items-center justify-between">
                <router-link :to="g.root.authorId ? ('/users/' + g.root.authorId) : '/users'" class="flex items-center gap-2 hover:opacity-90">
                  <template v-if="g.root.authorAvatarUrl">
                    <img :src="normalizeImageUrl(g.root.authorAvatarUrl)" alt="头像" class="w-7 h-7 rounded-full object-cover" loading="lazy" />
                  </template>
                  <template v-else>
                    <div class="w-7 h-7 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-xs text-gray-600 dark:text-gray-300">
                      {{ String(g.root.authorUsername || g.root.authorId || '').slice(0,1).toUpperCase() || 'U' }}
                    </div>
                  </template>
                  <div class="text-xs text-gray-600 dark:text-gray-300">{{ g.root.authorUsername || g.root.authorId }}</div>
                </router-link>
                <span class="text-xs text-gray-400">{{ g.root.floorLabel }} · {{ formatRelativeTime(g.root.createdAt) }}</span>
              </div>
            <div class="mt-2 prose max-w-none dark:prose-invert" v-html="render(g.root.content)"></div>
            <div class="mt-2 flex items-center gap-2 text-xs">
              <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="setReplyTo(g.root.id)">回复</button>
            <button v-if="g.items.length" class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="toggleCollapse(g.root.id)">
              {{ collapsedMap[g.root.id] ? '展开回复(' + g.items.length + ')' : '折叠回复(' + g.items.length + ')' }}
            </button>
          </div>
          <!-- 针对顶层评论的内联回复输入框 -->
          <div v-if="replyToPostId === g.root.id" class="mt-2">
            <div v-if="!isLoggedIn" class="text-xs text-gray-500">登录后可发表评论</div>
            <div v-else>
              <textarea v-if="!previewMode" v-model="content" class="w-[720px] h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100" placeholder="支持基础 Markdown（图片请使用上方上传功能）"></textarea>
              <div v-else class="w-[720px] h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 prose max-w-none dark:prose-invert" v-html="render(content)"></div>
              <div class="mt-2 flex items-center gap-2">
                <label class="inline-flex items-center gap-2 text-xs cursor-pointer">
                  <input type="file" accept="image/*" class="hidden" @change="(e) => { const f=e.target.files?.[0]; if (f) handleUploadImage(f) }" />
                  <span class="rounded px-2 py-1 border dark:border-gray-700">添加图片</span>
                </label>
                <button class="rounded px-2 py-1 border dark:border-gray-700" @click="previewMode = !previewMode">{{ previewMode ? '退出预览' : '预览' }}</button>
                <button class="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700" @click="submit">发送评论</button>
                <span class="text-xs text-gray-500">回复：#{{ replyToPostId }} <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="cancelReply">取消</button></span>
              </div>
            </div>
          </div>
          </div>

          <!-- 子回复区域（同容器内按层级缩进） -->
          <div v-if="g.items.length && !collapsedMap[g.root.id]" class="mt-3 space-y-2 border-l-2 border-gray-200 dark:border-gray-700 pl-3 bg-gray-50 dark:bg-gray-800/40 rounded-sm">
            <div v-for="c in getChildrenPage(g)" :key="c.id" :id="'post-' + c.id" class="rounded-sm p-2"
                 :style="{ paddingLeft: '24px' }">
              <div class="flex items-center justify-between">
                <router-link :to="c.authorId ? ('/users/' + c.authorId) : '/users'" class="flex items-center gap-2 hover:opacity-90">
                  <template v-if="c.authorAvatarUrl">
                    <img :src="normalizeImageUrl(c.authorAvatarUrl)" alt="头像" class="w-6 h-6 rounded-full object-cover" loading="lazy" />
                  </template>
                  <template v-else>
                    <div class="w-6 h-6 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-[10px] text-gray-600 dark:text-gray-300">
                      {{ String(c.authorUsername || c.authorId || '').slice(0,1).toUpperCase() || 'U' }}
                    </div>
                  </template>
                  <div class="text-xs text-gray-600 dark:text-gray-300">{{ c.authorUsername || c.authorId }}</div>
                </router-link>
                <span class="text-xs text-gray-400">{{ formatRelativeTime(c.createdAt) }}</span>
              </div>
              <div class="mt-1 text-xs text-gray-600 dark:text-gray-300" v-if="c.replyToPostId">
                回复 <a :href="'#post-' + c.replyToPostId" class="text-blue-600 hover:underline">@{{ c.parentAuthorUsername || c.replyToPostId }}</a>
              </div>
              <div class="mt-2 prose max-w-none dark:prose-invert" v-html="render(c.content)"></div>
              <div class="mt-2 flex items-center gap-2 text-xs">
                <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="setReplyTo(c.id, g.root.id)">回复</button>
              </div>
              <!-- 针对子回复的内联回复输入框 -->
              <div v-if="replyToPostId === c.id" class="mt-2">
                <div v-if="!isLoggedIn" class="text-xs text-gray-500">登录后可发表评论</div>
                <div v-else>
                  <textarea v-if="!previewMode" v-model="content" class="w-[720px] h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100" placeholder="支持基础 Markdown（图片请使用上方上传功能）"></textarea>
                  <div v-else class="w-[720px] h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 prose max-w-none dark:prose-invert" v-html="render(content)"></div>
                  <div class="mt-2 flex items-center gap-2">
                    <label class="inline-flex items-center gap-2 text-xs cursor-pointer">
                      <input type="file" accept="image/*" class="hidden" @change="(e) => { const f=e.target.files?.[0]; if (f) handleUploadImage(f) }" />
                      <span class="rounded px-2 py-1 border dark:border-gray-700">添加图片</span>
                    </label>
                    <button class="rounded px-2 py-1 border dark:border-gray-700" @click="previewMode = !previewMode">{{ previewMode ? '退出预览' : '预览' }}</button>
                    <button class="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700" @click="submit">发送评论</button>
                    <span class="text-xs text-gray-500">回复：#{{ replyToPostId }} <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="cancelReply">取消</button></span>
                  </div>
                </div>
              </div>
            </div>
            <!-- 子回复分页控件（仅当子回复超过每页大小时显示） -->
            <div v-if="g.items.length > (props.childPageSize || 10)" class="mt-2 flex items-center justify-end gap-2 text-xs">
              <button class="rounded px-2 py-1 border dark:border-gray-700 disabled:opacity-50"
                      :disabled="(groupPageMap[g.root.id]||1) <= 1"
                      @click="(() => { const cur = Number(groupPageMap[g.root.id]||1); if (cur>1) groupPageMap[g.root.id] = cur-1 })()">上一页</button>
              <span>第</span>
              <input type="text" class="w-14 text-center rounded border px-1 py-0.5 dark:bg-gray-800 dark:border-gray-700"
                     :value="groupPageMap[g.root.id] || 1"
                     @input="(e)=>{ const v=Number((e.target?.value||'').toString().replace(/[^0-9]/g,'')); if(!isNaN(v)) groupPageMap[g.root.id]=v }"
                     @keyup.enter="(() => { const size = Number(props.childPageSize||10); const max = Math.max(1, Math.ceil(g.items.length/size)); const cur = Number(groupPageMap[g.root.id]||1); groupPageMap[g.root.id] = Math.min(Math.max(1, cur||1), max) })()"
                     @blur="(() => { const size = Number(props.childPageSize||10); const max = Math.max(1, Math.ceil(g.items.length/size)); const cur = Number(groupPageMap[g.root.id]||1); groupPageMap[g.root.id] = Math.min(Math.max(1, cur||1), max) })()" />
              <span>/ {{ Math.max(1, Math.ceil(g.items.length / (props.childPageSize || 10))) }}</span>
              <button class="rounded px-2 py-1 border dark:border-gray-700 disabled:opacity-50"
                      :disabled="(groupPageMap[g.root.id]||1) >= Math.ceil(g.items.length/(props.childPageSize||10))"
                      @click="(() => { const size = Number(props.childPageSize||10); const max = Math.max(1, Math.ceil(g.items.length/size)); const cur = Number(groupPageMap[g.root.id]||1); if (cur<max) groupPageMap[g.root.id] = cur+1 })()">下一页</button>
            </div>
          </div>
        </li>
      </ul>

      <!-- 分页（顶层楼层总数大于每页大小时显示） -->
      <div v-if="hasPagination" class="mt-4 flex items-center justify-end gap-2 text-xs">
<button class="rounded px-2 py-1 border dark:border-gray-700 disabled:opacity-50" :disabled="page<=1" @click="goPrevPage">上一页</button>
        <span>第</span>
<input type="text" class="w-14 text-center rounded border px-1 py-0.5 dark:bg-gray-800 dark:border-gray-700" :value="page" @input="(e)=>{ const v=Number((e.target?.value||'').toString().replace(/[^0-9]/g,'')); if(!isNaN(v)) page=v }" @keyup.enter="applyPageInput" @blur="applyPageInput" />
        <span>/ {{ pageCount }}</span>
<button class="rounded px-2 py-1 border dark:border-gray-700 disabled:opacity-50" :disabled="page>=pageCount" @click="goNextPage">下一页</button>
      </div>

    </div>
  </div>
</template>

<style scoped>
.prose :deep(img) { max-width: 100%; height: auto; }
</style>
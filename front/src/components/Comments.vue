<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { listPosts, createPost } from '@/api/posts'
import { uploadImage } from '@/api/uploads'
import { formatRelativeTime } from '@/composables/time'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { normalizeImageUrl } from '@/utils/image'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { ComponentPublicInstance } from 'vue'
import type { Id, PageResult, Post, ProfileUpdatedDetail } from '@/types'
import type { UploadImageResponse } from '@/api/uploads'

interface CommentsProps {
  threadId: number
  autoCollapseCountThreshold?: number
  autoCollapseWidthThreshold?: number
  autoCollapseHeightThreshold?: number
  childPageSize?: number
  scrollToPostId?: number | null
}

type SortKey = 'time' | 'replies'
type SortOrder = 'asc' | 'desc'

interface CommentItem extends Post {
  children?: CommentItem[]
  parentAuthorUsername?: string | null
  parentAuthorId?: Id | null
  parentAuthorNickname?: string | null
  floorLabel?: string
  depth?: number
  _optimistic?: boolean
}

interface CommentGroup {
  root: CommentItem
  items: CommentItem[]
}

const props = withDefaults(defineProps<CommentsProps>(), {
  autoCollapseCountThreshold: 5,
  autoCollapseWidthThreshold: 720,
  autoCollapseHeightThreshold: 480,
  childPageSize: 10,
  scrollToPostId: null,
})

const loading = ref(false)
const error = ref('')
const items = ref<CommentItem[]>([])
// 将评论分组为：每个顶层评论一个容器，子回复在同一容器内按层级缩进，并生成楼层号
const groups = computed<CommentGroup[]>(() => {
  const list = Array.isArray(items.value) ? items.value.slice() : []
  const nodes: CommentItem[] = list.map((p) => ({ ...p, children: [] }))
  const byId = new Map<Id, CommentItem>(nodes.map((n) => [n.id, n]))
  nodes.forEach(n => {
    const pid = n.replyToPostId
    if (pid && byId.has(pid)) {
      const parent = byId.get(pid)
      if (!parent) return
      parent.children.push(n)
      n.parentAuthorUsername = parent.authorUsername || String(parent.authorId || '')
      n.parentAuthorId = parent.authorId
    }
  })
  const sortByCreated = (a: CommentItem, b: CommentItem) =>
    new Date(a.createdAt || 0).getTime() - new Date(b.createdAt || 0).getTime()
  const roots = nodes.filter(n => !n.replyToPostId || !byId.has(n.replyToPostId)).sort(sortByCreated)
  const result: CommentGroup[] = []
  const walk = (n: CommentItem, depth: number, acc: CommentItem[], path: number[]) => {
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
const sortKey = ref<SortKey>('time')
const sortOrder = ref<SortOrder>('desc')
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

const pagedGroups = computed(() => (sortedGroups.value || []).slice())

const pageCount = computed(() => Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15)))))
const hasPagination = computed(() => Number(total.value || 0) > Math.max(1, Number(size.value || 15)))

// 独立下拉菜单状态与选择函数
const showTimeMenu = ref(false)
const showRepliesMenu = ref(false)
function selectTimeOrder(order: SortOrder) {
  sortKey.value = 'time'
  sortOrder.value = order === 'asc' ? 'asc' : 'desc'
  showTimeMenu.value = false
}
function selectRepliesOrder(order: SortOrder) {
  sortKey.value = 'replies'
  sortOrder.value = order === 'asc' ? 'asc' : 'desc'
  showRepliesMenu.value = false
}

// 顶层容器的折叠/展开状态管理
const collapsedMap = ref<Record<string, boolean>>({})
function toggleCollapse(rootId: Id) {
  const key = String(rootId)
  const cur = !!collapsedMap.value[key]
  collapsedMap.value[key] = !cur
}
// 每个顶层楼层的子回复分页页码
const groupPageMap = ref<Record<string, number>>({})

// 持久化与恢复（按线程维度）：子回复页码与折叠状态
function storageKeyPages() { return 'comments_group_pages_' + String(props.threadId || '') }
function storageKeyCollapsed() { return 'comments_collapsed_map_' + String(props.threadId || '') }
function restoreStateFromStorage(): void {
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
function persistStateToStorage(): void {
  try { localStorage.setItem(storageKeyPages(), JSON.stringify(groupPageMap.value || {})) } catch (_) {}
  try { localStorage.setItem(storageKeyCollapsed(), JSON.stringify(collapsedMap.value || {})) } catch (_) {}
}
// 初次挂载时恢复状态，之后每次变更时持久化
onMounted(() => { restoreStateFromStorage() })
watch(groupPageMap, () => { persistStateToStorage() }, { deep: true })
watch(collapsedMap, () => { persistStateToStorage() }, { deep: true })

// 记录每个楼层容器的元素引用，用于测量宽度
const groupEls = ref<Record<string, HTMLElement>>({})
let resizeObserver: ResizeObserver | null = null
function setGroupEl(rootId: Id, el: Element | null) {
  if (el instanceof HTMLElement) groupEls.value[String(rootId)] = el
}

function setGroupRef(rootId: Id, el: Element | ComponentPublicInstance | null): void {
  setGroupEl(rootId, el instanceof Element ? el : null)
}

function updateAutoCollapse(): void {
  const countTh = Number(props.autoCollapseCountThreshold || 5)
  const widthTh = Number(props.autoCollapseWidthThreshold || 720)
  const heightTh = Number(props.autoCollapseHeightThreshold || 480)
  const currentGroups = groups.value || []
  currentGroups.forEach(g => {
    const key = String(g.root.id)
    const el = groupEls.value[key]
    const width = el?.clientWidth || 0
    const height = el?.scrollHeight || el?.clientHeight || 0
    const shouldCollapse = (g.items.length >= countTh) || (width > widthTh) || (height > heightTh)
    // 仅在首次或尚未定义时设置自动折叠，避免覆盖用户手动切换
    if (typeof collapsedMap.value[key] === 'undefined') {
      collapsedMap.value[key] = shouldCollapse
    }
    // 初始化子回复分页页码
    if (typeof groupPageMap.value[key] === 'undefined') {
      groupPageMap.value[key] = 1
    }
  })
}
const page = ref(1)
const size = ref(15)
const total = ref(0)
const content = ref('')
const replyToPostId = ref<Id | null>(null)
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const previewMode = ref(false)

// 资料更新事件：当我更换头像后，更新当前页面中我发表的评论头像
function onProfileUpdated(evt: Event) {
  try {
    const detail = (evt as CustomEvent<ProfileUpdatedDetail>).detail
    const next = detail?.avatarUrl || ''
    const myId = Number(user?.value?.id || 0)
    if (!myId || !next) return
    const apply = (arr: CommentItem[]) => (arr || []).map(it => (Number(it?.authorId || 0) === myId ? { ...it, authorAvatarUrl: next } : it))
    items.value = apply(items.value)
  } catch (_) {}
}

const md = createMarkdownRenderer({ katex: true, normalizeImages: true })

function renderCommentMarkdownHtml(mdText: string | undefined) {
  return renderMarkdown(md, mdText)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await listPosts(props.threadId, { page: page.value, size: size.value })
    items.value = Array.isArray(data) ? (data as CommentItem[]) : ((data.items || []) as CommentItem[])
    total.value = Array.isArray(data) ? data.length : Number(data.total || 0)
    page.value = Array.isArray(data) ? page.value : Number(data.page || page.value)
    size.value = Array.isArray(data) ? size.value : Number(data.size || size.value)
    tryScrollToId(props.scrollToPostId)
  } catch (_) {
    error.value = '加载评论失败'
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  if (!isLoggedIn.value) {
    error.value = '请先登录再评论'
    return
  }
  const text = String(content.value || '').trim()
  if (!text) return
  if (text.length > 3000) { error.value = '内容过长'; return }
  const optimistic: CommentItem = {
    id: 'temp_' + Math.random().toString(36).slice(2),
    threadId: props.threadId,
    authorId: Number(user.value?.id || 0),
    authorUsername: user.value?.username || '我',
    authorNickname: user.value?.username || '我',
    authorAvatarUrl: '',
    content: text,
    parentAuthorId: null,
    parentAuthorUsername: null,
    parentAuthorNickname: null,
    replyToPostId: replyToPostId.value || null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    _optimistic: true,
  }
  // 乐观更新：在当前页尾部追加
  items.value = [...items.value, optimistic]
  total.value = Number(total.value || 0) + 1
  try {
    const created = await createPost(props.threadId, { contentMd: text, replyToPostId: replyToPostId.value || null })
    items.value = items.value.map(it => (it.id === optimistic.id ? created : it))
    content.value = ''
    replyToPostId.value = null
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } } | null
    error.value = err?.response?.data?.message || '发布失败'
    items.value = items.value.filter(it => it.id !== optimistic.id)
    total.value = Math.max(0, Number(total.value || 0) - 1)
  }
}

async function handleUploadImage(file: File, onProgress?: (percent: number) => void): Promise<void> {
  const token = getStoredAccessToken()
  if (!hasRealToken(token)) {
    error.value = '请先登录后再上传图片'
    return
  }
  const resp: UploadImageResponse = await uploadImage(file, token, onProgress)
  const normalized = normalizeImageUrl(resp?.url || resp?.path || '')
  const insert = `\n\n![](${normalized})\n\n`
  content.value = (content.value || '') + insert
}

function getInputFile(event: Event): File | null {
  const target = event.target as HTMLInputElement | null
  return target?.files?.[0] || null
}

function onSelectUploadImage(event: Event): void {
  const file = getInputFile(event)
  if (file) handleUploadImage(file)
}

function getInputNumber(event: Event): number | null {
  const target = event.target as HTMLInputElement | null
  const value = Number(String(target?.value || '').replace(/[^0-9]/g, ''))
  return Number.isNaN(value) ? null : value
}

function updateGroupPageInput(rootId: Id, event: Event): void {
  const value = getInputNumber(event)
  if (value != null) {
    groupPageMap.value[String(rootId)] = value
  }
}

function clampGroupPage(rootId: Id, itemCount: number): void {
  const sizeChild = Number(props.childPageSize || 10)
  const max = Math.max(1, Math.ceil(itemCount / sizeChild))
  const cur = Number(groupPageMap.value[String(rootId)] || 1)
  groupPageMap.value[String(rootId)] = Math.min(Math.max(1, cur || 1), max)
}

function updatePageInput(event: Event): void {
  const value = getInputNumber(event)
  if (value != null) {
    page.value = value
  }
}

function setReplyTo(id: Id, rootId?: Id) {
  replyToPostId.value = id
  // 如果传入了 rootId（子回复），确保所在页可见，并展开楼层
  if (rootId) {
    const g = (groups.value || []).find(x => x.root?.id === rootId)
    if (g) {
      const size = Number(props.childPageSize || 10)
      const idx = g.items.findIndex(x => x.id === id)
      if (idx >= 0) {
        const pageOfChild = Math.floor(idx / size) + 1
        groupPageMap.value[String(rootId)] = pageOfChild
      }
      collapsedMap.value[String(rootId)] = false
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
function tryScrollToId(id: Id | null | undefined): void {
  const targetId = Number(id || 0)
  if (!targetId) return
  const gs = groups.value || []
  let targetGroup: CommentGroup | null = null
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
  collapsedMap.value[String(rootId)] = false
  // 若是子回复，切换到包含该子回复的页码
  if (!isRoot) {
    const idx = (targetGroup.items || []).findIndex(c => Number(c?.id || 0) === targetId)
    const sizeChild = Math.max(1, Number(props.childPageSize || 10))
    if (idx >= 0) groupPageMap.value[String(rootId)] = Math.floor(idx / sizeChild) + 1
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

onMounted(load)
onMounted(() => { window.addEventListener('profile-updated', onProfileUpdated) })
watch(() => props.threadId, () => { page.value = 1; load() })

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

watch([page, size], () => {
  nextTick(() => {
    load()
  })
})

function goPrevPage() {
  const max = Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15))))
  page.value = Math.min(Math.max(1, Number(page.value || 1) - 1), max)
}

function goNextPage() {
  const max = Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15))))
  page.value = Math.min(max, Number(page.value || 1) + 1)
}

function applyPageInput() {
  const max = Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15))))
  page.value = Math.min(Math.max(1, Number(page.value || 1)), max)
}

watch([sortKey, sortOrder], () => {
  groupPageMap.value = {}
  collapsedMap.value = {}
})

// 计算指定顶层楼层的当前页子回复列表
function getChildrenPage(g: CommentGroup): CommentItem[] {
  const size = Number(props.childPageSize || 10)
  const pg = Number(groupPageMap.value[String(g.root.id)] || 1)
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
<textarea v-if="!previewMode" v-model="content" class="w-full h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="支持基础 Markdown（图片请使用上方上传功能）"></textarea>
          <div v-else class="w-full h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 prose max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(content)"></div>
        <div class="mt-2 flex items-center gap-2">
          <label class="inline-flex items-center gap-2 text-xs cursor-pointer">
            <input type="file" accept="image/*" class="hidden" @change="onSelectUploadImage" />
            <span class="rounded px-2 py-1 border dark:border-gray-700">添加图片</span>
          </label>
          <button class="rounded px-2 py-1 border dark:border-gray-700" @click="previewMode = !previewMode">{{ previewMode ? '退出预览' : '预览' }}</button>
<button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-1 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="submit">发送评论</button>
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
<li v-for="g in pagedGroups" :key="g.root.id" class="rounded-md border border-gray-200 bg-white p-3 dark:bg-gray-800 dark:border-gray-700" :ref="el => setGroupRef(g.root.id, el)">
          <!-- 顶层评论（容器顶部） -->
          <div :id="'post-' + g.root.id" class="">
              <div class="flex items-center justify-between">
                <router-link :to="g.root.authorId ? ('/users/' + g.root.authorId) : '/users'" class="flex items-center gap-2 hover:opacity-90">
                  <img 
                    :src="g.root.authorAvatarUrl ? normalizeImageUrl(g.root.authorAvatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${g.root.authorNickname || g.root.authorUsername || 'U'}`" 
                    alt="头像" 
                    class="w-7 h-7 rounded-full object-cover bg-gray-100 dark:bg-gray-700" 
                    loading="lazy" 
                  />
                  <div class="text-xs text-gray-600 dark:text-gray-300">{{ g.root.authorNickname || g.root.authorUsername }}</div>
                </router-link>
                <span class="text-xs text-gray-400">{{ g.root.floorLabel }} · {{ formatRelativeTime(g.root.createdAt) }}</span>
              </div>
            <div class="mt-2 prose max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(g.root.content)"></div>
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
<textarea v-if="!previewMode" v-model="content" class="w-[720px] h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="支持基础 Markdown（图片请使用上方上传功能）"></textarea>
              <div v-else class="w-[720px] h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 prose max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(content)"></div>
              <div class="mt-2 flex items-center gap-2">
                <label class="inline-flex items-center gap-2 text-xs cursor-pointer">
                  <input type="file" accept="image/*" class="hidden" @change="onSelectUploadImage" />
                  <span class="rounded px-2 py-1 border dark:border-gray-700">添加图片</span>
                </label>
                <button class="rounded px-2 py-1 border dark:border-gray-700" @click="previewMode = !previewMode">{{ previewMode ? '退出预览' : '预览' }}</button>
<button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-1 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="submit">发送评论</button>
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
                  <img 
                    :src="c.authorAvatarUrl ? normalizeImageUrl(c.authorAvatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${c.authorNickname || c.authorUsername || 'U'}`" 
                    alt="头像" 
                    class="w-6 h-6 rounded-full object-cover bg-gray-100 dark:bg-gray-700" 
                    loading="lazy" 
                  />
                  <div class="text-xs text-gray-600 dark:text-gray-300">{{ c.authorNickname || c.authorUsername }}</div>
                </router-link>
                <span class="text-xs text-gray-400">{{ formatRelativeTime(c.createdAt) }}</span>
              </div>
              <div class="mt-1 text-xs text-gray-600 dark:text-gray-300" v-if="c.replyToPostId">
                回复 <a :href="'#post-' + c.replyToPostId" class="text-brandDay-600 dark:text-brandNight-400 hover:underline">@{{ c.parentAuthorNickname || c.parentAuthorUsername }}</a>
              </div>
              <div class="mt-2 prose max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(c.content)"></div>
              <div class="mt-2 flex items-center gap-2 text-xs">
                <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="setReplyTo(c.id, g.root.id)">回复</button>
              </div>
              <!-- 针对子回复的内联回复输入框 -->
              <div v-if="replyToPostId === c.id" class="mt-2">
                <div v-if="!isLoggedIn" class="text-xs text-gray-500">登录后可发表评论</div>
                <div v-else>
<textarea v-if="!previewMode" v-model="content" class="w-[720px] h-[140px] resize-none rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="支持基础 Markdown（图片请使用上方上传功能）"></textarea>
                  <div v-else class="w-[720px] h-[140px] overflow-auto rounded-md border border-gray-300 bg-white px-3 py-2 text-sm dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 prose max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(content)"></div>
                  <div class="mt-2 flex items-center gap-2">
                    <label class="inline-flex items-center gap-2 text-xs cursor-pointer">
                      <input type="file" accept="image/*" class="hidden" @change="onSelectUploadImage" />
                      <span class="rounded px-2 py-1 border dark:border-gray-700">添加图片</span>
                    </label>
                    <button class="rounded px-2 py-1 border dark:border-gray-700" @click="previewMode = !previewMode">{{ previewMode ? '退出预览' : '预览' }}</button>
<button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-1 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="submit">发送评论</button>
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
                     @input="updateGroupPageInput(g.root.id, $event)"
                     @keyup.enter="clampGroupPage(g.root.id, g.items.length)"
                     @blur="clampGroupPage(g.root.id, g.items.length)" />
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
<input type="text" class="w-14 text-center rounded border px-1 py-0.5 dark:bg-gray-800 dark:border-gray-700" :value="page" @input="updatePageInput" @keyup.enter="applyPageInput" @blur="applyPageInput" />
        <span>/ {{ pageCount }}</span>
<button class="rounded px-2 py-1 border dark:border-gray-700 disabled:opacity-50" :disabled="page>=pageCount" @click="goNextPage">下一页</button>
      </div>

    </div>
  </div>
</template>

<style scoped>
</style>

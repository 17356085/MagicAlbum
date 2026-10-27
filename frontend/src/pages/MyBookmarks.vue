<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { listBookmarks, type BookmarkPage } from '@/api/bookmarkPages'
import Pagination from '@/components/Pagination.vue'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Id } from '@/types'

const authStore = useAuthStore()
const { token, user } = storeToRefs(authStore)

const page = ref(1)
const size = ref(10)
const loading = ref(true)
const error = ref('')
const bookmarks = ref<BookmarkPage>({ items: [], page: 1, size: 10, total: 0 })

interface SessionSnapshot {
  token: string
  epoch: number
  ownerId: Id | null
}

let sessionEpoch = 0
let requestSequence = 0
let reloadQueued = false
let mounted = false
let disposed = false
let observedToken = ''
let observedStoredToken = ''
let observedOwnerKey: string | null = null

function normalizeId(value: unknown): Id | null {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : null
  if (typeof value === 'string' && value.trim()) return value.trim()
  return null
}

function idKey(value: unknown): string | null {
  const normalized = normalizeId(value)
  return normalized === null ? null : String(normalized)
}

function currentToken(): string {
  return String(token.value || '').trim()
}

function storedToken(): string {
  return String(getStoredAccessToken() || '').trim()
}

function currentOwnerId(): Id | null {
  return normalizeId(user.value?.id ?? user.value?.userId)
}

function emptyBookmarks(): BookmarkPage {
  return { items: [], page: 1, size: size.value, total: 0 }
}

function captureSession(): SessionSnapshot | null {
  const accessToken = currentToken()
  if (!hasRealToken(accessToken) || accessToken !== storedToken()) return null
  return { token: accessToken, epoch: sessionEpoch, ownerId: currentOwnerId() }
}

function sameOwner(expected: Id | null, actual: Id | null): boolean {
  // Token-only sessions may be hydrated with their confirmed owner later.
  if (expected === null) return true
  return actual !== null && idKey(expected) === idKey(actual)
}

function isCurrentSession(context: SessionSnapshot): boolean {
  return !disposed
    && context.epoch === sessionEpoch
    && currentToken() === context.token
    && storedToken() === context.token
    && sameOwner(context.ownerId, currentOwnerId())
}

function isCurrentRequest(context: SessionSnapshot, requestId: number): boolean {
  return isCurrentSession(context) && requestId === requestSequence
}

function resetPrivateState(): void {
  page.value = 1
  error.value = ''
  bookmarks.value = emptyBookmarks()
}

function queueReload(): void {
  if (disposed || !mounted || reloadQueued) return
  reloadQueued = true
  queueMicrotask(() => {
    reloadQueued = false
    if (disposed || !mounted) return
    void load()
  })
}

function resetAndQueueReload(): void {
  if (disposed) return
  sessionEpoch += 1
  requestSequence += 1
  resetPrivateState()
  loading.value = false
  queueReload()
}

function sessionUnavailableMessage(): string {
  return hasRealToken(currentToken()) || hasRealToken(storedToken())
    ? '账号状态已变更，请刷新页面后重试'
    : '请登录后再加载收藏'
}

function clearUnavailableSession(): void {
  if (disposed) return
  sessionEpoch += 1
  requestSequence += 1
  resetPrivateState()
  loading.value = false
  if (hasRealToken(currentToken()) && currentToken() === storedToken()) queueReload()
  else error.value = sessionUnavailableMessage()
}

function observeSessionSnapshot(): boolean {
  const nextToken = currentToken()
  const nextStoredToken = storedToken()
  const nextOwnerKey = idKey(currentOwnerId())
  const tokenChanged = nextToken !== observedToken || nextStoredToken !== observedStoredToken
  const sameTokenHydration = !tokenChanged && observedOwnerKey === null && nextOwnerKey !== null
  const ownerChanged = !sameTokenHydration && nextOwnerKey !== observedOwnerKey
  observedToken = nextToken
  observedStoredToken = nextStoredToken
  observedOwnerKey = nextOwnerKey
  return tokenChanged || ownerChanged
}

function handleStaleRequest(context: SessionSnapshot, requestId: number): void {
  if (disposed || requestId !== requestSequence || context.epoch !== sessionEpoch) return
  if (!isCurrentSession(context)) clearUnavailableSession()
}

function isPositiveSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isNonNegativeSafeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function normalizeResult(result: BookmarkPage, requestedPage: number): BookmarkPage {
  if (!result || typeof result !== 'object' || !Array.isArray(result.items)) {
    throw new Error('Invalid bookmarks response')
  }
  const resultPage = result.page === undefined ? requestedPage : result.page
  const resultSize = result.size === undefined ? size.value : result.size
  const resultTotal = result.total === undefined ? result.items.length : result.total
  if (!isPositiveSafeInteger(resultPage)
    || !isPositiveSafeInteger(resultSize)
    || !isNonNegativeSafeInteger(resultTotal)) {
    throw new Error('Invalid bookmarks pagination metadata')
  }
  return {
    ...result,
    items: result.items,
    page: resultPage,
    size: resultSize,
    total: resultTotal,
  }
}

function totalPagesFor(result: BookmarkPage): number {
  return Math.max(1, Math.ceil(result.total / (result.size || size.value)))
}

function fallbackPageFor(result: BookmarkPage, requestedPage: number): number | null {
  if (result.totalKnown === false) return null
  const totalPages = totalPagesFor(result)
  const returnedPage = result.page
  if (requestedPage <= totalPages && returnedPage <= totalPages) return null
  const target = Math.min(Math.max(1, returnedPage), totalPages)
  return target
}

function assertResultMatchesPage(result: BookmarkPage, requestedPage: number): void {
  if (result.page !== requestedPage || (result.totalKnown !== false && requestedPage > totalPagesFor(result))) {
    throw new Error('Invalid bookmarks page metadata')
  }
}

const totalPages = computed(() => totalPagesFor(bookmarks.value))
const hasKnownTotal = computed(() => bookmarks.value.totalKnown !== false)
const actualPageSize = computed(() => bookmarks.value.size || size.value)
const canLoadNextUnknown = computed(() => !hasKnownTotal.value
  && page.value < Number.MAX_SAFE_INTEGER
  && bookmarks.value.items.length >= actualPageSize.value)

function formatBookmarkedAt(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value || ''
  return date.toLocaleString()
}

async function load(nextPage = page.value): Promise<void> {
  if (disposed) return
  const context = captureSession()
  if (!context) {
    clearUnavailableSession()
    return
  }

  const requestId = ++requestSequence
  const requestedPage = Math.min(Math.max(1, Number(nextPage) || 1), Number.MAX_SAFE_INTEGER)
  loading.value = true
  error.value = ''
  try {
    let result = normalizeResult(await listBookmarks({ page: requestedPage, size: size.value }), requestedPage)
    if (!isCurrentRequest(context, requestId)) {
      handleStaleRequest(context, requestId)
      return
    }

    const fallbackPage = fallbackPageFor(result, requestedPage)
    if (fallbackPage === null) {
      assertResultMatchesPage(result, requestedPage)
    } else {
      page.value = fallbackPage
      result = normalizeResult(await listBookmarks({ page: fallbackPage, size: size.value }), fallbackPage)
      if (!isCurrentRequest(context, requestId)) {
        handleStaleRequest(context, requestId)
        return
      }
      assertResultMatchesPage(result, fallbackPage)
    }

    bookmarks.value = result
    page.value = result.page
  } catch (_) {
    if (isCurrentRequest(context, requestId)) error.value = '收藏加载失败，请稍后重试'
    else handleStaleRequest(context, requestId)
  } finally {
    if (isCurrentRequest(context, requestId)) loading.value = false
    else handleStaleRequest(context, requestId)
  }
}

function retry(): void {
  void load(page.value)
}

function setPage(nextPage: number): void {
  if (loading.value || !Number.isSafeInteger(nextPage) || nextPage <= 0) return
  const currentPage = page.value
  let target: number
  if (hasKnownTotal.value) {
    target = Math.min(nextPage, totalPages.value)
  } else if (nextPage === currentPage - 1 && currentPage > 1) {
    target = nextPage
  } else if (nextPage === currentPage + 1 && canLoadNextUnknown.value) {
    target = nextPage
  } else {
    return
  }
  if (target === page.value) return
  page.value = target
  void load(target)
}

const stopSessionWatch = watch(
  [() => currentToken(), () => storedToken(), () => idKey(currentOwnerId())],
  () => {
    if (!mounted || disposed) return
    if (observeSessionSnapshot()) resetAndQueueReload()
  },
  { flush: 'sync' },
)

function onAuthStateChanged(): void {
  if (disposed) return
  observeSessionSnapshot()
  resetAndQueueReload()
}

function onStorageChanged(event: StorageEvent): void {
  if (disposed || (event.storageArea !== null && event.storageArea !== localStorage)) return
  if (event.key !== null && event.key !== 'accessToken') return
  if (observeSessionSnapshot()) resetAndQueueReload()
}

onMounted(() => {
  mounted = true
  observedToken = currentToken()
  observedStoredToken = storedToken()
  observedOwnerKey = idKey(currentOwnerId())
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  window.addEventListener('storage', onStorageChanged)
  void load()
})

onUnmounted(() => {
  disposed = true
  mounted = false
  sessionEpoch += 1
  requestSequence += 1
  reloadQueued = false
  stopSessionWatch()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  window.removeEventListener('storage', onStorageChanged)
})
</script>

<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">我的收藏</h1>
        <p class="ui-description">收藏过的帖子会保存在你的账户中。</p>
      </div>
    </header>

    <section class="ui-panel overflow-hidden">
      <div v-if="loading" role="status" class="ui-empty">正在加载收藏...</div>

      <div v-else-if="error" role="alert" class="ui-error m-4">
        <p>{{ error }}</p>
        <button type="button" class="ui-button mt-3" @click="retry">重试</button>
      </div>

      <div v-else-if="bookmarks.items.length === 0" class="ui-empty">
        <template v-if="!hasKnownTotal && page > 1">
          <h2>本页暂无收藏</h2>
          <p>可以返回上一页继续查看。</p>
        </template>
        <template v-else>
          <h2>暂无收藏</h2>
          <p>收藏帖子后，它们会出现在这里。</p>
        </template>
      </div>

      <ul v-else>
        <li v-for="item in bookmarks.items" :key="String(item.threadId)" class="ui-list-row">
          <router-link :to="`/threads/${item.threadId}`" class="block min-w-0">
            <h2 class="line-clamp-2 text-base font-semibold leading-6 text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
              {{ item.title }}
            </h2>
            <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-[var(--ui-muted)]">
              <span class="ui-tag">{{ item.sectionName || '未分区' }}</span>
              <span>收藏于 {{ formatBookmarkedAt(item.bookmarkedAt) }}</span>
            </div>
          </router-link>
        </li>
      </ul>

      <div v-if="!loading && !error && hasKnownTotal && bookmarks.items.length > 0" class="border-t border-[var(--ui-border)] px-4 py-4 sm:px-6">
        <Pagination :page="page" :size="bookmarks.size || size" :total="bookmarks.total" @update:page="setPage" />
      </div>

      <div v-else-if="!loading && !error && !hasKnownTotal && (bookmarks.items.length > 0 || page > 1)" class="border-t border-[var(--ui-border)] px-4 py-4 sm:px-6">
        <nav aria-label="收藏翻页" class="flex flex-wrap items-center justify-between gap-4">
          <button type="button" class="ui-button disabled:opacity-50" :disabled="page <= 1" @click="setPage(page - 1)">上一页</button>
          <div class="flex items-center gap-3 text-sm text-gray-600 dark:text-gray-300">
            <span>第 {{ page }} 页</span>
            <span>暂未获取总数</span>
          </div>
          <button type="button" class="ui-button disabled:opacity-50" :disabled="!canLoadNextUnknown" @click="setPage(page + 1)">下一页</button>
        </nav>
      </div>
    </section>
  </section>
</template>

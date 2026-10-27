<template>
  <section class="ui-page">
    <div v-if="loading && !profileLoaded" class="ui-panel ui-empty" role="status">加载中...</div>
    <div v-else-if="error && !profileLoaded" class="ui-error flex items-center justify-between gap-3" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="ui-button shrink-0" :disabled="profileReadPending" @click="retryProfile">重试</button>
    </div>
    <section v-else class="ui-panel space-y-6 p-4 sm:p-6">
      <div v-if="error" class="ui-error flex items-center justify-between gap-3" role="alert">
        <span>{{ error }}</span>
        <button type="button" class="ui-button shrink-0" :disabled="profileReadPending" @click="retryProfile">重试</button>
      </div>
      <header class="ui-page-header mb-0">
        <div class="flex min-w-0 items-start gap-4">
          <button type="button" class="ui-button shrink-0 px-3" @click="safeBack()" aria-label="返回上一页" title="返回上一页">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
              <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
            </svg>
          </button>
          <div class="flex min-w-0 items-center gap-4">
            <template v-if="profile.avatarUrl">
              <img :src="normalizeImageUrl(profile.avatarUrl)" alt="头像" class="h-20 w-20 shrink-0 rounded-full border-4 border-[var(--ui-subtle)] object-cover" />
            </template>
            <template v-else>
              <div class="flex h-20 w-20 shrink-0 items-center justify-center rounded-full bg-[var(--ui-subtle)] text-2xl text-[var(--ui-muted)]">
                {{ String(profile.nickname || profile.username || 'U').slice(0,1).toUpperCase() }}
              </div>
            </template>
            <div class="min-w-0">
              <h1 class="ui-title truncate">{{ profile.nickname || profile.username || '未命名用户' }}</h1>
              <p v-if="profile.username" class="ui-description">{{ profile.username }}</p>
              <div class="mt-2 flex flex-wrap items-center gap-2 text-xs">
                <router-link :to="{ name: 'user-followers', params: { id: userId } }" class="ui-tag">粉丝 {{ Number(profile.followerCount || 0) }}</router-link>
                <router-link :to="{ name: 'user-following', params: { id: userId } }" class="ui-tag">关注 {{ Number(profile.followingCount || 0) }}</router-link>
                <span v-if="!isMe && profile.followingMe" class="ui-tag">关注了你</span>
              </div>
              <div v-if="profile.homepageUrl" class="mt-2 truncate text-sm text-[var(--ui-accent)]">
                <span class="mr-1 text-[var(--ui-muted)]">主页链接：</span><a :href="profile.homepageUrl" target="_blank" rel="noopener">{{ profile.homepageUrl }}</a>
              </div>
              <div v-if="profile.location" class="mt-1 text-sm text-[var(--ui-muted)]">所在地：{{ profile.location }}</div>
            </div>
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2 self-start">
          <router-link v-if="isMe" to="/settings" class="ui-button-primary">编辑资料</router-link>
          <button v-else type="button" class="ui-button" :class="followedByMe === true ? 'text-[var(--ui-muted)]' : 'ui-button-primary'" :disabled="followButtonDisabled" @click="toggleFollow">
            {{ followSaving ? '处理中...' : (followedByMe === true ? '已关注' : '关注') }}
          </button>
        </div>
      </header>

      <section class="border-t border-[var(--ui-border)] pt-5">
        <h2 class="text-sm font-semibold text-[var(--ui-muted)]">个人介绍</h2>
        <template v-if="profile.bio && String(profile.bio).trim()">
          <div class="prose mt-2 max-w-none text-[var(--ui-ink)] dark:prose-invert" v-html="renderBioMarkdownHtml(profile.bio)"></div>
        </template>
        <template v-else>
          <p class="mt-2 text-[var(--ui-muted)]">这个人很神秘，什么都没有写。</p>
        </template>
        <div v-if="followStateError" class="ui-error mt-3 flex items-center justify-between gap-3" role="alert">
          <span>{{ followStateError }}</span>
          <button type="button" class="ui-button shrink-0 px-2 py-1 text-xs" :disabled="profileReadPending" @click="retryProfile">重试</button>
        </div>
        <div v-if="followError" class="ui-error mt-3" role="alert">{{ followError }}</div>
      </section>

      <section class="border-t border-[var(--ui-border)] pt-5">
        <h2 class="mb-2 text-sm font-semibold text-[var(--ui-muted)]">Ta的帖子</h2>
        <div v-if="threadsLoading" class="ui-empty" role="status">正在加载...</div>
        <template v-else>
          <div v-if="threadsError" class="ui-error mb-3" role="alert">{{ threadsError }}</div>
          <template v-if="threads.items && threads.items.length">
            <ul>
              <li v-for="t in threads.items" :key="t.id" class="ui-list-row">
                <router-link :to="`/threads/${t.id}`" class="block">
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <div class="font-medium text-[var(--ui-ink)]">{{ t.title }}</div>
                    <span class="text-xs text-[var(--ui-muted)]">#{{ t.id }}</span>
                  </div>
                  <div class="mt-1 text-xs text-[var(--ui-muted)]">更新于：{{ formatRelativeTime(t.updatedAt || t.createdAt) }}</div>
                </router-link>
              </li>
            </ul>
          </template>
          <div v-else class="ui-empty px-0 py-8" role="status"><h2>暂无帖子</h2></div>
          <div class="mt-3 flex flex-col gap-3 border-t border-[var(--ui-border)] pt-3 text-sm sm:flex-row sm:items-center sm:justify-between">
            <div class="text-xs text-[var(--ui-muted)]">共 {{ threads.total || 0 }} 条 · 每页 {{ size }} 条</div>
            <div class="flex flex-wrap items-center gap-2">
              <button type="button" class="ui-button" :disabled="page<=1" @click="prevPage">上一页</button>
              <span class="text-sm text-[var(--ui-muted)]">第</span>
              <input v-model="inputPage" aria-label="页码" class="ui-field w-16 text-center" inputmode="numeric" @keyup.enter="goToInputPage" @blur="goToInputPage" />
              <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
              <button type="button" class="ui-button" :disabled="page>=totalPages" @click="nextPage">下一页</button>
            </div>
          </div>
        </template>
      </section>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { followUser, getUserProfile, listUserThreads, unfollowUser } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'
import { safeBack as navigateBackSafely } from '@/utils/router'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { formatRelativeTime } from '@/composables/time'
import { InteractionResponseError } from '@/api/interactionState'
import type { PageResult, Thread, User, UserProfile as UserProfileModel } from '@/types'

const route = useRoute()
const router = useRouter()
type ProfileView = Partial<User> & Partial<UserProfileModel>

function getRouteParamId(value: string | string[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

function createEmptyProfile(): ProfileView {
  return {
    username: '',
    nickname: '',
    bio: '',
    homepageUrl: '',
    location: '',
    avatarUrl: '',
  }
}

function createEmptyThreads(page = 1, size = 10): PageResult<Thread> {
  return { items: [], page, size, total: 0 }
}

const userId = computed(() => getRouteParamId(route.params.id as string | string[] | undefined))
const loading = ref(true)
const error = ref('')
const profileLoaded = ref(false)
const profileReadPending = ref(false)
const profile = ref<ProfileView>(createEmptyProfile())
const authStore = useAuthStore()
const { isLoggedIn, user, token } = storeToRefs(authStore)
const isMe = computed(() => String(user?.value?.id || '') === String(userId.value || ''))
const followSaving = ref(false)
const followError = ref('')
const followStateError = ref('')

const md = createMarkdownRenderer({ katex: true, normalizeImages: true })

function safeBack(): void {
  navigateBackSafely(router)
}

function renderBioMarkdownHtml(raw: string | undefined) {
  return renderMarkdown(md, raw)
}

// 主题帖分页状态
const threadsLoading = ref(false)
const threadsError = ref('')
const threads = ref<PageResult<Thread>>(createEmptyThreads())
const page = ref(1)
const size = ref(10)
const inputPage = ref('1')
const totalPages = computed(() => {
  const s = Math.max(1, Number(size.value || 10))
  const total = Number(threads.value.total || 0)
  return Math.max(1, Math.ceil(total / s))
})

function readRelationship(value: unknown): boolean | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as { followedByMe?: unknown; following?: unknown }
  const hasFollowedByMe = Object.prototype.hasOwnProperty.call(record, 'followedByMe')
  const hasFollowing = Object.prototype.hasOwnProperty.call(record, 'following')
  if (hasFollowedByMe && typeof record.followedByMe !== 'boolean') return null
  if (hasFollowing && typeof record.following !== 'boolean') return null
  const followedByMe = hasFollowedByMe ? record.followedByMe as boolean : undefined
  const following = hasFollowing ? record.following as boolean : undefined
  if (followedByMe !== undefined && following !== undefined && followedByMe !== following) return null
  return followedByMe ?? following ?? null
}

const followedByMe = computed<boolean | null>(() => readRelationship(profile.value))
const followButtonDisabled = computed(() => followSaving.value || (
  Boolean(isLoggedIn.value) && (followedByMe.value === null || Boolean(followStateError.value))
))

function normalizeCount(value: unknown): number | null {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) return null
  return value
}

function normalizeFollowState(value: unknown, requestedUserId: string): NormalizedFollowState | null {
  if (typeof value !== 'object' || value === null) return null
  const record = value as {
    userId?: unknown
    followedByMe?: unknown
    following?: unknown
    followingMe?: unknown
    followerCount?: unknown
    followingCount?: unknown
  }
  const relationship = readRelationship(record)
  if (relationship === null) return null
  if (record.userId !== undefined && String(record.userId) !== requestedUserId) return null
  if (record.followingMe !== undefined && typeof record.followingMe !== 'boolean') return null
  const followerCount = normalizeCount(record.followerCount)
  const followingCount = normalizeCount(record.followingCount)
  if (followerCount === null || followingCount === null) return null
  return {
    following: relationship,
    followingMe: record.followingMe as boolean | undefined,
    followerCount,
    followingCount,
  }
}

function setProfileRelationUnknown(): void {
  profile.value = {
    ...profile.value,
    followedByMe: undefined,
    followingMe: undefined,
  }
}

function setStorageDriftError(kind: 'profile' | 'threads' | 'follow', context: ViewerContext, requestSeq?: number): boolean {
  if (disposed || (!storageDrifted(context) && validViewerPair(context))) return false
  if (kind === 'profile' && requestSeq !== undefined && requestSeq !== profileRequestSeq) return false
  if (kind === 'threads' && requestSeq !== undefined && requestSeq !== threadsRequestSeq) return false
  if (kind === 'follow' && requestSeq !== undefined && requestSeq !== followRequestSeq) return false
  if (kind !== 'threads' && String(userId.value) !== (context as { userId?: string }).userId) return false
  observeViewerContext(false)
  if (kind === 'profile') {
    error.value = SESSION_CHANGED_MESSAGE
    loading.value = false
    profileReadPending.value = false
  } else if (kind === 'threads') {
    threadsError.value = SESSION_CHANGED_MESSAGE
    threadsLoading.value = false
  } else {
    followError.value = SESSION_CHANGED_MESSAGE
    followSaving.value = false
  }
  return true
}

const SESSION_CHANGED_MESSAGE = '登录状态已变化，请重试'

interface ViewerContext {
  token: string
  storedToken: string
  loggedIn: boolean
  knownOwner: string
}

interface ProfileRequestContext extends ViewerContext {
  userId: string
  epoch: number
  relationRevision: number
}

interface ThreadsRequestContext extends ViewerContext {
  userId: string
  page: number
  size: number
}

interface FollowRequestContext extends ViewerContext {
  userId: string
  epoch: number
}

interface NormalizedFollowState {
  following: boolean
  followingMe?: boolean
  followerCount: number
  followingCount: number
}

let profileRequestSeq = 0
let threadsRequestSeq = 0
let followRequestSeq = 0
let relationRevision = 0
let viewerEpoch = 0
let disposed = false
let refreshQueued = false
let refreshThreadsQueued = false
let refreshPreserveProfile = true
let observedViewer = captureViewerContext()

function knownOwnerId(): string {
  const currentUser = user.value
  const id = currentUser?.id ?? currentUser?.userId
  if (id === undefined || id === null || !String(id).trim()) return ''
  return String(id).trim()
}

function captureViewerContext(): ViewerContext {
  const currentToken = String(token.value || '')
  return {
    token: currentToken,
    storedToken: getStoredAccessToken(),
    loggedIn: Boolean(isLoggedIn.value) && hasRealToken(currentToken),
    knownOwner: knownOwnerId(),
  }
}

function sameViewerContext(left: ViewerContext, right: ViewerContext): boolean {
  return left.token === right.token
    && left.storedToken === right.storedToken
    && left.loggedIn === right.loggedIn
    && left.knownOwner === right.knownOwner
}

function isHydration(previous: ViewerContext, next: ViewerContext): boolean {
  return previous.loggedIn && next.loggedIn
    && previous.token === next.token
    && previous.storedToken === next.storedToken
    && !previous.knownOwner
    && Boolean(next.knownOwner)
}

function validViewerPair(context: ViewerContext): boolean {
  return context.token === context.storedToken
    && (context.token === '' || hasRealToken(context.token))
}

function storageDrifted(context: ViewerContext): boolean {
  const current = captureViewerContext()
  return current.token !== context.token || current.storedToken !== context.storedToken
}

function observeViewerContext(triggerRefresh: boolean, explicitEvent = false): { changed: boolean; hydration: boolean } {
  const next = captureViewerContext()
  const previous = observedViewer
  const changed = !sameViewerContext(previous, next)
  const hydration = changed && isHydration(previous, next)
  observedViewer = next

  if (hydration) return { changed: true, hydration: true }
  if (changed) {
    viewerEpoch += 1
    if (triggerRefresh) queueContextRefresh({ preserveProfile: true, relationChanged: true })
  } else if (explicitEvent && triggerRefresh) {
    queueContextRefresh({ preserveProfile: true, relationChanged: false })
  }
  return { changed, hydration: false }
}

function currentProfileContext(context: ProfileRequestContext, requestSeq: number): boolean {
  if (disposed || requestSeq !== profileRequestSeq || String(userId.value) !== context.userId) return false
  const current = captureViewerContext()
  if (!sameViewerContext(context, current)) {
    observeViewerContext(false)
  }
  if (context.epoch !== viewerEpoch) return false
  if (current.token !== context.token || current.storedToken !== context.storedToken) return false
  return context.knownOwner === current.knownOwner
    || (context.loggedIn && current.loggedIn && !context.knownOwner && Boolean(current.knownOwner))
}

function currentThreadsContext(context: ThreadsRequestContext, requestSeq: number): boolean {
  return !disposed
    && requestSeq === threadsRequestSeq
    && String(userId.value) === context.userId
    && page.value === context.page
    && size.value === context.size
}

function currentFollowContext(context: FollowRequestContext, requestSeq: number): boolean {
  if (disposed || requestSeq !== followRequestSeq || String(userId.value) !== context.userId) return false
  const current = captureViewerContext()
  if (!sameViewerContext(context, current)) observeViewerContext(false)
  if (context.epoch !== viewerEpoch) return false
  if (current.token !== context.token || current.storedToken !== context.storedToken) return false
  return context.knownOwner === current.knownOwner
    || (context.loggedIn && current.loggedIn && !context.knownOwner && Boolean(current.knownOwner))
}

function errorMessage(errorValue: unknown, fallback: string): string {
  if (typeof errorValue === 'object' && errorValue !== null && 'response' in errorValue) {
    const response = (errorValue as { response?: { data?: { message?: unknown } } }).response
    if (typeof response?.data?.message === 'string' && response.data.message.trim()) return response.data.message
  }
  return errorValue instanceof Error && errorValue.message.trim() ? errorValue.message : fallback
}

function prevPage() {
  if (page.value > 1) {
    page.value -= 1
    loadThreads()
  }
}

function nextPage() {
  if (page.value < totalPages.value) {
    page.value += 1
    loadThreads()
  }
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  const target = Math.min(Math.max(1, Number(raw)), totalPages.value)
  if (target === page.value) {
    inputPage.value = String(page.value || 1)
    return
  }
  page.value = target
  loadThreads()
}

watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

async function loadThreads(): Promise<void> {
  const requestSeq = ++threadsRequestSeq
  const viewer = captureViewerContext()
  const context: ThreadsRequestContext = {
    ...viewer,
    userId: userId.value,
    page: page.value,
    size: size.value,
  }
  const requestedUserId = context.userId
  const requestedPage = context.page
  const requestedSize = context.size
  threadsLoading.value = true
  threadsError.value = ''
  if (!validViewerPair(context)) {
    setStorageDriftError('threads', context, requestSeq)
    return
  }
  try {
    const data = await listUserThreads(requestedUserId, { page: requestedPage, size: requestedSize })
    if (!currentThreadsContext(context, requestSeq)) return
    threads.value = data
  } catch (e: unknown) {
    if (currentThreadsContext(context, requestSeq)) {
      threadsError.value = e instanceof Error ? e.message : '加载帖子失败'
    }
  } finally {
    if (currentThreadsContext(context, requestSeq)) threadsLoading.value = false
  }
}

async function loadProfile(options: { preserveView?: boolean } = {}): Promise<void> {
  const requestSeq = ++profileRequestSeq
  const viewer = captureViewerContext()
  const context: ProfileRequestContext = {
    ...viewer,
    userId: userId.value,
    epoch: viewerEpoch,
    relationRevision,
  }
  const preserveView = options.preserveView ?? profileLoaded.value
  const requestedUserId = context.userId
  profileReadPending.value = true
  if (!preserveView || !profileLoaded.value) loading.value = true
  error.value = ''
  if (!validViewerPair(context)) {
    setStorageDriftError('profile', context, requestSeq)
    return
  }
  try {
    const data = await getUserProfile(requestedUserId)
    if (!currentProfileContext(context, requestSeq)) {
      setStorageDriftError('profile', context, requestSeq)
      return
    }
    const previous = profile.value
    const next = data && typeof data === 'object' ? { ...data } : createEmptyProfile()
    const relationIsStale = context.relationRevision !== relationRevision
    const relation = readRelationship(data)
    if (relationIsStale) {
      next.followedByMe = previous.followedByMe
      next.followingMe = previous.followingMe
      next.followerCount = previous.followerCount
      next.followingCount = previous.followingCount
    } else if (relation === null) {
      next.followedByMe = undefined
      next.followingMe = undefined
    } else {
      next.followedByMe = relation
    }
    profile.value = next
    profileLoaded.value = true
    if (!relationIsStale) {
      followStateError.value = context.loggedIn && relation === null
        ? '关注状态加载失败，请重试'
        : ''
    }
  } catch (e: unknown) {
    if (currentProfileContext(context, requestSeq)) {
      error.value = errorMessage(e, '获取用户资料失败')
    }
  } finally {
    if (currentProfileContext(context, requestSeq)) {
      loading.value = false
      profileReadPending.value = false
    }
    else setStorageDriftError('profile', context, requestSeq)
  }
}

async function toggleFollow(): Promise<void> {
  if (disposed || followSaving.value || isMe.value) return
  if (!isLoggedIn.value) {
    followError.value = '请先登录后再关注'
    return
  }
  const requestedUserId = userId.value
  const relationship = followedByMe.value
  if (relationship === null) {
    followStateError.value = followStateError.value || '关注状态加载失败，请重试'
    return
  }
  const viewer = captureViewerContext()
  const context: FollowRequestContext = { ...viewer, userId: requestedUserId, epoch: viewerEpoch }
  if (!validViewerPair(context)) {
    setStorageDriftError('follow', context)
    return
  }
  const requestSeq = ++followRequestSeq
  relationRevision += 1
  const mutationRevision = relationRevision
  followSaving.value = true
  followError.value = ''
  followStateError.value = ''
  try {
    const state = relationship
      ? await unfollowUser(requestedUserId)
      : await followUser(requestedUserId)
    if (!currentFollowContext(context, requestSeq)) {
      setStorageDriftError('follow', context, requestSeq)
      return
    }
    const normalized = normalizeFollowState(state, requestedUserId)
    if (!normalized) {
      if (mutationRevision === relationRevision) {
        setProfileRelationUnknown()
        followStateError.value = '关注结果暂未确认，请重试状态'
      }
      return
    }
    profile.value = {
      ...profile.value,
      followedByMe: normalized.following,
      followingMe: normalized.followingMe,
      followerCount: normalized.followerCount,
      followingCount: normalized.followingCount,
    }
    followStateError.value = ''
    try {
      window.dispatchEvent(new CustomEvent('follow-state-updated', {
        detail: {
          userId: requestedUserId,
          following: normalized.following,
        },
      }))
    } catch (_) {}
  } catch (e: unknown) {
    if (currentFollowContext(context, requestSeq)) {
      if (e instanceof InteractionResponseError) {
        if (mutationRevision === relationRevision) {
          setProfileRelationUnknown()
          followError.value = ''
          followStateError.value = errorMessage(e, '关注状态暂不可用，请重试')
        }
      } else {
        followError.value = errorMessage(e, '关注操作失败')
      }
    } else {
      setStorageDriftError('follow', context, requestSeq)
    }
  } finally {
    if (currentFollowContext(context, requestSeq)) followSaving.value = false
    else setStorageDriftError('follow', context, requestSeq)
  }
}

function queueContextRefresh(options: { resetThreads?: boolean; preserveProfile?: boolean; relationChanged?: boolean } = {}): void {
  if (disposed) return
  const resetThreads = Boolean(options.resetThreads)
  const preserveProfile = options.preserveProfile !== false
  const relationChanged = Boolean(options.relationChanged)
  profileRequestSeq += 1
  followRequestSeq += 1
  refreshPreserveProfile = refreshPreserveProfile && preserveProfile

  if (!preserveProfile) {
    profileLoaded.value = false
    profile.value = createEmptyProfile()
    loading.value = true
  } else if (profileLoaded.value) {
    loading.value = false
  }
  error.value = ''
  followSaving.value = false
  followError.value = ''
  if (relationChanged) {
    relationRevision += 1
    setProfileRelationUnknown()
    followStateError.value = ''
  }

  if (resetThreads) {
    threadsRequestSeq += 1
    threads.value = createEmptyThreads()
    threadsLoading.value = true
    threadsError.value = ''
    page.value = 1
    inputPage.value = '1'
    refreshThreadsQueued = true
  }
  if (refreshQueued) return
  refreshQueued = true
  queueMicrotask(() => {
    if (disposed) return
    refreshQueued = false
    const reloadThreads = refreshThreadsQueued
    const preserveCurrentProfile = refreshPreserveProfile
    refreshThreadsQueued = false
    refreshPreserveProfile = true
    void loadProfile({ preserveView: preserveCurrentProfile })
    if (reloadThreads) void loadThreads()
  })
}

function retryProfile(): void {
  if (disposed || profileReadPending.value) return
  void loadProfile({ preserveView: profileLoaded.value })
}

function onAuthContextChanged(): void {
  if (disposed) return
  observeViewerContext(true, true)
}

function onStorageChanged(event: StorageEvent): void {
  if (disposed || (event.storageArea !== null && event.storageArea !== localStorage)) return
  if (event.key !== null && event.key !== 'accessToken') return
  observeViewerContext(true)
}

const stopViewerWatch = watch(
  [() => String(token.value || ''), () => Boolean(isLoggedIn.value), knownOwnerId],
  () => { observeViewerContext(true) },
  { flush: 'sync' },
)

onMounted(() => {
  window.addEventListener('auth-state-changed', onAuthContextChanged)
  window.addEventListener('storage', onStorageChanged)
  queueContextRefresh({ resetThreads: true, preserveProfile: false })
})

// 当路由 id 变化时，刷新个人资料并重置分页后重新加载帖子
const stopRouteWatch = watch(userId, () => {
  queueContextRefresh({ resetThreads: true, preserveProfile: false, relationChanged: true })
}, { flush: 'sync' })

onUnmounted(() => {
  disposed = true
  viewerEpoch += 1
  refreshQueued = false
  refreshThreadsQueued = false
  stopViewerWatch()
  stopRouteWatch()
  window.removeEventListener('auth-state-changed', onAuthContextChanged)
  window.removeEventListener('storage', onStorageChanged)
  profileRequestSeq += 1
  threadsRequestSeq += 1
  followRequestSeq += 1
})
</script>

<style scoped>
</style>

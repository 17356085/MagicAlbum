<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { bookmarkThread, getThreadBookmarkState, unbookmarkThread } from '@/api/bookmarks'
import { getThreadLikeState, likeThread, unlikeThread } from '@/api/threads'
import ReportDialog from '@/components/ReportDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Id, Thread } from '@/types'

const props = defineProps<{
  thread: Thread
}>()

const emit = defineEmits<{
  updated: [patch: Partial<Thread>]
}>()

type StateStatus = 'idle' | 'loading' | 'ready' | 'error'
type ActionKind = 'like' | 'bookmark' | 'report'

interface SessionSnapshot {
  threadId: string
  token: string
  storedToken: string
  ownerKey: string | null
  loggedIn: boolean
}

interface OperationContext extends SessionSnapshot {
  epoch: number
  threadValue: Id
}

interface PendingRead {
  sequence: number
  key: string
  promise: Promise<void>
}

const authStore = useAuthStore()
const { isLoggedIn, token, user } = storeToRefs(authStore)

const likePending = ref(false)
const bookmarkPending = ref(false)
const bookmarkLoading = ref(false)
const message = ref('')
const bookmarkMessage = ref('')
const reportDialogOpen = ref(false)
const reportMessage = ref('')
const reportTargetThreadId = ref<Id | null>(null)

const likeState = ref<boolean | null>(typeof props.thread.liked === 'boolean' ? props.thread.liked : null)
const likeCountState = ref<number | null>(null)
const likeStatus = ref<StateStatus>('idle')
const likeConfirmed = ref(false)
const propLikeUsable = ref(true)
const bookmarkState = ref<boolean | null>(null)
const bookmarkStatus = ref<StateStatus>('idle')

let disposed = false
let mounted = false
let contextEpoch = 0
let likeRequestSequence = 0
let bookmarkRequestSequence = 0
let likeReadRevision = 0
let bookmarkReadRevision = 0
let pendingLikeRead: PendingRead | null = null
let pendingBookmarkRead: PendingRead | null = null
let readQueued = false
let observedSnapshot: SessionSnapshot | null = null
let reactiveAuthInvalidated = false
let hydrationObserved = false

function idKey(value: unknown): string {
  return String(value ?? '').trim()
}

function currentToken(): string {
  return String(token.value || '').trim()
}

function storedToken(): string {
  return String(getStoredAccessToken() || '').trim()
}

function currentOwnerKey(): string | null {
  const owner = user.value?.id ?? user.value?.userId ?? user.value?.username
  const key = String(owner ?? '').trim()
  return key || null
}

function currentSnapshot(): SessionSnapshot {
  return {
    threadId: idKey(props.thread.id),
    token: currentToken(),
    storedToken: storedToken(),
    ownerKey: currentOwnerKey(),
    loggedIn: Boolean(isLoggedIn.value),
  }
}

function isValidSession(snapshot: SessionSnapshot): boolean {
  return Boolean(snapshot.threadId)
    && snapshot.loggedIn
    && hasRealToken(snapshot.token)
    && snapshot.token === snapshot.storedToken
}

function sameTokenHydration(previous: SessionSnapshot, next: SessionSnapshot): boolean {
  return previous.threadId === next.threadId
    && previous.token === next.token
    && previous.storedToken === next.storedToken
    && previous.loggedIn
    && next.loggedIn
    && previous.ownerKey === null
    && next.ownerKey !== null
}

function sameOwner(expected: string | null, actual: string | null): boolean {
  // A token-only session may be hydrated with its confirmed owner later.
  return expected === null || (actual !== null && expected === actual)
}

function sameAuthContext(previous: SessionSnapshot, next: SessionSnapshot): boolean {
  return previous.token === next.token
    && previous.storedToken === next.storedToken
    && previous.ownerKey === next.ownerKey
    && previous.loggedIn === next.loggedIn
}

function contextKey(context: OperationContext): string {
  return [context.threadId, context.token, context.storedToken, context.epoch].join('|')
}

function normalizeCount(value: unknown): number | null {
  const count = Number(value)
  return Number.isFinite(count) && count >= 0 ? count : null
}

function parseLikeState(value: unknown): { liked: boolean; likeCount: number | null } {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_LIKE_STATE')
  const state = value as { liked?: unknown; likeCount?: unknown }
  if (typeof state.liked !== 'boolean') throw new Error('INVALID_LIKE_STATE')
  return { liked: state.liked, likeCount: normalizeCount(state.likeCount) }
}

function parseBookmarkState(value: unknown): boolean {
  if (typeof value !== 'object' || value === null) throw new Error('INVALID_BOOKMARK_STATE')
  const state = value as { bookmarked?: unknown }
  if (typeof state.bookmarked !== 'boolean') throw new Error('INVALID_BOOKMARK_STATE')
  return state.bookmarked
}

function setMessage(kind: ActionKind, value: string): void {
  if (kind === 'like') message.value = value
  else if (kind === 'bookmark') bookmarkMessage.value = value
  else reportMessage.value = value
}

function clearPersonalizedState(): void {
  likeState.value = null
  likeCountState.value = null
  likeStatus.value = 'idle'
  likeConfirmed.value = false
  propLikeUsable.value = false
  likePending.value = false
  message.value = ''

  bookmarkState.value = null
  bookmarkStatus.value = 'idle'
  bookmarkPending.value = false
  bookmarkLoading.value = false
  bookmarkMessage.value = ''

  reportDialogOpen.value = false
  reportTargetThreadId.value = null
  reportMessage.value = ''
}

function invalidateContext(): void {
  contextEpoch += 1
  likeRequestSequence += 1
  bookmarkRequestSequence += 1
  likeReadRevision += 1
  bookmarkReadRevision += 1
  pendingLikeRead = null
  pendingBookmarkRead = null
  clearPersonalizedState()
}

function queueCurrentReads(): void {
  if (disposed || !mounted || readQueued) return
  readQueued = true
  queueMicrotask(() => {
    readQueued = false
    if (disposed || !mounted) return
    void refreshLikeState()
    void refreshBookmarkState()
  })
}

function reconcileSnapshot(next: SessionSnapshot): { changed: boolean; authChanged: boolean; hydration: boolean } {
  const previous = observedSnapshot
  if (!previous) {
    observedSnapshot = next
    return { changed: false, authChanged: false, hydration: false }
  }

  const hydration = sameTokenHydration(previous, next)
  const changed = previous.threadId !== next.threadId || !sameAuthContext(previous, next)
  const authChanged = !sameTokenHydration(previous, next)
    && !sameAuthContext(previous, next)
  observedSnapshot = next

  if (hydration) {
    hydrationObserved = true
    queueMicrotask(() => { hydrationObserved = false })
    return { changed: false, authChanged: false, hydration: true }
  }
  if (changed) {
    invalidateContext()
    queueCurrentReads()
  }
  return { changed, authChanged, hydration: false }
}

function captureContext(): OperationContext | null {
  const snapshot = currentSnapshot()
  if (!isValidSession(snapshot)) return null
  return { ...snapshot, epoch: contextEpoch, threadValue: props.thread.id }
}

function isCurrentContext(context: OperationContext): boolean {
  if (disposed || context.epoch !== contextEpoch) return false
  const current = currentSnapshot()
  return current.threadId === context.threadId
    && current.token === context.token
    && current.storedToken === context.storedToken
    && current.loggedIn
    && sameOwner(context.ownerKey, current.ownerKey)
}

function isCurrentLikeRead(context: OperationContext, sequence: number, revision: number): boolean {
  return isCurrentContext(context) && likeRequestSequence === sequence && likeReadRevision === revision
}

function isCurrentBookmarkRead(context: OperationContext, sequence: number, revision: number): boolean {
  return isCurrentContext(context) && bookmarkRequestSequence === sequence && bookmarkReadRevision === revision
}

function isCurrentLikeWrite(context: OperationContext, sequence: number): boolean {
  return isCurrentContext(context) && likeRequestSequence === sequence
}

function isCurrentBookmarkWrite(context: OperationContext, sequence: number): boolean {
  return isCurrentContext(context) && bookmarkRequestSequence === sequence
}

function setSessionChangedMessage(kind: ActionKind, snapshot = currentSnapshot()): void {
  if (hasRealToken(snapshot.token) || hasRealToken(snapshot.storedToken)) {
    setMessage(kind, '登录状态已变化，请重新登录')
  }
}

function handleStaleOperation(context: OperationContext, kind: ActionKind): void {
  if (disposed || context.epoch !== contextEpoch) return
  const next = currentSnapshot()
  const changed = next.threadId !== context.threadId
    || next.token !== context.token
    || next.storedToken !== context.storedToken
    || next.loggedIn !== context.loggedIn
    || !sameOwner(context.ownerKey, next.ownerKey)
  if (!changed) return
  const result = reconcileSnapshot(next)
  if (result.changed && !isValidSession(next)) setSessionChangedMessage(kind, next)
}

function prepareActionContext(kind: ActionKind): OperationContext | null {
  const next = currentSnapshot()
  const result = reconcileSnapshot(next)
  if (!isValidSession(next)) {
    if (hasRealToken(next.token) || hasRealToken(next.storedToken)) setSessionChangedMessage(kind, next)
    else if (kind === 'like') message.value = '登录后可以点赞'
    else if (kind === 'bookmark') bookmarkMessage.value = '登录后可以收藏'
    else reportMessage.value = '登录后可以举报'
    return null
  }
  if (result.changed) return null
  return { ...next, epoch: contextEpoch, threadValue: props.thread.id }
}

async function refreshLikeState(force = false): Promise<void> {
  const context = captureContext()
  if (!context) {
    likeStatus.value = 'idle'
    likeState.value = null
    likePending.value = false
    return
  }
  if (!force && likeStatus.value === 'ready' && likeState.value !== null) return
  const key = contextKey(context)
  if (pendingLikeRead?.key === key) return pendingLikeRead.promise

  const sequence = ++likeRequestSequence
  const revision = likeReadRevision
  likeStatus.value = 'loading'
  message.value = ''
  const operation = (async () => {
    try {
      const state = await getThreadLikeState(context.threadValue)
      if (!isCurrentLikeRead(context, sequence, revision)) {
        handleStaleOperation(context, 'like')
        return
      }
      const { liked: nextLiked, likeCount: nextCount } = parseLikeState(state)
      likeState.value = nextLiked
      likeCountState.value = nextCount
      likeStatus.value = 'ready'
      likeConfirmed.value = true
      propLikeUsable.value = true
      if (props.thread.liked !== nextLiked || (nextCount !== null && normalizeCount(props.thread.likeCount) !== nextCount)) {
        const patch: Partial<Thread> = { liked: nextLiked }
        if (nextCount !== null) patch.likeCount = nextCount
        if (isCurrentLikeRead(context, sequence, revision)) emit('updated', patch)
      }
    } catch (_) {
      if (isCurrentLikeRead(context, sequence, revision)) {
        likeStatus.value = 'error'
        likeState.value = null
        likeConfirmed.value = false
        message.value = '点赞状态加载失败，请稍后重试'
      } else {
        handleStaleOperation(context, 'like')
      }
    } finally {
      if (pendingLikeRead?.sequence === sequence) pendingLikeRead = null
      if (isCurrentLikeRead(context, sequence, revision)) {
        if (likeStatus.value === 'loading') likeStatus.value = 'idle'
      } else {
        handleStaleOperation(context, 'like')
      }
    }
  })()
  pendingLikeRead = { sequence, key, promise: operation }
  return operation
}

async function refreshBookmarkState(force = false): Promise<void> {
  const context = captureContext()
  if (!context) {
    bookmarkStatus.value = 'idle'
    bookmarkState.value = null
    bookmarkPending.value = false
    bookmarkLoading.value = false
    return
  }
  if (!force && bookmarkStatus.value === 'ready' && bookmarkState.value !== null) return
  const key = contextKey(context)
  if (pendingBookmarkRead?.key === key) return pendingBookmarkRead.promise

  const sequence = ++bookmarkRequestSequence
  const revision = bookmarkReadRevision
  bookmarkStatus.value = 'loading'
  bookmarkLoading.value = true
  bookmarkMessage.value = ''
  const operation = (async () => {
    try {
      const state = await getThreadBookmarkState(context.threadValue)
      if (!isCurrentBookmarkRead(context, sequence, revision)) {
        handleStaleOperation(context, 'bookmark')
        return
      }
      bookmarkState.value = parseBookmarkState(state)
      bookmarkStatus.value = 'ready'
    } catch (_) {
      if (isCurrentBookmarkRead(context, sequence, revision)) {
        bookmarkStatus.value = 'error'
        bookmarkState.value = null
        bookmarkMessage.value = '收藏状态加载失败，请稍后重试'
      } else {
        handleStaleOperation(context, 'bookmark')
      }
    } finally {
      if (pendingBookmarkRead?.sequence === sequence) pendingBookmarkRead = null
      if (isCurrentBookmarkRead(context, sequence, revision)) {
        bookmarkLoading.value = false
        if (bookmarkStatus.value === 'loading') bookmarkStatus.value = 'idle'
      } else {
        handleStaleOperation(context, 'bookmark')
      }
    }
  })()
  pendingBookmarkRead = { sequence, key, promise: operation }
  return operation
}

async function toggleLike(): Promise<void> {
  if (likePending.value) return
  const context = prepareActionContext('like')
  if (!context) return
  if (likeStatus.value !== 'ready' || likeState.value === null) {
    message.value = likeStatus.value === 'error'
      ? '点赞状态加载失败，请点击重试'
      : '点赞状态正在加载，请稍后再试'
    return
  }

  const wasLiked = likeState.value
  const sequence = ++likeRequestSequence
  likeReadRevision += 1
  likePending.value = true
  message.value = ''
  try {
    const state = wasLiked
      ? await unlikeThread(context.threadValue)
      : await likeThread(context.threadValue)
    if (!isCurrentLikeWrite(context, sequence)) {
      handleStaleOperation(context, 'like')
      return
    }
    const { liked: nextLiked, likeCount: nextCount } = parseLikeState(state)
    likeState.value = nextLiked
    likeCountState.value = nextCount
    likeStatus.value = 'ready'
    likeConfirmed.value = true
    propLikeUsable.value = true
    message.value = ''
    const patch: Partial<Thread> = { liked: nextLiked }
    if (nextCount !== null) patch.likeCount = nextCount
    if (isCurrentLikeWrite(context, sequence)) emit('updated', patch)
  } catch (_) {
    if (isCurrentLikeWrite(context, sequence)) {
      likeStatus.value = 'error'
      likeState.value = null
      likeConfirmed.value = false
      message.value = '点赞操作失败，请稍后重试'
    } else {
      handleStaleOperation(context, 'like')
    }
  } finally {
    if (isCurrentLikeWrite(context, sequence)) likePending.value = false
    else handleStaleOperation(context, 'like')
  }
}

async function toggleBookmark(): Promise<void> {
  if (bookmarkPending.value || bookmarkLoading.value) return
  const context = prepareActionContext('bookmark')
  if (!context) return
  if (bookmarkStatus.value !== 'ready' || bookmarkState.value === null) {
    bookmarkMessage.value = bookmarkStatus.value === 'error'
      ? '收藏状态加载失败，请点击重试'
      : '收藏状态正在加载，请稍后再试'
    return
  }

  const wasBookmarked = bookmarkState.value
  const sequence = ++bookmarkRequestSequence
  bookmarkReadRevision += 1
  bookmarkPending.value = true
  bookmarkMessage.value = ''
  try {
    const state = wasBookmarked
      ? await unbookmarkThread(context.threadValue)
      : await bookmarkThread(context.threadValue)
    if (!isCurrentBookmarkWrite(context, sequence)) {
      handleStaleOperation(context, 'bookmark')
      return
    }
    bookmarkState.value = parseBookmarkState(state)
    bookmarkStatus.value = 'ready'
    bookmarkMessage.value = ''
  } catch (_) {
    if (isCurrentBookmarkWrite(context, sequence)) {
      bookmarkStatus.value = 'error'
      bookmarkState.value = null
      bookmarkMessage.value = '收藏操作失败，请稍后重试'
    } else {
      handleStaleOperation(context, 'bookmark')
    }
  } finally {
    if (isCurrentBookmarkWrite(context, sequence)) bookmarkPending.value = false
    else handleStaleOperation(context, 'bookmark')
  }
}

function retryLikeState(): void {
  const context = prepareActionContext('like')
  if (!context) return
  void refreshLikeState(true)
}

function retryBookmarkState(): void {
  const context = prepareActionContext('bookmark')
  if (!context) return
  void refreshBookmarkState(true)
}

function openReportDialog(): void {
  const context = prepareActionContext('report')
  if (!context) return
  reportMessage.value = ''
  reportTargetThreadId.value = props.thread.id
  reportDialogOpen.value = true
}

function onAuthStateChanged(event: Event): void {
  if (disposed) return
  const loggedInEvent = (event as CustomEvent<{ loggedIn?: boolean }>).detail?.loggedIn
  const next = currentSnapshot()
  if (reactiveAuthInvalidated) {
    reactiveAuthInvalidated = false
    observedSnapshot = next
    return
  }
  if (hydrationObserved) {
    hydrationObserved = false
    observedSnapshot = next
    return
  }
  const previous = observedSnapshot
  if (previous && sameTokenHydration(previous, next) && loggedInEvent !== false) {
    observedSnapshot = next
    return
  }
  const result = reconcileSnapshot(next)
  if (!result.changed) {
    invalidateContext()
    if (loggedInEvent !== false) queueCurrentReads()
  }
  if (!isValidSession(next)) setSessionChangedMessage('like', next)
}

function onStorageChanged(event: StorageEvent): void {
  if (disposed || (event.storageArea !== null && event.storageArea !== localStorage)) return
  if (event.key !== null && event.key !== 'accessToken') return
  const next = currentSnapshot()
  const result = reconcileSnapshot(next)
  if (result.changed && !isValidSession(next)) setSessionChangedMessage('like', next)
}

watch(
  [() => idKey(props.thread.id), token, isLoggedIn, () => currentOwnerKey()],
  () => {
    const next = currentSnapshot()
    if (!mounted) {
      observedSnapshot = next
      return
    }
    const result = reconcileSnapshot(next)
    if (result.changed && result.authChanged) {
      reactiveAuthInvalidated = true
      queueMicrotask(() => { reactiveAuthInvalidated = false })
    }
  },
  { flush: 'sync' },
)

watch(
  [() => props.thread.liked, () => props.thread.likeCount],
  ([nextLiked, nextCount]) => {
    if (likeConfirmed.value || !propLikeUsable.value || likeStatus.value === 'error') return
    if (typeof nextLiked === 'boolean') likeState.value = nextLiked
    const count = normalizeCount(nextCount)
    if (count !== null) likeCountState.value = count
  },
)

const isPrivateSession = computed(() => {
  const current = currentSnapshot()
  return isValidSession(current)
})
const liked = computed(() => {
  if (!isPrivateSession.value) return false
  if (likeState.value !== null) return likeState.value
  return propLikeUsable.value && typeof props.thread.liked === 'boolean' ? props.thread.liked : false
})
const likeCount = computed(() => likeCountState.value ?? normalizeCount(props.thread.likeCount) ?? 0)
const bookmarked = computed(() => isPrivateSession.value && bookmarkState.value === true)
const likeButtonDisabled = computed(() => isPrivateSession.value
  && (likePending.value || likeStatus.value !== 'ready' || likeState.value === null))
const bookmarkButtonDisabled = computed(() => isPrivateSession.value
  && (bookmarkLoading.value || bookmarkPending.value || bookmarkStatus.value !== 'ready' || bookmarkState.value === null))

onMounted(() => {
  mounted = true
  observedSnapshot = currentSnapshot()
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  window.addEventListener('storage', onStorageChanged)
  queueCurrentReads()
})

onBeforeUnmount(() => {
  disposed = true
  mounted = false
  contextEpoch += 1
  likeRequestSequence += 1
  bookmarkRequestSequence += 1
  likeReadRevision += 1
  bookmarkReadRevision += 1
  pendingLikeRead = null
  pendingBookmarkRead = null
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  window.removeEventListener('storage', onStorageChanged)
})
</script>

<template>
  <section class="px-5 py-5 sm:px-8" aria-label="帖子互动">
    <div class="flex flex-wrap items-center gap-3">
      <button
        type="button"
        :aria-label="liked ? '取消点赞' : '点赞'"
        :aria-pressed="liked"
        :aria-busy="likeStatus === 'loading' || likePending"
        :disabled="likeButtonDisabled"
        class="ui-button min-h-10 px-4 py-2 disabled:opacity-60"
        :class="liked
          ? 'border-rose-200 bg-rose-50 text-rose-600 hover:bg-rose-100 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300'
          : 'hover:border-rose-200 hover:text-rose-600 dark:hover:border-rose-800 dark:hover:text-rose-300'"
        @click="toggleLike"
      >
        <span class="text-base leading-none">{{ liked ? '♥' : '♡' }}</span>
        <span>{{ liked ? '已点赞' : '点赞' }}</span>
        <span class="text-xs opacity-75">{{ likeCount }}</span>
      </button>
      <button
        type="button"
        :aria-label="bookmarked ? '取消收藏' : '收藏'"
        :aria-pressed="bookmarked"
        :aria-busy="bookmarkLoading || bookmarkPending"
        :disabled="bookmarkButtonDisabled"
        class="ui-button min-h-10 px-4 py-2 disabled:opacity-60"
        :class="bookmarked
          ? 'border-amber-200 bg-amber-50 text-amber-600 hover:bg-amber-100 dark:border-amber-900/60 dark:bg-amber-950/40 dark:text-amber-300'
          : 'hover:border-amber-200 hover:text-amber-600 dark:hover:border-amber-800 dark:hover:text-amber-300'"
        @click="toggleBookmark"
      >
        <span class="text-base leading-none">{{ bookmarked ? '★' : '☆' }}</span>
        <span>{{ bookmarked ? '已收藏' : '收藏' }}</span>
      </button>
      <button
        type="button"
        aria-label="举报"
        class="ui-button min-h-10 px-4 py-2 hover:border-red-200 hover:text-red-600 dark:hover:border-red-800 dark:hover:text-red-300"
        @click="openReportDialog"
      >
        <span class="text-base leading-none">⚑</span>
        <span>举报</span>
      </button>
      <span v-if="message" class="ui-muted text-sm" role="status">{{ message }}</span>
      <button v-if="likeStatus === 'error'" type="button" data-testid="like-state-retry" class="text-sm text-[var(--ui-accent)] underline" @click="retryLikeState">重试点赞状态</button>
      <span v-if="bookmarkMessage" class="ui-muted text-sm" role="status">{{ bookmarkMessage }}</span>
      <button v-if="bookmarkStatus === 'error'" type="button" data-testid="bookmark-state-retry" class="text-sm text-[var(--ui-accent)] underline" @click="retryBookmarkState">重试收藏状态</button>
      <span v-if="reportMessage" class="text-sm text-amber-700 dark:text-amber-300" role="alert">{{ reportMessage }}</span>
    </div>
    <ReportDialog
      v-if="reportDialogOpen"
      :model-value="reportDialogOpen"
      target-type="THREAD"
      :target-id="Number(reportTargetThreadId)"
      :is-logged-in="isPrivateSession"
      @update:model-value="reportDialogOpen = $event"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createBaseMarkdownRenderer, renderInlineMarkdown } from '@/utils/markdownBase'
import { formatRelativeTime } from '@/composables/time'
import { followUser, getUserProfile, unfollowUser } from '@/api/users'
import { InteractionResponseError } from '@/api/interactionState'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Thread } from '@/types'

const props = defineProps<{
  displayAuthorAvatarUrl?: string
  thread: Thread
}>()

defineEmits<{
  back: []
}>()

const titleRenderer = createBaseMarkdownRenderer({ html: false, breaks: false })
const authStore = useAuthStore()
const { isLoggedIn, token, user } = storeToRefs(authStore)
const followedByMe = ref<boolean | null>(null)
const followLoading = ref(false)
const followStateLoading = ref(false)
const followError = ref('')
const followLoadError = ref('')

const authorId = computed(() => props.thread.authorId ?? null)
const isMe = computed(() => String(user.value?.id || user.value?.userId || '') === String(authorId.value || ''))
const showFollowButton = computed(() => Boolean(authorId.value) && !isMe.value)
const followButtonDisabled = computed(() => followLoading.value || followStateLoading.value || (
  Boolean(isLoggedIn.value) && (!knownUserIdentity() || followedByMe.value === null || Boolean(followLoadError.value))
))

interface SessionContext {
  token: string
  storedToken: string
  loggedIn: boolean
  knownUser: string
}

interface FollowSnapshot extends SessionContext {
  authorId: string
  epoch: number
}

interface AcceptedFollowState {
  authorId: string
  token: string
  storedToken: string
  epoch: number
  following: boolean
}

interface PendingRead {
  authorId: string
  token: string
  storedToken: string
  loggedIn: boolean
  knownUser: string
  epoch: number
  operation: number
  mutationVersion: number
  promise: Promise<void>
}

let disposed = false
let sessionEpoch = 0
let readOperation = 0
let mutationOperation = 0
let mutationVersion = 0
let pendingRead: PendingRead | null = null
let acceptedState: AcceptedFollowState | null = null
let sessionLoadQueued = false

function knownUserIdentity(): string {
  const currentUser = user.value
  const id = currentUser?.id ?? currentUser?.userId
  if (id !== undefined && id !== null && String(id).trim()) return `id:${String(id)}`
  const username = String(currentUser?.username || '').trim().toLowerCase()
  return username ? `username:${username}` : ''
}

function sessionContext(): SessionContext {
  const currentToken = String(token.value || '')
  return {
    token: currentToken,
    storedToken: getStoredAccessToken(),
    loggedIn: Boolean(isLoggedIn.value) && hasRealToken(currentToken),
    knownUser: knownUserIdentity(),
  }
}

function sameSessionContext(left: SessionContext, right: SessionContext): boolean {
  return left.token === right.token && left.storedToken === right.storedToken &&
    left.loggedIn === right.loggedIn && left.knownUser === right.knownUser
}

function isHydration(previous: SessionContext, next: SessionContext): boolean {
  return previous.loggedIn && next.loggedIn && previous.token === next.token &&
    previous.storedToken === next.storedToken && !previous.knownUser && Boolean(next.knownUser)
}

function captureSnapshot(requestedAuthorId: unknown): FollowSnapshot {
  const current = sessionContext()
  return {
    ...current,
    authorId: String(requestedAuthorId ?? ''),
    epoch: sessionEpoch,
  }
}

function sameAuthor(requestedAuthorId: string): boolean {
  return !disposed && String(authorId.value ?? '') === requestedAuthorId
}

function storageStillMatches(snapshot: FollowSnapshot): boolean {
  return String(token.value || '') === snapshot.token && getStoredAccessToken() === snapshot.storedToken
}

function snapshotStillCurrent(snapshot: FollowSnapshot, allowHydration = true): boolean {
  if (!sameAuthor(snapshot.authorId) || snapshot.epoch !== sessionEpoch || !storageStillMatches(snapshot)) return false
  const currentUser = knownUserIdentity()
  if (currentUser === snapshot.knownUser) return true
  return allowHydration && !snapshot.knownUser && Boolean(currentUser) &&
    snapshot.loggedIn && Boolean(isLoggedIn.value) && hasRealToken(snapshot.token)
}

function acceptedStateMatches(snapshot: FollowSnapshot): boolean {
  return Boolean(acceptedState && acceptedState.authorId === snapshot.authorId &&
    acceptedState.token === snapshot.token && acceptedState.storedToken === snapshot.storedToken &&
    acceptedState.epoch === snapshot.epoch)
}

function errorMessage(error: unknown, fallback: string): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { data?: { message?: unknown } } }).response
    if (typeof response?.data?.message === 'string' && response.data.message.trim()) return response.data.message
  }
  return error instanceof Error && error.message.trim() ? error.message : fallback
}

function readFollowValue(profile: unknown): boolean | null {
  if (typeof profile !== 'object' || profile === null) return null
  const record = profile as { followedByMe?: unknown; following?: unknown }
  const value = record.followedByMe ?? record.following
  return typeof value === 'boolean' ? value : null
}

function setStorageDriftError(kind: 'read' | 'write', snapshot: FollowSnapshot, operation?: number): void {
  if (disposed || !sameAuthor(snapshot.authorId) || String(token.value || '') !== snapshot.token || snapshot.epoch !== sessionEpoch) return
  if (operation !== undefined && (kind === 'read' ? operation !== readOperation : operation !== mutationOperation)) return
  const message = '登录状态已变化，请重试'
  if (kind === 'read') followLoadError.value = message
  else followError.value = message
}

function clearPrivateState({ invalidateRead = true, preserveHydratedRead = false } = {}): void {
  if (invalidateRead) {
    readOperation += 1
  }
  if (!preserveHydratedRead) mutationVersion += 1
  mutationOperation += 1
  followedByMe.value = null
  followStateLoading.value = false
  followLoading.value = false
  followError.value = ''
  followLoadError.value = ''
}

function applyAcceptedState(snapshot: FollowSnapshot, following: boolean): void {
  followedByMe.value = following
  followLoadError.value = ''
  acceptedState = {
    authorId: snapshot.authorId,
    token: snapshot.token,
    storedToken: snapshot.storedToken,
    epoch: snapshot.epoch,
    following,
  }
}

function markFollowResultUnknown(): void {
  followedByMe.value = null
  acceptedState = null
  followLoadError.value = '关注结果暂未确认，请重试状态'
}

function currentReadMatches(pending: PendingRead): boolean {
  const snapshot: FollowSnapshot = pending
  return !disposed && pendingRead?.operation === pending.operation &&
    pending.operation === readOperation && snapshotStillCurrent(snapshot) &&
    mutationVersion === pending.mutationVersion
}

function currentMutationMatches(snapshot: FollowSnapshot, operation: number, allowHydration = true): boolean {
  return !disposed && operation === mutationOperation && snapshotStillCurrent(snapshot, allowHydration)
}

async function loadFollowState(force = false): Promise<void> {
  if (disposed || !showFollowButton.value || !authorId.value) return
  if (!isLoggedIn.value) {
    followedByMe.value = null
    followStateLoading.value = false
    followLoadError.value = ''
    return
  }

  const requestedAuthorId = authorId.value
  const snapshot = captureSnapshot(requestedAuthorId)
  if (!snapshot.token || !snapshot.storedToken || snapshot.token !== snapshot.storedToken) {
    followStateLoading.value = false
    setStorageDriftError('read', snapshot)
    return
  }

  if (!force && acceptedStateMatches(snapshot) && acceptedState) {
    followedByMe.value = acceptedState.following
    followLoadError.value = ''
    return
  }

  if (pendingRead && pendingRead.operation === readOperation &&
    pendingRead.mutationVersion === mutationVersion && pendingRead.epoch === snapshot.epoch &&
    pendingRead.authorId === snapshot.authorId &&
    pendingRead.token === snapshot.token && pendingRead.storedToken === snapshot.storedToken) {
    return pendingRead.promise
  }

  const operation = ++readOperation
  const mutationAtStart = mutationVersion
  followStateLoading.value = true
  followLoadError.value = ''
  const pending: PendingRead = {
    ...snapshot,
    operation,
    mutationVersion: mutationAtStart,
    promise: Promise.resolve(),
  }
  pending.promise = (async () => {
    try {
      const profile = await getUserProfile(requestedAuthorId)
      if (!currentReadMatches(pending)) {
        if (!storageStillMatches(snapshot) && mutationVersion === pending.mutationVersion) {
          setStorageDriftError('read', snapshot, operation)
        }
        return
      }
      const following = readFollowValue(profile)
      if (following === null) {
        followLoadError.value = '关注状态加载失败，请重试'
        return
      }
      applyAcceptedState(snapshot, following)
    } catch (error: unknown) {
      if (!currentReadMatches(pending)) {
        if (!storageStillMatches(snapshot) && mutationVersion === pending.mutationVersion) {
          setStorageDriftError('read', snapshot, operation)
        }
        return
      }
      followLoadError.value = errorMessage(error, '关注状态加载失败，请重试')
    } finally {
      if (pendingRead?.operation === operation) {
        const ownsCurrentState = !disposed && operation === readOperation && sameAuthor(snapshot.authorId)
        pendingRead = null
        if (ownsCurrentState) followStateLoading.value = false
      }
    }
  })()
  pendingRead = pending
  return pending.promise
}

function retryFollowState(): void {
  if (disposed) return
  void loadFollowState(true)
}

async function toggleFollow(): Promise<void> {
  if (disposed || !authorId.value || followLoading.value || isMe.value) return
  if (!isLoggedIn.value) {
    followError.value = '请先登录'
    return
  }
  if (!knownUserIdentity()) {
    followError.value = '登录状态加载中，请稍候'
    return
  }
  if (followedByMe.value === null) {
    followError.value = followLoadError.value || '关注状态加载中，请稍候'
    return
  }

  const requestedAuthorId = authorId.value
  const snapshot = captureSnapshot(requestedAuthorId)
  if (!snapshot.token || !snapshot.storedToken || snapshot.token !== snapshot.storedToken) {
    setStorageDriftError('write', snapshot)
    return
  }

  const shouldFollow = !followedByMe.value
  const operation = ++mutationOperation
  mutationVersion += 1
  followLoading.value = true
  followError.value = ''
  try {
    const state = shouldFollow ? await followUser(requestedAuthorId) : await unfollowUser(requestedAuthorId)
    if (!currentMutationMatches(snapshot, operation)) {
      if (!storageStillMatches(snapshot)) setStorageDriftError('write', snapshot, operation)
      return
    }
    const nextFollowing = readFollowValue(state)
    if (nextFollowing === null) {
      markFollowResultUnknown()
      return
    }
    applyAcceptedState(snapshot, nextFollowing)
    try {
      window.dispatchEvent(new CustomEvent('follow-state-updated', {
        detail: {
          userId: requestedAuthorId,
          following: nextFollowing,
        },
      }))
    } catch (_) {}
  } catch (error: unknown) {
    if (currentMutationMatches(snapshot, operation)) {
      if (error instanceof InteractionResponseError) markFollowResultUnknown()
      else followError.value = errorMessage(error, '操作失败')
    } else if (!storageStillMatches(snapshot)) {
      setStorageDriftError('write', snapshot, operation)
    }
  } finally {
    if (!disposed && operation === mutationOperation && sameAuthor(snapshot.authorId)) followLoading.value = false
  }
}

let observedSession = sessionContext()

function applySessionChange(next: SessionContext, explicitEvent = false, loadImmediately = explicitEvent): void {
  if (disposed) return
  const previous = observedSession
  const hydration = isHydration(previous, next)
  observedSession = next
  if (!explicitEvent && sameSessionContext(previous, next)) return
  if (hydration) {
    // Token-only sessions may hydrate their user object after this read or
    // mutation starts. A reactive hydration is the same session, so preserve
    // its in-flight operation and let the pending read coalesce below.
    if (explicitEvent) {
      const hadPendingRead = Boolean(pendingRead && pendingRead.operation === readOperation &&
        pendingRead.mutationVersion === mutationVersion && pendingRead.epoch === sessionEpoch &&
        pendingRead.authorId === String(authorId.value ?? '') &&
        pendingRead.token === next.token && pendingRead.storedToken === next.storedToken)
      clearPrivateState({ invalidateRead: false, preserveHydratedRead: hadPendingRead })
      if (acceptedState && acceptedState.authorId === String(authorId.value ?? '') &&
        acceptedState.token === next.token && acceptedState.storedToken === next.storedToken &&
        acceptedState.epoch === sessionEpoch) {
        followedByMe.value = acceptedState.following
      }
    }
  } else {
    sessionEpoch += 1
    clearPrivateState()
  }
  if (!showFollowButton.value || !next.loggedIn) return
  if (loadImmediately) {
    void loadFollowState()
    return
  }
  if (sessionLoadQueued) return
  sessionLoadQueued = true
  queueMicrotask(() => {
    sessionLoadQueued = false
    if (disposed || !showFollowButton.value || !sessionContext().loggedIn) return
    void loadFollowState()
  })
}

function onReactiveSessionChange(): void {
  if (disposed) return
  const next = sessionContext()
  if (sameSessionContext(observedSession, next)) return
  applySessionChange(next)
}

function onAuthStateChanged(): void {
  if (disposed) return
  applySessionChange(sessionContext(), true)
}

function renderTitleMarkdownHtml(text: string | undefined): string {
  return renderInlineMarkdown(titleRenderer, text)
}

const stopAuthorWatch = watch(
  authorId,
  () => {
    clearPrivateState()
    void loadFollowState()
  },
  { immediate: true, flush: 'sync' },
)

const stopSessionWatch = watch(
  [() => String(token.value || ''), knownUserIdentity, () => Boolean(isLoggedIn.value)],
  onReactiveSessionChange,
  { flush: 'sync' },
)

onMounted(() => {
  window.addEventListener('auth-state-changed', onAuthStateChanged)
})

onBeforeUnmount(() => {
  disposed = true
  sessionLoadQueued = false
  sessionEpoch += 1
  readOperation += 1
  mutationOperation += 1
  mutationVersion += 1
  pendingRead = null
  stopAuthorWatch()
  stopSessionWatch()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

<template>
  <header class="border-b border-[var(--ui-border)] bg-[var(--ui-subtle)] px-5 py-6 sm:px-8">
    <div class="flex min-w-0 items-start gap-4">
      <button
        type="button"
        aria-label="返回"
        class="ui-button mt-1 h-10 w-10 shrink-0 !p-2 text-[var(--ui-muted)]"
        @click="$emit('back')"
      >
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
          <path fill-rule="evenodd" d="M11.03 3.97a.75.75 0 010 1.06l-6.22 6.22H21a.75.75 0 010 1.5H4.81l6.22 6.22a.75.75 0 11-1.06 1.06l-7.5-7.5a.75.75 0 010-1.06l7.5-7.5a.75.75 0 011.06 0z" clip-rule="evenodd" />
        </svg>
      </button>
      <div class="min-w-0 flex-1">
        <div class="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[var(--ui-muted)]">
          <router-link
            :to="{ name: 'discover', query: { sectionId: props.thread.sectionId, page: 1 } }"
            class="ui-tag"
          >
            {{ props.thread.sectionName || props.thread.sectionId }}
          </router-link>
          <span>·</span>
          <span>{{ formatRelativeTime(props.thread.createdAt) }}</span>
          <span v-if="props.thread.updatedAt && props.thread.updatedAt !== props.thread.createdAt" class="text-[var(--ui-muted)]">
            (编辑于 {{ formatRelativeTime(props.thread.updatedAt) }})
          </span>
        </div>
        <h1 class="ui-title break-words" v-html="renderTitleMarkdownHtml(props.thread.title)" />

        <div v-if="props.thread.tags?.length" class="mt-3 flex flex-wrap gap-2">
          <router-link
            v-for="tag in props.thread.tags"
            :key="tag"
            :to="{ name: 'discover', query: { q: tag, page: 1, sectionId: props.thread.sectionId } }"
            class="ui-tag"
          >
            #{{ tag }}
          </router-link>
        </div>

        <div class="mt-5 flex flex-wrap items-center justify-between gap-3">
          <router-link :to="props.thread.authorId ? (`/users/${props.thread.authorId}`) : '/users'" class="group flex min-w-0 items-center gap-2">
            <img
              :src="props.displayAuthorAvatarUrl || `https://api.dicebear.com/7.x/initials/svg?seed=${props.thread.authorNickname || props.thread.authorUsername || 'U'}`"
              class="h-8 w-8 rounded-full bg-[var(--ui-subtle)] object-cover"
              alt=""
            />
            <span class="truncate text-sm font-medium text-[var(--ui-ink)] group-hover:text-[var(--ui-accent)]">
              {{ props.thread.authorNickname || props.thread.authorUsername || props.thread.authorId }}
            </span>
          </router-link>
          <div v-if="showFollowButton" class="flex shrink-0 items-center gap-2">
            <span v-if="followLoadError" class="text-xs text-rose-600 dark:text-rose-300" role="alert">{{ followLoadError }}</span>
            <span v-else-if="followError" class="text-xs text-rose-600 dark:text-rose-300" role="alert">{{ followError }}</span>
            <button
              v-if="followLoadError"
              type="button"
              class="ui-button min-h-9 px-3 py-1 text-xs"
              @click="retryFollowState"
            >
              重试
            </button>
            <button
              type="button"
              class="ui-button min-h-9 px-3 py-1 text-xs disabled:opacity-60"
              :class="followedByMe ? '' : 'ui-button-primary'"
              :disabled="followButtonDisabled"
              @click="toggleFollow"
            >
              {{ followLoading ? '处理中...' : (followStateLoading ? '加载中...' : (followedByMe ? '已关注' : '关注')) }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch, type InjectionKey, type Ref } from 'vue'
import { listPosts, createPost, likePost, unlikePost } from '@/api/posts'
import { uploadImage } from '@/api/uploads'
import CommentsComposer from '@/components/comments/CommentsComposer.vue'
import CommentsList from '@/components/comments/CommentsList.vue'
import { useCommentGroups } from '@/components/comments/useCommentGroups'
import { useCommentUiState } from '@/components/comments/useCommentUiState'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { normalizeImageUrl } from '@/utils/image'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { InteractionResponseError } from '@/api/interactionState'
import { isPostCreationUnconfirmedError } from '@/api/postCreationResponse'
import { isUploadResponseUnconfirmedError } from '@/api/uploadResponse'
import type { Id, ProfileUpdatedDetail } from '@/types'
import type { UploadImageResponse } from '@/api/uploads'
import type { CommentItem, CommentsProps } from '@/components/comments/types'

const props = withDefaults(defineProps<CommentsProps>(), {
  autoCollapseCountThreshold: 5,
  autoCollapseWidthThreshold: 720,
  autoCollapseHeightThreshold: 480,
  childPageSize: 10,
  scrollToPostId: null,
})

const loading = ref(false)
const error = ref('')
const loadError = ref('')
const submissionUnconfirmed = ref(false)
const unconfirmedLikeMessage = '点赞结果暂未确认，请刷新评论后重试'
const items = ref<CommentItem[]>([])
const {
  groups,
  selectLikesOrder,
  selectRepliesOrder,
  selectTimeOrder,
  showRepliesMenu,
  showTimeMenu,
  sortKey,
  sortOrder,
  sortedGroups,
} = useCommentGroups(items)
const pagedGroups = computed(() => sortedGroups.value.slice())

const pageCount = computed(() => Math.max(1, Math.ceil(Number(total.value || 0) / Math.max(1, Number(size.value || 15)))))
const hasPagination = computed(() => Number(total.value || 0) > Math.max(1, Number(size.value || 15)))
const {
  clampGroupPage,
  collapsedMap,
  getChildrenPage,
  groupPageMap,
  highlightedPostId,
  setGroupRef,
  toggleCollapse,
  tryScrollToId,
  updateGroupPageInput,
} = useCommentUiState({
  autoCollapseCountThreshold: Number(props.autoCollapseCountThreshold || 5),
  autoCollapseHeightThreshold: Number(props.autoCollapseHeightThreshold || 480),
  autoCollapseWidthThreshold: Number(props.autoCollapseWidthThreshold || 720),
  childPageSize: Number(props.childPageSize || 10),
  groups,
  scrollToPostId: computed(() => props.scrollToPostId),
  threadId: computed(() => props.threadId),
})
const page = ref(1)
const size = ref(15)
const total = ref(0)
const content = ref('')
const replyToPostId = ref<Id | null>(null)
const authStore = useAuthStore()
const { isLoggedIn, token, user } = storeToRefs(authStore)
const previewMode = ref(false)
const isSubmitting = ref(false)
const isUploading = ref(false)
const rootRef = ref<HTMLElement | null>(null)
const commentSubmissionKey = Symbol.for('bluealbum.comments.submitting') as InjectionKey<Ref<boolean>>
provide(commentSubmissionKey, isSubmitting)
let loadRequestSeq = 0
let submitRequestSeq = 0
let uploadRequestSeq = 0
let activeUploadId: number | null = null
let likeRequestSeq = 0
const likeRequestByComment = new Map<string, LikeRequestContext>()
let disposed = false
let threadGeneration = 0
let sessionEpoch = 0
let applyingLoadResponse = false
let loadScheduled = false
let boundaryLoadPending = false
const listRevisionByQuery = new Map<string, number>()
let activeLoadRequestSeq: number | null = null
let activeLoadQueryKey: string | null = null

type SessionContext = Readonly<{
  memoryToken: string
  persistedToken: string
  ownerId: string
}>

type RequestSessionContext = SessionContext & { epoch: number }

type QueryContext = Readonly<{
  page: number
  size: number
  sortKey: string
  sortOrder: string
}>

type LikeRequestContext = RequestSessionContext & {
  requestSeq: number
  threadId: number
  commentKey: string
  queryKey: string
}

function normalizeSessionToken(value: unknown): string {
  const tokenValue = typeof value === 'string' ? value : ''
  return hasRealToken(tokenValue) ? tokenValue : ''
}

function getKnownOwnerId(): string {
  for (const owner of [user.value?.id, user.value?.userId]) {
    if (typeof owner === 'number' && Number.isSafeInteger(owner) && owner > 0) return String(owner)
    if (typeof owner === 'string' && /^[1-9]\d*$/.test(owner.trim())) return owner.trim()
  }
  return ''
}

function readSessionContext(): SessionContext {
  return Object.freeze({
    memoryToken: normalizeSessionToken(token.value),
    persistedToken: normalizeSessionToken(getStoredAccessToken()),
    ownerId: getKnownOwnerId(),
  })
}

let observedSession = readSessionContext()

function sameTokenPair(left: SessionContext, right: SessionContext): boolean {
  return left.memoryToken === right.memoryToken && left.persistedToken === right.persistedToken
}

function requiresSessionBoundary(previous: SessionContext, next: SessionContext): boolean {
  if (!sameTokenPair(previous, next)) return true
  // A token can be hydrated with its known identity after the component has
  // already started loading or uploading. Treat unknown -> known as the same
  // session; every other known-owner transition is a new owner boundary.
  return !!previous.ownerId && previous.ownerId !== next.ownerId
}

function isPairUsable(session: SessionContext): boolean {
  return session.memoryToken === session.persistedToken
}

function getQueryContext(): QueryContext {
  return Object.freeze({
    page: page.value,
    size: size.value,
    sortKey: sortKey.value,
    sortOrder: sortOrder.value,
  })
}

function getQueryKey(query: QueryContext): string {
  return [query.page, query.size, query.sortKey, query.sortOrder].join('|')
}

function scheduleBoundaryLoad(): void {
  if (boundaryLoadPending || disposed) return
  boundaryLoadPending = true
  scheduleLoad()
}

function clearSessionOwnedState(next: SessionContext): void {
  sessionEpoch += 1
  observedSession = next
  loadRequestSeq += 1
  submitRequestSeq += 1
  uploadRequestSeq += 1
  activeUploadId = null
  likeRequestByComment.clear()
  activeLoadRequestSeq = null
  activeLoadQueryKey = null
  listRevisionByQuery.clear()
  loading.value = false
  isSubmitting.value = false
  isUploading.value = false
  items.value = []
  total.value = 0
  content.value = ''
  replyToPostId.value = null
  submissionUnconfirmed.value = false
  previewMode.value = false
  groupPageMap.value = {}
  collapsedMap.value = {}
  error.value = '账号状态已变更，请刷新后重试'
  loadError.value = ''
  scheduleBoundaryLoad()
}

function reconcileSession(forceBoundary = false): SessionContext {
  const next = readSessionContext()
  if (forceBoundary || requiresSessionBoundary(observedSession, next)) {
    clearSessionOwnedState(next)
  } else {
    observedSession = next
  }
  return next
}

function captureRequestSession(): RequestSessionContext | null {
  const current = readSessionContext()
  const boundary = requiresSessionBoundary(observedSession, current)
  if (boundary) clearSessionOwnedState(current)
  else observedSession = current
  if (!isPairUsable(current)) {
    if (!boundary) clearSessionOwnedState(current)
    return null
  }
  return { ...current, epoch: sessionEpoch }
}

function isRequestSessionCurrent(session: RequestSessionContext): boolean {
  const current = captureRequestSession()
  if (!current) return false
  return session.epoch === sessionEpoch
    && sameTokenPair(session, current)
    && (!session.ownerId || session.ownerId === current.ownerId)
}

function markMutationAccepted(queryKey: string): void {
  listRevisionByQuery.set(queryKey, (listRevisionByQuery.get(queryKey) || 0) + 1)
  if (activeLoadRequestSeq !== null
    && activeLoadRequestSeq === loadRequestSeq
    && activeLoadQueryKey === queryKey) {
    loadRequestSeq += 1
    activeLoadRequestSeq = null
    activeLoadQueryKey = null
    loading.value = false
  }
}

function isCurrentThread(threadId: number, generation = threadGeneration): boolean {
  return !disposed
    && generation === threadGeneration
    && String(props.threadId) === String(threadId)
}

function isCurrentLoad(requestSeq: number, threadId: number, session: RequestSessionContext, queryKey: string, revision: number): boolean {
  return isCurrentThread(threadId)
    && requestSeq === loadRequestSeq
    && revision === (listRevisionByQuery.get(queryKey) || 0)
    && isRequestSessionCurrent(session)
}

function isCurrentSubmit(requestSeq: number, threadId: number, session: RequestSessionContext): boolean {
  return isCurrentThread(threadId)
    && requestSeq === submitRequestSeq
    && isRequestSessionCurrent(session)
}

function isCurrentLike(request: LikeRequestContext): boolean {
  return isCurrentThread(request.threadId)
    && likeRequestByComment.get(request.commentKey) === request
    && request.queryKey === getQueryKey(getQueryContext())
    && isRequestSessionCurrent(request)
}

function isCurrentUpload(uploadId: number, threadId: number, session: RequestSessionContext): boolean {
  return activeUploadId === uploadId && isCurrentThread(threadId) && isRequestSessionCurrent(session)
}

function scheduleLoad(): void {
  if (loadScheduled) return
  loadScheduled = true
  nextTick(() => {
    loadScheduled = false
    if (disposed) return
    const current = readSessionContext()
    boundaryLoadPending = false
    if (isPairUsable(current)) void load()
  })
}

// 资料更新事件：当我更换头像后，更新当前页面中我发表的评论头像
function onProfileUpdated(evt: Event) {
  try {
    const detail = (evt as CustomEvent<ProfileUpdatedDetail>).detail
    const myId = user.value?.id ?? user.value?.userId
    if (!isLoggedIn.value || myId == null || detail?.id == null || String(detail.id) !== String(myId) || typeof detail.avatarUrl !== 'string') return
    const next = detail.avatarUrl
    const apply = (arr: CommentItem[]) => (arr || []).map(it => (String(it?.authorId ?? '') === String(myId) ? { ...it, authorAvatarUrl: next } : it))
    items.value = apply(items.value)
  } catch (_) {}
}

async function load(): Promise<void> {
  const threadId = props.threadId
  const requestedPage = page.value
  const requestedSize = size.value
  const requestedSortKey = sortKey.value
  const requestedSortOrder = sortOrder.value
  const query = getQueryContext()
  const queryKey = getQueryKey(query)
  const session = captureRequestSession()
  if (!session) return
  const requestSeq = ++loadRequestSeq
  const requestedRevision = listRevisionByQuery.get(queryKey) || 0
  activeLoadRequestSeq = requestSeq
  activeLoadQueryKey = queryKey
  loading.value = true
  error.value = ''
  loadError.value = ''
  try {
    const data = await listPosts(threadId, {
      page: requestedPage,
      size: requestedSize,
      sort: requestedSortKey === 'likes' ? 'likeCount' : 'time',
    })
    if (!isCurrentLoad(requestSeq, threadId, session, queryKey, requestedRevision)) return
    if (
      page.value !== requestedPage
      || size.value !== requestedSize
      || sortKey.value !== requestedSortKey
      || sortOrder.value !== requestedSortOrder
    ) return
    applyingLoadResponse = true
    items.value = data.items as CommentItem[]
    total.value = data.total
    page.value = data.page
    size.value = data.size
    applyingLoadResponse = false
    tryScrollToId(props.scrollToPostId)
  } catch (_) {
    if (isCurrentLoad(requestSeq, threadId, session, queryKey, requestedRevision)) {
      loadError.value = '加载评论失败'
      error.value = loadError.value
    }
  } finally {
    if (activeLoadRequestSeq === requestSeq) {
      activeLoadRequestSeq = null
      activeLoadQueryKey = null
    }
    if (isCurrentLoad(requestSeq, threadId, session, queryKey, requestedRevision)) loading.value = false
  }
}

function retryLoad(): void {
  if (!loading.value && !disposed) void load()
}

async function toggleLike(comment: CommentItem): Promise<void> {
  const session = captureRequestSession()
  if (!session) return
  if (!isLoggedIn.value) {
    error.value = '请先登录后再点赞'
    return
  }
  if (!comment?.id || comment._optimistic) {
    return
  }
  const threadId = props.threadId
  const queryKey = getQueryKey(getQueryContext())
  const commentKey = String(comment.id)
  if (likeRequestByComment.has(commentKey)) return
  error.value = ''
  const request: LikeRequestContext = {
    ...session,
    requestSeq: ++likeRequestSeq,
    threadId,
    commentKey,
    queryKey,
  }
  likeRequestByComment.set(commentKey, request)
  try {
    const state = comment.liked ? await unlikePost(comment.id) : await likePost(comment.id)
    if (!isCurrentLike(request)) return
    markMutationAccepted(queryKey)
    items.value = items.value.map((item) => (
      item.id === comment.id
        ? { ...item, liked: state.liked, likeCount: state.likeCount }
        : item
    ))
  } catch (cause: unknown) {
    if (isCurrentLike(request)) {
      error.value = cause instanceof InteractionResponseError ? unconfirmedLikeMessage : '点赞操作失败，请稍后再试'
    }
  } finally {
    if (likeRequestByComment.get(commentKey) === request) likeRequestByComment.delete(commentKey)
  }
}

async function submit(): Promise<void> {
  const session = captureRequestSession()
  if (!session) return
  if (isSubmitting.value) return
  if (isUploading.value) {
    error.value = '请等待图片上传完成'
    return
  }
  const requestSeq = ++submitRequestSeq
  const threadId = props.threadId
  const queryKey = getQueryKey(getQueryContext())
  isSubmitting.value = true
  error.value = ''
  if (!isLoggedIn.value) {
    error.value = '请先登录再评论'
    isSubmitting.value = false
    return
  }
  const text = String(content.value || '').trim()
  if (!text) {
    error.value = '请输入评论内容'
    isSubmitting.value = false
    return
  }
  if (text.length > 3000) {
    error.value = '内容过长'
    isSubmitting.value = false
    return
  }
  submissionUnconfirmed.value = false
  const optimistic: CommentItem = {
    id: 'temp_' + Math.random().toString(36).slice(2),
    threadId,
    authorId: session.ownerId || undefined,
    authorUsername: user.value?.username || '我',
    authorNickname: user.value?.nickname || user.value?.username || '我',
    authorAvatarUrl: user.value?.avatarUrl || '',
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
    const created = await createPost(threadId, { contentMd: text, replyToPostId: replyToPostId.value || null })
    if (!isCurrentSubmit(requestSeq, threadId, session)) return
    markMutationAccepted(queryKey)
    items.value = items.value.map(it => (it.id === optimistic.id ? created : it))
    content.value = ''
    replyToPostId.value = null
    if (!isCurrentSubmit(requestSeq, threadId, session)) return
    await load()
  } catch (e: unknown) {
    if (!isCurrentSubmit(requestSeq, threadId, session)) return
    const err = e as { response?: { data?: { message?: string } } } | null
    submissionUnconfirmed.value = isPostCreationUnconfirmedError(e)
    error.value = submissionUnconfirmed.value ? '' : err?.response?.data?.message || '发布失败'
    const hadOptimisticRow = items.value.some(it => it.id === optimistic.id)
    items.value = items.value.filter(it => it.id !== optimistic.id)
    if (hadOptimisticRow) total.value = Math.max(0, Number(total.value || 0) - 1)
  } finally {
    if (isCurrentSubmit(requestSeq, threadId, session)) isSubmitting.value = false
  }
}

function getUploadErrorMessage(reason: unknown): string {
  const failure = reason as {
    code?: unknown
    message?: unknown
    response?: {
      status?: unknown
      data?: { message?: unknown }
    }
  } | null
  if (isUploadResponseUnconfirmedError(reason)) return reason.message
  const status = Number(failure?.response?.status || 0)
  const responseMessage = failure?.response?.data?.message
  const message = typeof responseMessage === 'string' ? responseMessage.trim() : ''

  if (status === 401 || status === 403 || /unauthori[sz]ed|forbidden/i.test(message)) {
    return '登录已过期，请重新登录后再上传图片'
  }

  const code = String(failure?.code || '')
  const detail = String(failure?.message || '')
  if (code === 'ECONNABORTED' || code === 'ETIMEDOUT' || /timeout/i.test(detail)) {
    return '图片上传超时，请稍后重试'
  }

  return message ? `图片上传失败：${message}` : '图片上传失败，请稍后重试'
}

async function handleUploadImage(file: File, onProgress?: (percent: number) => void): Promise<void> {
  const session = captureRequestSession()
  if (!session) return
  const threadId = props.threadId
  if (isSubmitting.value || isUploading.value) {
    if (!isUploading.value) error.value = '请等待评论发送完成'
    return
  }
  const uploadId = ++uploadRequestSeq
  activeUploadId = uploadId
  isUploading.value = true
  try {
    if (!hasRealToken(session.memoryToken)) {
      if (isCurrentUpload(uploadId, threadId, session)) error.value = '请先登录后再上传图片'
      return
    }
    const guardedProgress = onProgress
      ? (percent: number) => {
        if (isCurrentUpload(uploadId, threadId, session)) onProgress(percent)
      }
      : undefined
    const resp: UploadImageResponse = await uploadImage(file, session.memoryToken, guardedProgress)
    if (!isCurrentUpload(uploadId, threadId, session)) return
    const normalized = normalizeImageUrl(resp?.url || resp?.path || '')
    if (!normalized) {
      error.value = '图片上传失败：服务器未返回有效图片地址'
      return
    }
    content.value = (content.value || '') + `\n\n![](${normalized})\n\n`
    error.value = ''
  } catch (reason: unknown) {
    if (isCurrentUpload(uploadId, threadId, session)) error.value = getUploadErrorMessage(reason)
  } finally {
    if (activeUploadId === uploadId) {
      activeUploadId = null
      isUploading.value = false
    }
  }
}

function getInputFile(event: Event): File | null {
  const target = event.target as HTMLInputElement | null
  return target?.files?.[0] || null
}

function onSelectUploadImage(event: Event): void {
  const file = getInputFile(event)
  if (!file) return
  const target = event.target as HTMLInputElement | null
  // 清空 input 的值，允许用户在失败后再次选择同一个文件。
  if (target) target.value = ''
  void handleUploadImage(file)
}

function getInputNumber(event: Event): number | null {
  const target = event.target as HTMLInputElement | null
  const value = Number(String(target?.value || '').replace(/[^0-9]/g, ''))
  return Number.isNaN(value) ? null : value
}

function updatePageInput(event: Event): void {
  const value = getInputNumber(event)
  if (value != null) {
    page.value = value
  }
}

function setReplyTo(id: Id, rootId?: Id) {
  if (isSubmitting.value || isUploading.value) return
  const target = items.value.find(item => String(item.id) === String(id))
  if (!target || target._optimistic) return
  replyToPostId.value = target.id
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

function focusComposer(): void {
  if (!isLoggedIn.value) {
    try {
      window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source: 'thread-reply' } }))
    } catch (_) {}
    return
  }

  replyToPostId.value = null
  void nextTick(() => {
    const textarea = rootRef.value?.querySelector('textarea')
    if (!textarea) return
    textarea.focus({ preventScroll: true })
    rootRef.value?.scrollIntoView({ behavior: 'auto', block: 'center' })
  })
}

defineExpose({ focusComposer })

function goPrevGroupPage(rootId: Id): void {
  const current = Number(groupPageMap.value[String(rootId)] || 1)
  if (current > 1) {
    groupPageMap.value[String(rootId)] = current - 1
  }
}

function goNextGroupPage(rootId: Id): void {
  groupPageMap.value[String(rootId)] = Number(groupPageMap.value[String(rootId)] || 1) + 1
}

function onAuthStateChanged(): void {
  reconcileSession(true)
}

function onStorageChanged(event: StorageEvent): void {
  if (event.key !== null && event.key !== 'accessToken' && event.key !== 'currentUser') return
  reconcileSession(true)
}

onMounted(() => {
  window.addEventListener('profile-updated', onProfileUpdated)
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  window.addEventListener('storage', onStorageChanged)
  void load()
})
watch(() => props.threadId, () => {
  threadGeneration += 1
  const previousPage = page.value
  page.value = 1
  items.value = []
  total.value = 0
  replyToPostId.value = null
  groupPageMap.value = {}
  collapsedMap.value = {}
  submitRequestSeq += 1
  likeRequestByComment.clear()
  isSubmitting.value = false
  activeUploadId = null
  uploadRequestSeq += 1
  isUploading.value = false
  activeLoadRequestSeq = null
  activeLoadQueryKey = null
  listRevisionByQuery.clear()
  loading.value = false
  content.value = ''
  error.value = ''
  loadError.value = ''
  submissionUnconfirmed.value = false
  if (previousPage === 1) void load()
})

onBeforeUnmount(() => {
  disposed = true
  threadGeneration += 1
  loadRequestSeq += 1
  submitRequestSeq += 1
  activeUploadId = null
  uploadRequestSeq += 1
  isUploading.value = false
  likeRequestByComment.clear()
  activeLoadRequestSeq = null
  try {
    window.removeEventListener('profile-updated', onProfileUpdated)
    window.removeEventListener('auth-state-changed', onAuthStateChanged)
    window.removeEventListener('storage', onStorageChanged)
  } catch (_) {}
})

watch([token, getKnownOwnerId], () => {
  if (!disposed) reconcileSession()
}, { flush: 'sync' })

watch(page, () => {
  if (applyingLoadResponse || disposed) return
  loadRequestSeq += 1
  scheduleLoad()
}, { flush: 'sync' })

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
  const previousPage = page.value
  page.value = 1
  if (applyingLoadResponse || disposed) return
  loadRequestSeq += 1
  if (previousPage === 1) scheduleLoad()
}, { flush: 'sync' })
</script>

<template>
  <section id="thread-comments" ref="rootRef" class="ui-panel mt-10" aria-labelledby="comments-heading">
    <div class="border-b border-[var(--ui-border)] px-4 py-5 sm:px-6" v-if="replyToPostId == null">
      <CommentsComposer
        :is-logged-in="isLoggedIn"
        :is-submitting="isSubmitting"
        :is-uploading="isUploading"
        :model-value="content"
        :preview-mode="previewMode"
        @submit="submit"
        @update:model-value="content = $event"
        @update:preview-mode="previewMode = $event"
        @upload-image="onSelectUploadImage"
      />
    </div>
    <p v-if="error && error !== loadError" class="mx-4 my-3 text-sm text-red-600 dark:text-red-300 sm:mx-6" role="alert">{{ error }}</p>
    <div v-if="submissionUnconfirmed" class="mx-4 my-3 text-sm sm:mx-6" role="alert">
      <p>评论发送结果暂未确认，当前输入已保留。请先刷新评论核对，避免重复发送。</p>
      <button type="button" class="ui-button mt-2 min-h-11 px-3 py-2 text-sm" :disabled="loading" @click="retryLoad">刷新评论核对</button>
    </div>
    <div v-if="error === unconfirmedLikeMessage" class="px-4 pb-3 sm:px-6">
      <button type="button" class="ui-button" :disabled="loading" @click="retryLoad">刷新评论状态</button>
    </div>
    <CommentsList
      :child-page-size="Number(props.childPageSize || 10)"
      :collapsed-map="collapsedMap"
      :error="loadError"
      :get-children-page="getChildrenPage"
      :group-page-map="groupPageMap"
      :has-pagination="hasPagination"
      :highlighted-post-id="highlightedPostId"
      :is-logged-in="isLoggedIn"
      :is-submitting="isSubmitting"
      :is-uploading="isUploading"
      :loading="loading"
      :model-value="content"
      :page="page"
      :page-count="pageCount"
      :paged-groups="pagedGroups"
      :preview-mode="previewMode"
      :reply-to-post-id="replyToPostId"
      :set-group-ref="setGroupRef"
      :show-replies-menu="showRepliesMenu"
      :show-time-menu="showTimeMenu"
      :sort-key="sortKey"
      :sort-order="sortOrder"
      @apply-page-input="applyPageInput"
      @cancel-reply="cancelReply"
      @clamp-group-page="clampGroupPage"
      @go-next-page="goNextPage"
      @go-prev-page="goPrevPage"
      @next-group-page="goNextGroupPage"
      @prev-group-page="goPrevGroupPage"
      @reply="setReplyTo"
      @select-likes-order="selectLikesOrder"
      @select-replies-order="selectRepliesOrder"
      @select-time-order="selectTimeOrder"
      @submit="submit"
      @toggle-collapse="toggleCollapse"
      @toggle-like="toggleLike"
      @update-group-page-input="updateGroupPageInput"
      @update:model-value="content = $event"
      @update:page-input="updatePageInput"
      @update:preview-mode="previewMode = $event"
      @update:showRepliesMenu="showRepliesMenu = $event"
      @update:showTimeMenu="showTimeMenu = $event"
      @upload-image="onSelectUploadImage"
    />
    <div v-if="loadError && !loading" class="px-4 pb-5 sm:px-6">
      <button type="button" class="ui-button" @click="retryLoad">重试加载评论</button>
    </div>
  </section>
</template>

<style scoped>
</style>

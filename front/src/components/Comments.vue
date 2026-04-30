<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
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

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await listPosts(props.threadId, {
      page: page.value,
      size: size.value,
      sort: sortKey.value === 'likes' ? 'likeCount' : 'time',
    })
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

async function toggleLike(comment: CommentItem): Promise<void> {
  if (!isLoggedIn.value) {
    error.value = '请先登录后再点赞'
    return
  }
  if (!comment?.id || comment._optimistic) {
    return
  }
  try {
    const state = comment.liked ? await unlikePost(comment.id) : await likePost(comment.id)
    items.value = items.value.map((item) => (
      item.id === comment.id
        ? { ...item, liked: state.liked, likeCount: state.likeCount }
        : item
    ))
  } catch (_) {
    error.value = '点赞操作失败，请稍后再试'
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

function goPrevGroupPage(rootId: Id): void {
  const current = Number(groupPageMap.value[String(rootId)] || 1)
  if (current > 1) {
    groupPageMap.value[String(rootId)] = current - 1
  }
}

function goNextGroupPage(rootId: Id): void {
  groupPageMap.value[String(rootId)] = Number(groupPageMap.value[String(rootId)] || 1) + 1
}

onMounted(load)
onMounted(() => { window.addEventListener('profile-updated', onProfileUpdated) })
watch(() => props.threadId, () => { page.value = 1; load() })

onBeforeUnmount(() => {
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
  page.value = 1
  load()
})
</script>

<template>
  <div class="mt-8">
    <div class="mb-3" v-if="replyToPostId == null">
      <CommentsComposer
        :is-logged-in="isLoggedIn"
        :model-value="content"
        :preview-mode="previewMode"
        @submit="submit"
        @update:model-value="content = $event"
        @update:preview-mode="previewMode = $event"
        @upload-image="onSelectUploadImage"
      />
    </div>
    <CommentsList
      :child-page-size="Number(props.childPageSize || 10)"
      :collapsed-map="collapsedMap"
      :error="error"
      :get-children-page="getChildrenPage"
      :group-page-map="groupPageMap"
      :has-pagination="hasPagination"
      :highlighted-post-id="highlightedPostId"
      :is-logged-in="isLoggedIn"
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
  </div>
</template>

<style scoped>
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { bookmarkThread, getThreadBookmarkState, unbookmarkThread } from '@/api/bookmarks'
import { getThreadLikeState, likeThread, unlikeThread } from '@/api/threads'
import ReportDialog from '@/components/ReportDialog.vue'
import { useAuthStore } from '@/stores/auth'
import type { Thread } from '@/types'

const props = defineProps<{
  thread: Thread
}>()

const emit = defineEmits<{
  updated: [patch: Partial<Thread>]
}>()

const authStore = useAuthStore()
const { isLoggedIn } = storeToRefs(authStore)
const likePending = ref(false)
const bookmarkPending = ref(false)
const bookmarkLoading = ref(false)
const message = ref('')
const bookmarkMessage = ref('')
const reportDialogOpen = ref(false)
const reportMessage = ref('')
let bookmarkRequestSequence = 0

const likeCount = computed(() => Number(props.thread.likeCount || 0))
const liked = computed(() => Boolean(props.thread.liked))
const bookmarked = ref(false)

async function refreshLikeState(): Promise<void> {
  if (!props.thread.id || !isLoggedIn.value) {
    return
  }
  try {
    const state = await getThreadLikeState(props.thread.id)
    if (state.liked !== liked.value || state.likeCount !== likeCount.value) {
      emit('updated', { liked: state.liked, likeCount: state.likeCount })
    }
  } catch (_) {}
}

async function toggleLike(): Promise<void> {
  if (!props.thread.id || likePending.value) {
    return
  }
  if (!isLoggedIn.value) {
    message.value = '登录后可以点赞'
    return
  }
  likePending.value = true
  message.value = ''
  try {
    const state = liked.value
      ? await unlikeThread(props.thread.id)
      : await likeThread(props.thread.id)
    emit('updated', { liked: state.liked, likeCount: state.likeCount })
  } catch (_) {
    message.value = '操作失败，请稍后再试'
  } finally {
    likePending.value = false
  }
}

async function refreshBookmarkState(): Promise<void> {
  const requestId = ++bookmarkRequestSequence
  bookmarkPending.value = false
  bookmarkMessage.value = ''
  if (!props.thread.id || !isLoggedIn.value) {
    bookmarked.value = false
    bookmarkLoading.value = false
    return
  }
  bookmarkLoading.value = true
  try {
    const state = await getThreadBookmarkState(props.thread.id)
    if (requestId === bookmarkRequestSequence) {
      bookmarked.value = Boolean(state.bookmarked)
    }
  } catch (_) {
    if (requestId === bookmarkRequestSequence) {
      bookmarkMessage.value = '收藏状态加载失败，请稍后重试'
    }
  } finally {
    if (requestId === bookmarkRequestSequence) {
      bookmarkLoading.value = false
    }
  }
}

async function toggleBookmark(): Promise<void> {
  if (!props.thread.id || bookmarkLoading.value || bookmarkPending.value) {
    return
  }
  if (!isLoggedIn.value) {
    bookmarkMessage.value = '登录后可以收藏'
    return
  }
  const threadId = props.thread.id
  const requestId = ++bookmarkRequestSequence
  bookmarkPending.value = true
  bookmarkMessage.value = ''
  try {
    const state = bookmarked.value
      ? await unbookmarkThread(threadId)
      : await bookmarkThread(threadId)
    if (requestId === bookmarkRequestSequence && String(props.thread.id) === String(threadId)) {
      bookmarked.value = Boolean(state.bookmarked)
    }
  } catch (_) {
    if (requestId === bookmarkRequestSequence) {
      bookmarkMessage.value = '收藏操作失败，请稍后再试'
    }
  } finally {
    bookmarkPending.value = false
  }
}

function openReportDialog(): void {
  if (!isLoggedIn.value) {
    reportMessage.value = '登录后可以举报'
    return
  }
  reportMessage.value = ''
  reportDialogOpen.value = true
}

watch(
  [() => props.thread.id, isLoggedIn],
  () => {
    void refreshLikeState()
    void refreshBookmarkState()
  },
  { immediate: true },
)
</script>

<template>
  <section class="px-5 py-5 sm:px-8" aria-label="帖子互动">
    <div class="flex flex-wrap items-center gap-3">
      <button
        type="button"
        :aria-pressed="liked"
        :disabled="likePending"
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
        :disabled="bookmarkLoading || bookmarkPending"
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
      <span v-if="bookmarkMessage" class="ui-muted text-sm" role="status">{{ bookmarkMessage }}</span>
      <span v-if="reportMessage" class="text-sm text-amber-700 dark:text-amber-300" role="alert">{{ reportMessage }}</span>
    </div>
    <ReportDialog
      v-if="reportDialogOpen"
      :model-value="reportDialogOpen"
      target-type="THREAD"
      :target-id="Number(thread.id)"
      :is-logged-in="isLoggedIn"
      @update:model-value="reportDialogOpen = $event"
    />
  </section>
</template>

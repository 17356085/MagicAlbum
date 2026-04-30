<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { getThreadLikeState, likeThread, unlikeThread } from '@/api/threads'
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
const pending = ref(false)
const message = ref('')

const likeCount = computed(() => Number(props.thread.likeCount || 0))
const liked = computed(() => Boolean(props.thread.liked))

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
  if (!props.thread.id || pending.value) {
    return
  }
  if (!isLoggedIn.value) {
    message.value = '登录后可以点赞'
    return
  }
  pending.value = true
  message.value = ''
  try {
    const state = liked.value
      ? await unlikeThread(props.thread.id)
      : await likeThread(props.thread.id)
    emit('updated', { liked: state.liked, likeCount: state.likeCount })
  } catch (_) {
    message.value = '操作失败，请稍后再试'
  } finally {
    pending.value = false
  }
}

watch(
  [() => props.thread.id, isLoggedIn],
  () => {
    void refreshLikeState()
  },
  { immediate: true },
)
</script>

<template>
  <div class="border-t border-gray-100 px-5 py-5 dark:border-gray-700 sm:px-8">
    <div class="flex flex-wrap items-center gap-3">
      <button
        type="button"
        :aria-pressed="liked"
        :disabled="pending"
        class="inline-flex min-h-10 items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-60"
        :class="liked
          ? 'border-rose-200 bg-rose-50 text-rose-600 hover:bg-rose-100 dark:border-rose-900/60 dark:bg-rose-950/40 dark:text-rose-300'
          : 'border-gray-200 bg-white text-gray-700 hover:border-rose-200 hover:text-rose-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:hover:border-rose-800 dark:hover:text-rose-300'"
        @click="toggleLike"
      >
        <span class="text-base leading-none">{{ liked ? '♥' : '♡' }}</span>
        <span>{{ liked ? '已点赞' : '点赞' }}</span>
        <span class="text-xs opacity-75">{{ likeCount }}</span>
      </button>
      <span v-if="message" class="text-sm text-gray-500 dark:text-gray-400">{{ message }}</span>
    </div>
  </div>
</template>

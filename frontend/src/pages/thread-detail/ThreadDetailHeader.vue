<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { createMarkdownRenderer, renderInlineMarkdown } from '@/utils/markdown'
import { formatRelativeTime } from '@/composables/time'
import { followUser, getUserProfile, unfollowUser } from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import type { Thread } from '@/types'

const props = defineProps<{
  displayAuthorAvatarUrl?: string
  thread: Thread
}>()

defineEmits<{
  back: []
}>()

const titleRenderer = createMarkdownRenderer({ html: false, breaks: false, highlight: false })
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const followedByMe = ref(false)
const followLoading = ref(false)
const followError = ref('')

const authorId = computed(() => props.thread.authorId || null)
const isMe = computed(() => String(user.value?.id || user.value?.userId || '') === String(authorId.value || ''))
const showFollowButton = computed(() => Boolean(authorId.value) && !isMe.value)

function renderTitleMarkdownHtml(text: string | undefined): string {
  return renderInlineMarkdown(titleRenderer, text)
}

async function loadFollowState(): Promise<void> {
  followError.value = ''
  followedByMe.value = false
  if (!showFollowButton.value || !authorId.value) return
  try {
    const profile = await getUserProfile(authorId.value)
    followedByMe.value = Boolean(profile.followedByMe)
  } catch (_) {
    followedByMe.value = false
  }
}

async function toggleFollow(): Promise<void> {
  if (!authorId.value || followLoading.value) return
  if (!isLoggedIn.value) {
    followError.value = '请先登录'
    return
  }
  followLoading.value = true
  followError.value = ''
  try {
    const state = followedByMe.value
      ? await unfollowUser(authorId.value)
      : await followUser(authorId.value)
    followedByMe.value = Boolean(state.followedByMe ?? state.following)
    try {
      window.dispatchEvent(new CustomEvent('follow-state-updated', {
        detail: {
          userId: authorId.value,
          following: followedByMe.value,
        },
      }))
    } catch (_) {}
  } catch (_) {
    followError.value = '操作失败'
  } finally {
    followLoading.value = false
  }
}

watch(authorId, loadFollowState, { immediate: true })
</script>

<template>
  <div class="border-b border-gray-100 bg-gray-50/50 p-5 dark:border-gray-700 dark:bg-gray-800/50">
    <div class="flex items-start gap-4">
      <button
        aria-label="返回"
        class="mt-1 shrink-0 rounded-full p-1.5 text-gray-400 transition-colors hover:bg-gray-200 hover:text-gray-700 dark:hover:bg-gray-700 dark:hover:text-gray-200"
        @click="$emit('back')"
      >
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
          <path fill-rule="evenodd" d="M11.03 3.97a.75.75 0 010 1.06l-6.22 6.22H21a.75.75 0 010 1.5H4.81l6.22 6.22a.75.75 0 11-1.06 1.06l-7.5-7.5a.75.75 0 010-1.06l7.5-7.5a.75.75 0 011.06 0z" clip-rule="evenodd" />
        </svg>
      </button>
      <div class="min-w-0 flex-1">
        <div class="mb-2 flex items-center gap-3 text-xs text-gray-500">
          <router-link
            :to="{ name: 'discover', query: { sectionId: props.thread.sectionId, page: 1 } }"
            class="rounded bg-brandDay-50 px-2 py-0.5 font-medium text-brandDay-600 transition-colors hover:bg-brandDay-100 dark:bg-brandNight-900/30 dark:text-brandNight-300 dark:hover:bg-brandNight-900/50"
          >
            {{ props.thread.sectionName || props.thread.sectionId }}
          </router-link>
          <span>·</span>
          <span>{{ formatRelativeTime(props.thread.createdAt) }}</span>
          <span v-if="props.thread.updatedAt && props.thread.updatedAt !== props.thread.createdAt" class="text-gray-400">
            (编辑于 {{ formatRelativeTime(props.thread.updatedAt) }})
          </span>
        </div>
        <h1 class="break-words text-2xl font-bold leading-tight text-gray-900 dark:text-gray-100" v-html="renderTitleMarkdownHtml(props.thread.title)" />

        <div v-if="props.thread.tags?.length" class="mt-3 flex flex-wrap gap-2">
          <router-link
            v-for="tag in props.thread.tags"
            :key="tag"
            :to="{ name: 'discover', query: { q: tag, page: 1, sectionId: props.thread.sectionId } }"
            class="rounded-md bg-gray-100 px-2.5 py-1 text-xs font-medium text-gray-500 transition-colors hover:bg-brandDay-50 hover:text-brandDay-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-brandNight-900/30 dark:hover:text-brandNight-300"
          >
            #{{ tag }}
          </router-link>
        </div>

        <div class="mt-4 flex items-center justify-between gap-3">
          <router-link :to="props.thread.authorId ? (`/users/${props.thread.authorId}`) : '/users'" class="group flex items-center gap-2">
            <img
              :src="props.displayAuthorAvatarUrl || `https://api.dicebear.com/7.x/initials/svg?seed=${props.thread.authorNickname || props.thread.authorUsername || 'U'}`"
              class="h-8 w-8 rounded-full bg-gray-100 object-cover ring-2 ring-transparent transition-all group-hover:ring-brandDay-100 dark:bg-gray-700 dark:group-hover:ring-brandNight-900"
              alt=""
            />
            <span class="text-sm font-medium text-gray-700 transition-colors group-hover:text-brandDay-600 dark:text-gray-300 dark:group-hover:text-brandNight-400">
              {{ props.thread.authorNickname || props.thread.authorUsername || props.thread.authorId }}
            </span>
          </router-link>
          <div v-if="showFollowButton" class="flex shrink-0 items-center gap-2">
            <span v-if="followError" class="text-xs text-red-500">{{ followError }}</span>
            <button
              type="button"
              class="rounded px-3 py-1.5 text-xs font-medium shadow-sm transition-colors disabled:opacity-60"
              :class="followedByMe ? 'border border-gray-200 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:hover:bg-gray-700' : 'bg-brandDay-600 text-white hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-700'"
              :disabled="followLoading"
              @click="toggleFollow"
            >
              {{ followLoading ? '处理中...' : (followedByMe ? '已关注' : '关注') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

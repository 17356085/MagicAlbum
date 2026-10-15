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
            <span v-if="followError" class="text-xs text-rose-600 dark:text-rose-300" role="alert">{{ followError }}</span>
            <button
              type="button"
              class="ui-button min-h-9 px-3 py-1 text-xs disabled:opacity-60"
              :class="followedByMe ? '' : 'ui-button-primary'"
              :disabled="followLoading"
              @click="toggleFollow"
            >
              {{ followLoading ? '处理中...' : (followedByMe ? '已关注' : '关注') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </header>
</template>

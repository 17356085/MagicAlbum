<template>
  <div class="flex items-center gap-2">
    <router-link :to="profileLink" class="flex items-center gap-2 hover:opacity-90">
      <img
        :src="resolvedAvatarUrl"
        alt="avatar"
        class="w-8 h-8 rounded-full object-cover border border-gray-300 dark:border-gray-700 bg-gray-100 dark:bg-gray-700"
      />
      <span class="text-gray-700 dark:text-gray-200">{{ displayLabel }}</span>
    </router-link>
    <button class="rounded px-3 py-1 hover:bg-gray-100 dark:hover:bg-gray-700 inline-flex items-center" aria-label="登出" title="登出" @click="$emit('logout')">
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="w-5 h-5">
        <path d="M12 4v7.5" stroke-width="1.8" stroke-linecap="round" />
        <path d="M7.5 6.5a7 7 0 1 0 9 0" fill="none" stroke-width="1.8" stroke-linecap="round" />
      </svg>
      <span class="sr-only">登出</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { normalizeImageUrl } from '@/utils/image'
import type { User } from '@/types'

const props = defineProps<{
  user: User | null
  avatarUrl: string
  displayName: string
}>()

defineEmits<{
  logout: []
}>()

const currentUserId = computed(() => props.user?.id || props.user?.userId || null)
const profileLink = computed(() => (
  currentUserId.value
    ? { name: 'user-profile', params: { id: currentUserId.value } }
    : { name: 'users' }
))
const displayLabel = computed(() => props.displayName || props.user?.username || '')
const resolvedAvatarUrl = computed(() => {
  if (props.avatarUrl) {
    return normalizeImageUrl(props.avatarUrl)
  }
  return `https://api.dicebear.com/7.x/initials/svg?seed=${displayLabel.value || 'U'}`
})
</script>

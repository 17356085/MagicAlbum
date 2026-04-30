<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { safeBack as navigateBackSafely } from '@/utils/router'
import Comments from '@/components/Comments.vue'
import ThreadDetailActions from '@/pages/thread-detail/ThreadDetailActions.vue'
import ThreadDetailContent from '@/pages/thread-detail/ThreadDetailContent.vue'
import ThreadDetailHeader from '@/pages/thread-detail/ThreadDetailHeader.vue'
import ThreadDetailSummary from '@/pages/thread-detail/ThreadDetailSummary.vue'
import { useThreadDetailPage } from '@/pages/thread-detail/useThreadDetailPage'

const route = useRoute()
const router = useRouter()
const { anchorPostId, displayAuthorAvatarUrl, error, load, loading, thread, updateAnchorFromHash, updateThread } = useThreadDetailPage(route)

function safeBack(): void {
  navigateBackSafely(router)
}

watch(
  () => route.params.id,
  async () => {
    await load()
    updateAnchorFromHash()
  },
  { immediate: true },
)

watch(
  () => route.hash,
  () => {
    updateAnchorFromHash()
  },
)
</script>

<template>
  <div>
    <div v-if="loading" class="text-gray-600">正在加载...</div>
    <div v-else>
      <div v-if="error" class="text-red-600 mb-3">{{ error }}</div>
      <div v-else-if="!thread" class="text-gray-600">未找到帖子</div>
      <div v-else class="overflow-hidden rounded-xl border border-gray-100 bg-white shadow-sm dark:bg-gray-800 dark:border-gray-700">
        <ThreadDetailHeader :thread="thread" :display-author-avatar-url="displayAuthorAvatarUrl" @back="safeBack" />
        <ThreadDetailSummary :thread-id="thread.id" />
        <ThreadDetailContent :thread="thread" />
        <ThreadDetailActions :thread="thread" @updated="updateThread" />
      </div>
    </div>
    <Comments v-if="thread?.id" :thread-id="Number(thread.id)" :scroll-to-post-id="anchorPostId" />
  </div>
</template>

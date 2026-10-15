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
  <div class="ui-page mx-auto max-w-4xl">
    <div v-if="loading" class="ui-panel ui-empty" role="status" aria-live="polite">
      <h1>正在加载...</h1>
      <div class="mx-auto mt-4 max-w-2xl space-y-3 text-left" aria-hidden="true">
        <div class="ui-skeleton h-6 w-3/4" />
        <div class="ui-skeleton h-4 w-full" />
        <div class="ui-skeleton h-4 w-5/6" />
      </div>
    </div>
    <div v-else-if="error" class="ui-error" role="alert">{{ error }}</div>
    <div v-else-if="!thread" class="ui-panel ui-empty">
      <h1>未找到帖子</h1>
    </div>
    <article v-else class="ui-panel overflow-hidden">
      <ThreadDetailHeader :thread="thread" :display-author-avatar-url="displayAuthorAvatarUrl" @back="safeBack" />
      <ThreadDetailSummary :thread-id="thread.id" />
      <ThreadDetailContent :thread="thread" />
      <ThreadDetailActions :thread="thread" @updated="updateThread" />
    </article>
    <Comments v-if="thread?.id" :thread-id="Number(thread.id)" :scroll-to-post-id="anchorPostId" />
  </div>
</template>

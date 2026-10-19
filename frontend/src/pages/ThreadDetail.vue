<script setup lang="ts">
import { ref, watch } from 'vue'
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
const commentsRef = ref<{ focusComposer: () => void } | null>(null)

function safeBack(): void {
  navigateBackSafely(router)
}

function viewComments(): void {
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches
  document.getElementById('thread-comments')?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
}

function writeReply(): void {
  commentsRef.value?.focusComposer()
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
  <div class="ui-page mx-auto max-w-4xl pb-16 lg:pb-0">
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
    <Comments
      v-if="thread?.id"
      ref="commentsRef"
      :thread-id="Number(thread.id)"
      :scroll-to-post-id="anchorPostId"
    />
    <nav v-if="thread?.id" class="mobile-thread-reply" aria-label="评论操作">
      <button type="button" @click="viewComments">查看评论</button>
      <button type="button" @click="writeReply">写回复</button>
    </nav>
  </div>
</template>

<style scoped>
.mobile-thread-reply {
  position: fixed;
  right: 0;
  bottom: calc(64px + env(safe-area-inset-bottom));
  left: 0;
  z-index: var(--ui-z-floating);
  display: flex;
  gap: 8px;
  height: 56px;
  padding: 4px 12px;
  border-top: 1px solid var(--ui-border);
  background: color-mix(in srgb, var(--ui-surface) 94%, transparent);
  backdrop-filter: blur(12px);
}

.mobile-thread-reply button {
  min-height: 48px;
  flex: 1;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: var(--ui-surface);
  color: var(--ui-ink);
  font-size: 0.875rem;
  font-weight: 600;
}

.mobile-thread-reply button:last-child {
  border-color: transparent;
  background: #1d4ed8;
  color: #fff;
}

.dark .mobile-thread-reply button:last-child {
  background: #4f46e5;
}

#thread-comments {
  scroll-margin-top: 80px;
}

@media (min-width: 1024px) {
  .mobile-thread-reply {
    display: none;
  }
}

</style>

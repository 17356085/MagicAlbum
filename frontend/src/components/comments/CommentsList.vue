<script setup lang="ts">
import { computed, ref, type PropType } from 'vue'
import { formatRelativeTime } from '@/composables/time'
import { normalizeImageUrl } from '@/utils/image'
import CommentBody from '@/components/comments/CommentBody.vue'
import CommentsComposer from '@/components/comments/CommentsComposer.vue'
import ReportDialog from '@/components/ReportDialog.vue'
import type { Id } from '@/types'
import type { CommentGroup, SortKey, SortOrder } from './types'

const props = defineProps({
  childPageSize: { type: Number, required: true },
  collapsedMap: { type: Object as PropType<Record<string, boolean>>, required: true },
  error: { type: String, required: true },
  getChildrenPage: { type: Function as PropType<(group: CommentGroup) => CommentGroup['items']>, required: true },
  groupPageMap: { type: Object as PropType<Record<string, number>>, required: true },
  hasPagination: { type: Boolean, required: true },
  highlightedPostId: { type: [String, Number] as PropType<Id | null>, default: null },
  isLoggedIn: { type: Boolean, required: true },
  isSubmitting: { type: Boolean, default: false },
  isUploading: { type: Boolean, default: false },
  loading: { type: Boolean, required: true },
  modelValue: { type: String, required: true },
  page: { type: Number, required: true },
  pageCount: { type: Number, required: true },
  pagedGroups: { type: Array as PropType<CommentGroup[]>, required: true },
  previewMode: { type: Boolean, required: true },
  replyToPostId: { type: [String, Number] as PropType<Id | null>, default: null },
  setGroupRef: { type: Function as PropType<(rootId: Id, el: Element | null) => void>, required: true },
  showRepliesMenu: { type: Boolean, required: true },
  showTimeMenu: { type: Boolean, required: true },
  sortKey: { type: String as PropType<SortKey>, required: true },
  sortOrder: { type: String as PropType<SortOrder>, required: true },
})

const emit = defineEmits<{
  'apply-page-input': []
  'cancel-reply': []
  'clamp-group-page': [rootId: Id, itemCount: number]
  'go-next-page': []
  'go-prev-page': []
  'next-group-page': [rootId: Id]
  'prev-group-page': [rootId: Id]
  'reply': [id: Id, rootId?: Id]
  'select-likes-order': [order: SortOrder]
  'select-replies-order': [order: SortOrder]
  'select-time-order': [order: SortOrder]
  'submit': []
  'toggle-collapse': [rootId: Id]
  'toggle-like': [comment: CommentGroup['root']]
  'update-group-page-input': [rootId: Id, event: Event]
  'update:modelValue': [value: string]
  'update:page-input': [event: Event]
  'update:previewMode': [value: boolean]
  'update:showRepliesMenu': [value: boolean]
  'update:showTimeMenu': [value: boolean]
  'upload-image': [event: Event]
}>()

const composerWidthClass = computed(() => 'w-full max-w-3xl')
const isHighlighted = (id: Id | null | undefined) => String(props.highlightedPostId || '') === String(id || '')
const reportDialogOpen = ref(false)
const reportTargetId = ref<number | null>(null)
const reportAuthMessage = ref('')

function openReportDialog(id: Id, isOptimistic = false): void {
  if (isOptimistic) return
  if (!props.isLoggedIn) {
    reportAuthMessage.value = '请先登录后再举报'
    return
  }
  const targetId = Number(id)
  if (!Number.isSafeInteger(targetId) || targetId <= 0) {
    reportAuthMessage.value = '举报目标无效，请刷新页面后重试'
    return
  }
  reportAuthMessage.value = ''
  reportTargetId.value = targetId
  reportDialogOpen.value = true
}

function updateReportDialogVisibility(open: boolean): void {
  reportDialogOpen.value = open
  if (!open) reportTargetId.value = null
}
</script>

<template>
  <div>
    <div class="ui-toolbar">
      <h2 id="comments-heading" class="text-base font-semibold text-[var(--ui-ink)]">评论</h2>
      <span v-if="reportAuthMessage" class="ml-auto text-xs font-normal text-amber-700 dark:text-amber-300" role="alert">{{ reportAuthMessage }}</span>
      <div class="ml-auto flex flex-wrap items-center gap-2 text-xs">
        <span class="ui-muted">排序：</span>
        <div class="relative">
          <button
            type="button"
            class="ui-button min-h-11 px-3 py-2 text-xs"
            :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey === 'time' }"
            @click="emit('update:showTimeMenu', !showTimeMenu)"
          >
            按时间 <span v-if="sortKey === 'time'">（{{ sortOrder === 'asc' ? '升序' : '降序' }}）</span>
          </button>
          <div v-if="showTimeMenu" class="ui-panel absolute right-0 z-10 mt-1 w-24 overflow-hidden text-[var(--ui-ink)]">
            <button type="button" class="block min-h-11 w-full px-3 py-2 text-left text-xs hover:bg-[var(--ui-subtle)]" @click="emit('select-time-order', 'asc')">升序</button>
            <button type="button" class="block min-h-11 w-full px-3 py-2 text-left text-xs hover:bg-[var(--ui-subtle)]" @click="emit('select-time-order', 'desc')">降序</button>
          </div>
        </div>
        <div class="relative">
          <button
            type="button"
            class="ui-button min-h-11 px-3 py-2 text-xs"
            :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey === 'replies' }"
            @click="emit('update:showRepliesMenu', !showRepliesMenu)"
          >
            按回复数 <span v-if="sortKey === 'replies'">（{{ sortOrder === 'asc' ? '升序' : '降序' }}）</span>
          </button>
          <div v-if="showRepliesMenu" class="ui-panel absolute right-0 z-10 mt-1 w-24 overflow-hidden text-[var(--ui-ink)]">
            <button type="button" class="block min-h-11 w-full px-3 py-2 text-left text-xs hover:bg-[var(--ui-subtle)]" @click="emit('select-replies-order', 'asc')">升序</button>
            <button type="button" class="block min-h-11 w-full px-3 py-2 text-left text-xs hover:bg-[var(--ui-subtle)]" @click="emit('select-replies-order', 'desc')">降序</button>
          </div>
        </div>
        <button
          type="button"
          class="ui-button min-h-11 px-3 py-2 text-xs"
          :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey === 'likes' }"
          @click="emit('select-likes-order', 'desc')"
        >
          最热
        </button>
      </div>
    </div>

    <div v-if="loading" class="ui-empty" role="status" aria-live="polite">
      <h3>正在加载...</h3>
      <div class="mx-auto mt-4 max-w-2xl space-y-3 text-left" aria-hidden="true">
        <div class="ui-skeleton h-4 w-2/3" />
        <div class="ui-skeleton h-4 w-full" />
      </div>
    </div>
    <div v-else-if="error" class="ui-error m-4" role="alert">{{ error }}</div>
    <div v-else-if="pagedGroups.length === 0" class="ui-empty">
      <h3>暂无评论</h3>
      <p>成为第一个参与讨论的人。</p>
    </div>
    <div v-else>
      <ul>
        <li
          v-for="group in pagedGroups"
          :key="group.root.id"
          class="ui-list-row"
          :class="isHighlighted(group.root.id) ? 'ring-2 ring-brandDay-400 bg-brandDay-50/70 dark:ring-brandNight-400 dark:bg-brandNight-900/20' : ''"
          :ref="(el) => setGroupRef(group.root.id, el as Element | null)"
        >
          <div :id="`post-${group.root.id}`">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <router-link :to="group.root.authorId ? (`/users/${group.root.authorId}`) : '/users'" class="flex min-w-0 items-center gap-2 text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
                <img
                  :src="group.root.authorAvatarUrl ? normalizeImageUrl(group.root.authorAvatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${group.root.authorNickname || group.root.authorUsername || 'U'}`"
                  alt="头像"
                  class="h-7 w-7 rounded-full bg-[var(--ui-subtle)] object-cover"
                  loading="lazy"
                />
                <div class="truncate text-xs">{{ group.root.authorNickname || group.root.authorUsername }}</div>
              </router-link>
              <div class="flex items-center gap-2 text-xs">
                <span v-if="isHighlighted(group.root.id)" class="ui-tag !bg-[var(--ui-accent-soft)] !text-[var(--ui-accent)]">当前定位</span>
                <span class="ui-muted">{{ group.root.floorLabel }} · {{ formatRelativeTime(group.root.createdAt) }}</span>
              </div>
            </div>
            <CommentBody :content="group.root.content" />
            <div class="mt-3 flex flex-wrap items-center gap-2 text-xs">
              <button
                type="button"
                class="ui-button min-h-11 px-3 py-2 text-xs"
                :class="{ 'text-rose-600 dark:text-rose-300': group.root.liked }"
                :disabled="Boolean(group.root._optimistic)"
                @click="emit('toggle-like', group.root)"
              >
                {{ group.root.liked ? '♥ 已赞' : '♡ 点赞' }} {{ Number(group.root.likeCount || 0) }}
              </button>
              <button type="button" class="ui-button min-h-11 px-3 py-2 text-xs" :disabled="isSubmitting || isUploading || Boolean(group.root._optimistic)" @click="emit('reply', group.root.id)">回复</button>
              <button
                v-if="!group.root._optimistic"
                type="button"
                class="ui-button min-h-11 px-3 py-2 text-xs text-[var(--ui-muted)] hover:border-red-200 hover:text-red-600 dark:hover:border-red-800 dark:hover:text-red-300"
                @click="openReportDialog(group.root.id, Boolean(group.root._optimistic))"
              >
                举报
              </button>
              <button v-if="group.items.length" type="button" class="ui-button min-h-11 px-3 py-2 text-xs" @click="emit('toggle-collapse', group.root.id)">
                {{ collapsedMap[group.root.id] ? `展开回复(${group.items.length})` : `折叠回复(${group.items.length})` }}
              </button>
            </div>
            <div v-if="replyToPostId === group.root.id" class="mt-2">
              <CommentsComposer
                :is-logged-in="isLoggedIn"
                :is-submitting="isSubmitting"
                :is-uploading="isUploading"
                :model-value="modelValue"
                :preview-mode="previewMode"
                :reply-to-post-id="replyToPostId"
                :width-class="composerWidthClass"
                @cancel-reply="emit('cancel-reply')"
                @submit="emit('submit')"
                @update:model-value="emit('update:modelValue', $event)"
                @update:preview-mode="emit('update:previewMode', $event)"
                @upload-image="emit('upload-image', $event)"
              />
            </div>
          </div>

          <div v-if="group.items.length && !collapsedMap[group.root.id]" class="mt-4 space-y-3 border-l border-[var(--ui-border)] pl-4">
            <div
              v-for="comment in getChildrenPage(group)"
              :key="comment.id"
              :id="`post-${comment.id}`"
              class="py-2"
              :class="isHighlighted(comment.id) ? 'ring-2 ring-brandDay-400 bg-brandDay-50/80 dark:ring-brandNight-400 dark:bg-brandNight-900/20' : ''"
              :style="{ paddingLeft: '24px' }"
            >
              <div class="flex flex-wrap items-center justify-between gap-2">
                <router-link :to="comment.authorId ? (`/users/${comment.authorId}`) : '/users'" class="flex min-w-0 items-center gap-2 text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
                  <img
                    :src="comment.authorAvatarUrl ? normalizeImageUrl(comment.authorAvatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${comment.authorNickname || comment.authorUsername || 'U'}`"
                    alt="头像"
                    class="h-6 w-6 rounded-full bg-[var(--ui-subtle)] object-cover"
                    loading="lazy"
                  />
                  <div class="truncate text-xs">{{ comment.authorNickname || comment.authorUsername }}</div>
                </router-link>
                <div class="flex items-center gap-2 text-xs">
                  <span v-if="isHighlighted(comment.id)" class="ui-tag !bg-[var(--ui-accent-soft)] !text-[var(--ui-accent)]">当前定位</span>
                  <span class="ui-muted">{{ formatRelativeTime(comment.createdAt) }}</span>
                </div>
              </div>
              <div v-if="comment.replyToPostId" class="ui-muted mt-2 text-xs">
                回复 <a :href="`#post-${comment.replyToPostId}`" class="text-[var(--ui-accent)] hover:underline">@{{ comment.parentAuthorNickname || comment.parentAuthorUsername }}</a>
              </div>
              <CommentBody :content="comment.content" />
              <div class="mt-3 flex flex-wrap items-center gap-2 text-xs">
                <button
                  type="button"
                  class="ui-button min-h-11 px-3 py-2 text-xs"
                  :class="{ 'text-rose-600 dark:text-rose-300': comment.liked }"
                  :disabled="Boolean(comment._optimistic)"
                  @click="emit('toggle-like', comment)"
                >
                  {{ comment.liked ? '♥ 已赞' : '♡ 点赞' }} {{ Number(comment.likeCount || 0) }}
                </button>
                <button type="button" class="ui-button min-h-11 px-3 py-2 text-xs" :disabled="isSubmitting || isUploading || Boolean(comment._optimistic)" @click="emit('reply', comment.id, group.root.id)">回复</button>
                <button
                  v-if="!comment._optimistic"
                  type="button"
                  class="ui-button min-h-11 px-3 py-2 text-xs text-[var(--ui-muted)] hover:border-red-200 hover:text-red-600 dark:hover:border-red-800 dark:hover:text-red-300"
                  @click="openReportDialog(comment.id, Boolean(comment._optimistic))"
                >
                  举报
                </button>
              </div>
              <div v-if="replyToPostId === comment.id" class="mt-2">
                <CommentsComposer
                  :is-logged-in="isLoggedIn"
                  :is-submitting="isSubmitting"
                  :is-uploading="isUploading"
                  :model-value="modelValue"
                  :preview-mode="previewMode"
                  :reply-to-post-id="replyToPostId"
                  :width-class="composerWidthClass"
                  @cancel-reply="emit('cancel-reply')"
                  @submit="emit('submit')"
                  @update:model-value="emit('update:modelValue', $event)"
                  @update:preview-mode="emit('update:previewMode', $event)"
                  @upload-image="emit('upload-image', $event)"
                />
              </div>
            </div>
            <div v-if="group.items.length > childPageSize" class="mt-3 flex flex-wrap items-center justify-end gap-2 text-xs">
              <button
                type="button"
                class="ui-button min-h-11 px-3 py-2 text-xs disabled:opacity-50"
                :disabled="(groupPageMap[group.root.id] || 1) <= 1"
                @click="emit('prev-group-page', group.root.id)"
              >
                上一页
              </button>
              <span class="ui-muted text-sm">第</span>
              <input
                type="text"
                aria-label="回复页码"
                class="ui-field min-h-11 w-16 py-1 text-center text-sm"
                inputmode="numeric"
                :value="groupPageMap[group.root.id] || 1"
                @input="emit('update-group-page-input', group.root.id, $event)"
                @keyup.enter="emit('clamp-group-page', group.root.id, group.items.length)"
                @blur="emit('clamp-group-page', group.root.id, group.items.length)"
              />
              <span class="ui-muted text-sm">/ {{ Math.max(1, Math.ceil(group.items.length / childPageSize)) }} 页</span>
              <button
                type="button"
                class="ui-button min-h-11 px-3 py-2 text-xs disabled:opacity-50"
                :disabled="(groupPageMap[group.root.id] || 1) >= Math.ceil(group.items.length / childPageSize)"
                @click="emit('next-group-page', group.root.id)"
              >
                下一页
              </button>
            </div>
          </div>
        </li>
      </ul>

      <div v-if="hasPagination" class="flex flex-wrap items-center justify-end gap-2 border-t border-[var(--ui-border)] px-4 py-4 sm:px-6">
        <button type="button" class="ui-button min-h-11 px-3 py-2 text-xs disabled:opacity-50" :disabled="page <= 1" @click="emit('go-prev-page')">上一页</button>
        <span class="ui-muted text-sm">第</span>
        <input
          type="text"
          aria-label="评论页码"
          class="ui-field min-h-11 w-16 py-1 text-center text-sm"
          inputmode="numeric"
          :value="page"
          @input="emit('update:page-input', $event)"
          @keyup.enter="emit('apply-page-input')"
          @blur="emit('apply-page-input')"
        />
        <span class="ui-muted text-sm">/ {{ pageCount }} 页</span>
        <button type="button" class="ui-button min-h-11 px-3 py-2 text-xs disabled:opacity-50" :disabled="page >= pageCount" @click="emit('go-next-page')">下一页</button>
      </div>
    </div>
    <ReportDialog
      v-if="reportDialogOpen && reportTargetId != null"
      :model-value="reportDialogOpen"
      target-type="POST"
      :target-id="reportTargetId"
      :is-logged-in="isLoggedIn"
      @update:model-value="updateReportDialogVisibility"
    />
  </div>
</template>

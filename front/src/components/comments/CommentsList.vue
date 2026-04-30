<script setup lang="ts">
import { computed, type PropType } from 'vue'
import { formatRelativeTime } from '@/composables/time'
import { normalizeImageUrl } from '@/utils/image'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'
import CommentsComposer from '@/components/comments/CommentsComposer.vue'
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

const markdownRenderer = createMarkdownRenderer({ katex: true, normalizeImages: true })
const renderCommentMarkdownHtml = (text: string | undefined) => renderMarkdown(markdownRenderer, text)
const composerWidthClass = computed(() => 'w-[720px]')
const isHighlighted = (id: Id | null | undefined) => String(props.highlightedPostId || '') === String(id || '')
</script>

<template>
  <div>
    <div class="mb-2 flex items-center justify-between text-sm font-medium">
      <span>评论</span>
      <div class="flex items-center gap-2 text-xs">
        <label>排序：</label>
        <div class="relative">
          <button
            class="rounded border px-2 py-1 hover:bg-gray-100 dark:bg-gray-800 dark:border-gray-700 dark:hover:bg-gray-700"
            :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey === 'time' }"
            @click="emit('update:showTimeMenu', !showTimeMenu)"
          >
            按时间 <span v-if="sortKey === 'time'">（{{ sortOrder === 'asc' ? '升序' : '降序' }}）</span>
          </button>
          <div v-if="showTimeMenu" class="absolute right-0 z-10 mt-1 w-24 rounded border bg-white text-gray-700 shadow-md dark:bg-gray-800 dark:text-gray-100 dark:border-gray-700">
            <button class="block w-full px-2 py-1 text-left hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('select-time-order', 'asc')">升序</button>
            <button class="block w-full px-2 py-1 text-left hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('select-time-order', 'desc')">降序</button>
          </div>
        </div>
        <div class="relative">
          <button
            class="rounded border px-2 py-1 hover:bg-gray-100 dark:bg-gray-800 dark:border-gray-700 dark:hover:bg-gray-700"
            :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey === 'replies' }"
            @click="emit('update:showRepliesMenu', !showRepliesMenu)"
          >
            按回复数 <span v-if="sortKey === 'replies'">（{{ sortOrder === 'asc' ? '升序' : '降序' }}）</span>
          </button>
          <div v-if="showRepliesMenu" class="absolute right-0 z-10 mt-1 w-24 rounded border bg-white text-gray-700 shadow-md dark:bg-gray-800 dark:text-gray-100 dark:border-gray-700">
            <button class="block w-full px-2 py-1 text-left hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('select-replies-order', 'asc')">升序</button>
            <button class="block w-full px-2 py-1 text-left hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('select-replies-order', 'desc')">降序</button>
          </div>
        </div>
        <button
          class="rounded border px-2 py-1 hover:bg-gray-100 dark:bg-gray-800 dark:border-gray-700 dark:hover:bg-gray-700"
          :class="{ 'bg-gray-100 dark:bg-gray-700': sortKey === 'likes' }"
          @click="emit('select-likes-order', 'desc')"
        >
          最热
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
    <div v-else>
      <div v-if="error" class="mb-3 text-red-600">{{ error }}</div>
      <ul class="space-y-3">
        <li
          v-for="group in pagedGroups"
          :key="group.root.id"
          class="rounded-md border border-gray-200 bg-white p-3 transition-colors dark:bg-gray-800 dark:border-gray-700"
          :class="isHighlighted(group.root.id) ? 'ring-2 ring-brandDay-400 bg-brandDay-50/70 dark:ring-brandNight-400 dark:bg-brandNight-900/20' : ''"
          :ref="(el) => setGroupRef(group.root.id, el as Element | null)"
        >
          <div :id="`post-${group.root.id}`">
            <div class="flex items-center justify-between">
              <router-link :to="group.root.authorId ? (`/users/${group.root.authorId}`) : '/users'" class="flex items-center gap-2 hover:opacity-90">
                <img
                  :src="group.root.authorAvatarUrl ? normalizeImageUrl(group.root.authorAvatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${group.root.authorNickname || group.root.authorUsername || 'U'}`"
                  alt="头像"
                  class="h-7 w-7 rounded-full bg-gray-100 object-cover dark:bg-gray-700"
                  loading="lazy"
                />
                <div class="text-xs text-gray-600 dark:text-gray-300">{{ group.root.authorNickname || group.root.authorUsername }}</div>
              </router-link>
              <div class="flex items-center gap-2 text-xs">
                <span v-if="isHighlighted(group.root.id)" class="rounded-full bg-brandDay-100 px-2 py-0.5 font-medium text-brandDay-700 dark:bg-brandNight-900/50 dark:text-brandNight-200">当前定位</span>
                <span class="text-gray-400">{{ group.root.floorLabel }} · {{ formatRelativeTime(group.root.createdAt) }}</span>
              </div>
            </div>
            <div class="prose mt-2 max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(group.root.content)" />
            <div class="mt-2 flex items-center gap-2 text-xs">
              <button
                class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
                :class="{ 'text-rose-600 dark:text-rose-300': group.root.liked }"
                @click="emit('toggle-like', group.root)"
              >
                {{ group.root.liked ? '♥ 已赞' : '♡ 点赞' }} {{ Number(group.root.likeCount || 0) }}
              </button>
              <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('reply', group.root.id)">回复</button>
              <button v-if="group.items.length" class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('toggle-collapse', group.root.id)">
                {{ collapsedMap[group.root.id] ? `展开回复(${group.items.length})` : `折叠回复(${group.items.length})` }}
              </button>
            </div>
            <div v-if="replyToPostId === group.root.id" class="mt-2">
              <CommentsComposer
                :is-logged-in="isLoggedIn"
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

          <div v-if="group.items.length && !collapsedMap[group.root.id]" class="mt-3 space-y-2 rounded-sm border-l-2 border-gray-200 bg-gray-50 pl-3 dark:border-gray-700 dark:bg-gray-800/40">
            <div
              v-for="comment in getChildrenPage(group)"
              :key="comment.id"
              :id="`post-${comment.id}`"
              class="rounded-sm p-2 transition-colors"
              :class="isHighlighted(comment.id) ? 'ring-2 ring-brandDay-400 bg-brandDay-50/80 dark:ring-brandNight-400 dark:bg-brandNight-900/20' : ''"
              :style="{ paddingLeft: '24px' }"
            >
              <div class="flex items-center justify-between">
                <router-link :to="comment.authorId ? (`/users/${comment.authorId}`) : '/users'" class="flex items-center gap-2 hover:opacity-90">
                  <img
                    :src="comment.authorAvatarUrl ? normalizeImageUrl(comment.authorAvatarUrl) : `https://api.dicebear.com/7.x/initials/svg?seed=${comment.authorNickname || comment.authorUsername || 'U'}`"
                    alt="头像"
                    class="h-6 w-6 rounded-full bg-gray-100 object-cover dark:bg-gray-700"
                    loading="lazy"
                  />
                  <div class="text-xs text-gray-600 dark:text-gray-300">{{ comment.authorNickname || comment.authorUsername }}</div>
                </router-link>
                <div class="flex items-center gap-2 text-xs">
                  <span v-if="isHighlighted(comment.id)" class="rounded-full bg-brandDay-100 px-2 py-0.5 font-medium text-brandDay-700 dark:bg-brandNight-900/50 dark:text-brandNight-200">当前定位</span>
                  <span class="text-gray-400">{{ formatRelativeTime(comment.createdAt) }}</span>
                </div>
              </div>
              <div v-if="comment.replyToPostId" class="mt-1 text-xs text-gray-600 dark:text-gray-300">
                回复 <a :href="`#post-${comment.replyToPostId}`" class="text-brandDay-600 hover:underline dark:text-brandNight-400">@{{ comment.parentAuthorNickname || comment.parentAuthorUsername }}</a>
              </div>
              <div class="prose mt-2 max-w-none dark:prose-invert" v-html="renderCommentMarkdownHtml(comment.content)" />
              <div class="mt-2 flex items-center gap-2 text-xs">
                <button
                  class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700"
                  :class="{ 'text-rose-600 dark:text-rose-300': comment.liked }"
                  @click="emit('toggle-like', comment)"
                >
                  {{ comment.liked ? '♥ 已赞' : '♡ 点赞' }} {{ Number(comment.likeCount || 0) }}
                </button>
                <button class="rounded px-2 py-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="emit('reply', comment.id, group.root.id)">回复</button>
              </div>
              <div v-if="replyToPostId === comment.id" class="mt-2">
                <CommentsComposer
                  :is-logged-in="isLoggedIn"
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
            <div v-if="group.items.length > childPageSize" class="mt-2 flex items-center justify-end gap-2 text-xs">
              <button
                class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
                :disabled="(groupPageMap[group.root.id] || 1) <= 1"
                @click="emit('prev-group-page', group.root.id)"
              >
                上一页
              </button>
              <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
              <input
                type="text"
                class="w-16 rounded border bg-white px-2 py-1 text-center text-sm focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:placeholder-gray-400 dark:focus:ring-accentCyan-400"
                :value="groupPageMap[group.root.id] || 1"
                @input="emit('update-group-page-input', group.root.id, $event)"
                @keyup.enter="emit('clamp-group-page', group.root.id, group.items.length)"
                @blur="emit('clamp-group-page', group.root.id, group.items.length)"
              />
              <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ Math.max(1, Math.ceil(group.items.length / childPageSize)) }} 页</span>
              <button
                class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
                :disabled="(groupPageMap[group.root.id] || 1) >= Math.ceil(group.items.length / childPageSize)"
                @click="emit('next-group-page', group.root.id)"
              >
                下一页
              </button>
            </div>
          </div>
        </li>
      </ul>

      <div v-if="hasPagination" class="mt-4 flex items-center justify-end gap-2">
        <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="page <= 1" @click="emit('go-prev-page')">上一页</button>
        <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
        <input
          type="text"
          class="w-16 rounded border bg-white px-2 py-1 text-center text-sm focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:placeholder-gray-400 dark:focus:ring-accentCyan-400"
          :value="page"
          @input="emit('update:page-input', $event)"
          @keyup.enter="emit('apply-page-input')"
          @blur="emit('apply-page-input')"
        />
        <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ pageCount }} 页</span>
        <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="page >= pageCount" @click="emit('go-next-page')">下一页</button>
      </div>
    </div>
  </div>
</template>

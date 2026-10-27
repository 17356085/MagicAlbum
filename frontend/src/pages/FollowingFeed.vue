<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { listFollowingFeed } from '@/api/followingFeed'
import { listUserFollowing, listUserThreads } from '@/api/users'
import { getStoredAccessToken } from '@/utils/authStorage'
import { normalizeImageUrl } from '@/utils/image'
import type { FollowingFeedItem } from '@/api/followingFeed'
import type { Id, PageResult, User } from '@/types'

const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)

const page = ref(1)
const size = ref(10)
const loading = ref(false)
const error = ref('')
const feed = ref<PageResult<FollowingFeedItem>>({ items: [], page: 1, size: 10, total: 0 })
const followingUsers = ref<User[]>([])
const selectedUserId = ref<'all' | string>('all')
const channelScroller = ref<HTMLElement | null>(null)
const inputPage = ref('1')

const currentUserId = computed(() => user.value?.id ?? user.value?.userId ?? null)
const totalPages = computed(() => Math.max(1, Math.ceil(Number(feed.value.total || 0) / Number(size.value || 10))))
const visibleItems = computed(() => feed.value.items || [])
const channelUsers = computed<User[]>(() => followingUsers.value)

const selectedUser = computed(() => {
  if (selectedUserId.value === 'all') return null
  return followingUsers.value.find((u) => String(u.id) === selectedUserId.value) || null
})
const emptyText = computed(() => selectedUserId.value === 'all' ? '暂无动态，关注一些用户后这里会显示他们发布的帖子。' : '该用户暂未发布帖子')
interface FeedRequestContext {
  token: string
  epoch: number
  sequence: number
  accountId: Id | null
}

let sessionEpoch = 0
let loadRequestSeq = 0
let reloadQueued = false
let disposed = false

function createRequestContext(): FeedRequestContext {
  return {
    token: authStore.token,
    epoch: sessionEpoch,
    sequence: ++loadRequestSeq,
    accountId: currentUserId.value,
  }
}

function isCurrentRequest(request: FeedRequestContext): boolean {
  return !disposed
    && request.epoch === sessionEpoch
    && request.sequence === loadRequestSeq
    && request.token === authStore.token
    && getStoredAccessToken() === request.token
    && isLoggedIn.value
}

function queueReload(): void {
  if (disposed || reloadQueued) return
  reloadQueued = true
  queueMicrotask(() => {
    reloadQueued = false
    if (disposed) return
    void load()
  })
}

function resetAndQueueReload(): void {
  if (disposed) return
  sessionEpoch += 1
  loadRequestSeq += 1
  resetFeedState()
  loading.value = false
  queueReload()
}

async function resolveCurrentUserId(request: FeedRequestContext): Promise<Id | null> {
  if (!isCurrentRequest(request)) return null
  const knownId = currentUserId.value
  if (knownId !== null && knownId !== undefined) {
    request.accountId = knownId
    return knownId
  }
  try {
    const refreshed = await authStore.refreshCurrentUser()
    if (!isCurrentRequest(request) || !refreshed) return null
    const hydratedId = currentUserId.value
    if (hydratedId === null || hydratedId === undefined) return null
    request.accountId = hydratedId
    return hydratedId
  } catch (_) {
    return null
  }
}

function userDisplayName(u: User): string {
  return u.nickname || u.username
}

function displayNameOf(item: FollowingFeedItem): string {
  return item.authorNickname || item.authorUsername || '用户'
}

function avatarUrl(name: string, raw?: string | null): string {
  return normalizeImageUrl(raw || `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(name || 'U')}`)
}

function avatarOf(item: FollowingFeedItem): string {
  return avatarUrl(displayNameOf(item), item.authorAvatarUrl)
}

function excerpt(item: FollowingFeedItem): string {
  const text = String(item.summary || item.content || item.contentMd || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^)]+\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/[#>*_`~\-[\]]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (!text) return ''
  return text.length > 220 ? `${text.slice(0, 220)}...` : text
}

function imageUrls(item: FollowingFeedItem): string[] {
  const text = String(item.content || item.contentMd || '')
  const urls: string[] = []
  const markdown = /!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g
  const html = /<img[^>]+src=["']([^"']+)["']/gi
  let match: RegExpExecArray | null
  while ((match = markdown.exec(text)) && urls.length < 3) {
    if (match[1]) urls.push(normalizeImageUrl(match[1]))
  }
  while ((match = html.exec(text)) && urls.length < 3) {
    if (match[1]) urls.push(normalizeImageUrl(match[1]))
  }
  return urls
}

function firstImageUrl(item: FollowingFeedItem): string {
  return imageUrls(item)[0] || ''
}

function formattedTime(item: FollowingFeedItem): string {
  const raw = item.createdAt || item.updatedAt || ''
  if (!raw) return ''
  return new Date(raw).toLocaleString()
}

function resetFeedState(): void {
  page.value = 1
  inputPage.value = '1'
  error.value = ''
  selectedUserId.value = 'all'
  followingUsers.value = []
  feed.value = { items: [], page: 1, size: size.value, total: 0 }
}

async function load(): Promise<void> {
  const request = createRequestContext()
  if (!isCurrentRequest(request)) {
    if (!isLoggedIn.value) resetFeedState()
    return
  }
  loading.value = true
  error.value = ''
  try {
    const userId = await resolveCurrentUserId(request)
    if (!isCurrentRequest(request)) return
    if (userId === null || userId === undefined) {
      resetFeedState()
      error.value = '账号信息加载失败，请重试'
      return
    }
    const followingPage = await listUserFollowing(userId, { page: 1, size: 12 })
    if (!isCurrentRequest(request)) return
    followingUsers.value = followingPage.items || []
    if (selectedUserId.value !== 'all' && !followingUsers.value.some((u) => String(u.id) === selectedUserId.value)) {
      selectedUserId.value = 'all'
    }
    await loadFeed(request)
  } catch (_) {
    if (isCurrentRequest(request)) {
      error.value = '加载动态失败'
    }
  } finally {
    if (isCurrentRequest(request)) {
      loading.value = false
    }
  }
}

async function loadFeed(request: FeedRequestContext): Promise<void> {
  const userId = request.accountId ?? await resolveCurrentUserId(request)
  if (!isCurrentRequest(request)) return
  if (userId === null || userId === undefined) {
    resetFeedState()
    error.value = '账号信息加载失败，请重试'
    return
  }
  request.accountId = userId
  if (selectedUserId.value === 'all') {
    if (!isCurrentRequest(request)) return
    const nextFeed = await listFollowingFeed({
      page: page.value,
      size: size.value,
    })
    if (isCurrentRequest(request)) {
      feed.value = nextFeed
    }
    return
  }

  const target = selectedUser.value
  if (!target) {
    feed.value = { items: [], page: page.value, size: size.value, total: 0 }
    return
  }
  if (!isCurrentRequest(request)) return
  const data = await listUserThreads(target.id, { page: page.value, size: size.value })
  if (!isCurrentRequest(request)) return
  feed.value = {
    items: data.items.map((thread) => ({
      ...thread,
      authorId: thread.authorId || target.id,
      authorUsername: thread.authorUsername || target.username,
      authorNickname: thread.authorNickname || target.nickname,
      authorAvatarUrl: thread.authorAvatarUrl || target.avatarUrl,
    })),
    page: data.page,
    size: data.size,
    total: data.total,
  }
}

async function selectUser(id: 'all' | string): Promise<void> {
  if (selectedUserId.value === id) return
  const request = createRequestContext()
  selectedUserId.value = id
  page.value = 1
  loading.value = true
  error.value = ''
  try {
    await loadFeed(request)
  } catch (_) {
    if (isCurrentRequest(request)) {
      error.value = '加载动态失败'
    }
  } finally {
    if (isCurrentRequest(request)) {
      loading.value = false
    }
  }
}

function setPage(nextPage: number): void {
  const target = Math.min(Math.max(1, nextPage), totalPages.value)
  if (target === page.value) return
  const request = createRequestContext()
  page.value = target
  loading.value = true
  error.value = ''
  loadFeed(request)
    .catch(() => {
      if (isCurrentRequest(request)) {
        error.value = '加载动态失败'
      }
    })
    .finally(() => {
      if (isCurrentRequest(request)) {
        loading.value = false
      }
    })
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
}

function scrollChannels(direction: 'left' | 'right'): void {
  const el = channelScroller.value
  if (!el) return
  el.scrollBy({ left: direction === 'left' ? -360 : 360, behavior: 'smooth' })
}

const stopAuthWatch = watch(
  [() => authStore.token, () => currentUserId.value, () => isLoggedIn.value],
  () => resetAndQueueReload(),
  { flush: 'sync' },
)

function onAuthStateChanged(): void {
  resetAndQueueReload()
}

onMounted(() => {
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  void load()
})
watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

onBeforeUnmount(() => {
  disposed = true
  sessionEpoch += 1
  loadRequestSeq += 1
  reloadQueued = false
  stopAuthWatch()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

<template>
  <section class="ui-page mx-auto max-w-[920px]">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">关注动态</h1>
        <p class="ui-description">查看你关注的成员最近发布的帖子。</p>
      </div>
      <router-link v-if="currentUserId" :to="`/users/${currentUserId}/following`" class="ui-button">全部关注</router-link>
    </header>

    <section class="ui-panel mb-3 overflow-hidden">
      <div class="flex flex-wrap items-center justify-between gap-2 px-4 pt-3 text-xs text-[var(--ui-muted)]">
        <span>全部动态包含所有关注用户的新帖；头像仅用于快捷筛选</span>
      </div>
      <div class="flex h-24 items-center gap-2 px-2 py-3">
        <button
          type="button"
          class="ui-button h-8 w-8 shrink-0 rounded-full p-0 text-[var(--ui-muted)]"
          @click="scrollChannels('left')"
          aria-label="向左查看更多关注用户"
        >
          ‹
        </button>

        <div ref="channelScroller" class="ui-scroll flex min-w-0 flex-1 items-center gap-5 overflow-x-auto px-1 py-1">
          <button
            type="button"
            class="flex h-[72px] w-[76px] shrink-0 flex-col items-center justify-start text-center"
            @click="selectUser('all')"
          >
            <div
              class="mx-auto flex h-12 w-12 shrink-0 items-center justify-center rounded-full border-2 bg-[var(--ui-surface)] text-[var(--ui-accent)]"
              style="width: 48px; height: 48px; max-width: 48px; max-height: 48px;"
              :class="selectedUserId === 'all' ? 'border-[var(--ui-accent)]' : 'border-[var(--ui-border)]'"
            >
              <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8">
                <path stroke-linecap="round" stroke-linejoin="round" d="M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4" />
              </svg>
            </div>
            <div
              class="mt-1.5 h-4 w-full truncate text-xs leading-4"
              :class="selectedUserId === 'all' ? 'font-medium text-[var(--ui-accent)]' : 'text-[var(--ui-muted)]'"
            >
              全部动态
            </div>
          </button>

          <button
            v-for="u in channelUsers"
            :key="u.id"
            type="button"
            class="flex h-[72px] w-[76px] shrink-0 flex-col items-center justify-start text-center"
            @click="selectUser(String(u.id))"
          >
            <div class="relative mx-auto h-12 w-12 shrink-0" style="width: 48px; height: 48px; max-width: 48px; max-height: 48px;">
              <img
                :src="avatarUrl(userDisplayName(u), u.avatarUrl)"
                alt="avatar"
                class="block h-12 w-12 rounded-full border-2 object-cover"
                style="width: 48px; height: 48px; max-width: 48px; max-height: 48px;"
                :class="selectedUserId === String(u.id) ? 'border-[var(--ui-accent)]' : 'border-transparent'"
              />
            </div>
            <div
              class="mt-1.5 h-4 w-full truncate text-xs leading-4"
              :class="selectedUserId === String(u.id) ? 'font-medium text-[var(--ui-accent)]' : 'text-[var(--ui-muted)]'"
            >
              {{ userDisplayName(u) }}
            </div>
          </button>
        </div>

        <button
          type="button"
          class="ui-button h-8 w-8 shrink-0 rounded-full p-0 text-[var(--ui-muted)]"
          @click="scrollChannels('right')"
          aria-label="向右查看更多关注用户"
        >
          ›
        </button>
      </div>
    </section>

    <div v-if="loading" class="ui-panel ui-empty" role="status">
      正在加载...
    </div>
    <div v-else-if="error" class="ui-error flex flex-wrap items-center justify-between gap-3" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="ui-button min-h-11" :disabled="loading" @click="load">重试</button>
    </div>
    <div v-else-if="visibleItems.length === 0" class="ui-panel ui-empty" role="status">
      <h2>暂无关注动态</h2>
      <p>{{ emptyText }}</p>
    </div>

    <div v-else class="ui-panel overflow-hidden">
      <ul>
        <li
          v-for="item in visibleItems"
          :key="item.id"
          class="ui-list-row"
        >
          <div class="block">
            <div class="flex flex-col items-start gap-5 md:flex-row">
              <router-link
                v-if="firstImageUrl(item)"
                :to="`/threads/${item.id}`"
                class="group/img relative h-48 w-full shrink-0 overflow-hidden rounded-lg border border-[var(--ui-border)] bg-[var(--ui-subtle)] md:h-40 md:w-64"
              >
                <img :src="firstImageUrl(item)" alt="" class="absolute inset-0 h-full w-full scale-110 object-cover opacity-50 blur-sm" />
                <img
                  :src="firstImageUrl(item)"
                  alt="封面"
                  loading="lazy"
                  class="relative z-10 h-full w-full object-contain transition-transform duration-500 group-hover:scale-105"
                  style="-webkit-mask-image: radial-gradient(circle, black 60%, transparent 100%); mask-image: radial-gradient(circle, black 60%, transparent 100%);"
                />
              </router-link>

              <div class="min-w-0 w-full flex-1">
                <router-link :to="`/threads/${item.id}`" class="mb-2.5 block line-clamp-2 text-lg font-bold text-[var(--ui-ink)] hover:text-[var(--ui-accent)]">
                  {{ item.title }}
                </router-link>
                <p class="line-clamp-3 text-sm leading-6 text-[var(--ui-muted)]">
                  {{ excerpt(item) || '暂无内容摘要' }}
                </p>

                <div class="mt-3 flex flex-wrap items-center gap-3 text-xs text-[var(--ui-muted)]">
                  <router-link :to="item.authorId ? (`/users/${item.authorId}`) : '/users'" class="flex items-center gap-1.5 hover:text-[var(--ui-ink)]">
                    <img :src="avatarOf(item)" class="h-5 w-5 rounded-full bg-[var(--ui-subtle)] object-cover" alt="" />
                    {{ displayNameOf(item) }}
                  </router-link>
                  <span>·</span>
                  <span v-if="item.sectionName || item.sectionId" class="ui-tag">
                    {{ item.sectionName || item.sectionId }}
                  </span>
                  <span v-if="item.sectionName || item.sectionId">·</span>
                  <span>{{ formattedTime(item) }}</span>
                  <span>·</span>
                  <span>回复 {{ Number(item.replyCount || 0) }}</span>
                  <span>点赞 {{ Number(item.likeCount || 0) }}</span>
                </div>
              </div>
            </div>
          </div>
        </li>
      </ul>
    </div>

    <div v-if="visibleItems.length > 0" class="ui-panel mt-3 flex flex-col items-start gap-2 px-3 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-5">
      <div class="text-xs text-[var(--ui-muted)]">共 {{ feed.total || 0 }} 条 · 每页 {{ size }} 条</div>
      <div class="flex w-full flex-wrap items-center gap-2 sm:w-auto">
        <button type="button" class="ui-button" :disabled="loading || page <= 1" @click="setPage(page - 1)">上一页</button>
        <span class="text-sm text-[var(--ui-muted)]">第</span>
        <input
          v-model="inputPage"
          class="ui-field w-16 text-center"
          aria-label="页码"
          inputmode="numeric"
          @keyup.enter="goToInputPage"
          @blur="goToInputPage"
        />
        <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
        <button type="button" class="ui-button" :disabled="loading || page >= totalPages" @click="setPage(page + 1)">下一页</button>
      </div>
    </div>
  </section>
</template>

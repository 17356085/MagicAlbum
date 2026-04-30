<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { listFollowingFeed } from '@/api/followingFeed'
import { getCurrentUser, listUserFollowing, listUserThreads } from '@/api/users'
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

const currentUserId = computed(() => user.value?.id || user.value?.userId || null)
const totalPages = computed(() => Math.max(1, Math.ceil(Number(feed.value.total || 0) / Number(size.value || 10))))
const visibleItems = computed(() => feed.value.items || [])
const channelUsers = computed<User[]>(() => followingUsers.value)

const selectedUser = computed(() => {
  if (selectedUserId.value === 'all') return null
  return followingUsers.value.find((u) => String(u.id) === selectedUserId.value) || null
})
const emptyText = computed(() => selectedUserId.value === 'all' ? '暂无动态，关注一些用户后这里会显示他们发布的帖子。' : '该用户暂未发布帖子')
let loadRequestSeq = 0

async function resolveCurrentUserId(): Promise<Id | null> {
  if (!isLoggedIn.value) return null
  if (currentUserId.value) return currentUserId.value
  try {
    const current = await getCurrentUser()
    const id = current?.id || current?.userId || null
    if (current && id) {
      authStore.updateCurrentUser(current)
      return id
    }
  } catch (_) {}
  return null
}

function userDisplayName(u: User): string {
  return u.nickname || u.username
}

function displayNameOf(item: FollowingFeedItem): string {
  return item.authorNickname || item.authorUsername || item.followedAuthor.nickname || item.followedAuthor.username
}

function avatarUrl(name: string, raw?: string | null): string {
  return normalizeImageUrl(raw || `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(name || 'U')}`)
}

function avatarOf(item: FollowingFeedItem): string {
  return avatarUrl(displayNameOf(item), item.authorAvatarUrl || item.followedAuthor.avatarUrl)
}

function excerpt(item: FollowingFeedItem): string {
  const text = String(item.summary || item.content || item.contentMd || '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/!\[[^\]]*\]\([^\)]+\)/g, '')
    .replace(/\[([^\]]+)\]\([^\)]+\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/[#>*_`~\-\[\]]/g, '')
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
  const requestSeq = ++loadRequestSeq
  const userId = await resolveCurrentUserId()
  if (requestSeq !== loadRequestSeq) return
  if (!userId) {
    resetFeedState()
    return
  }
  loading.value = true
  error.value = ''
  try {
    const followingPage = await listUserFollowing(userId, { page: 1, size: 12 })
    if (requestSeq !== loadRequestSeq) return
    followingUsers.value = followingPage.items || []
    if (selectedUserId.value !== 'all' && !followingUsers.value.some((u) => String(u.id) === selectedUserId.value)) {
      selectedUserId.value = 'all'
    }
    await loadFeed(requestSeq)
  } catch (_) {
    if (requestSeq === loadRequestSeq) {
      error.value = '加载动态失败'
    }
  } finally {
    if (requestSeq === loadRequestSeq) {
      loading.value = false
    }
  }
}

async function loadFeed(requestSeq = ++loadRequestSeq): Promise<void> {
  const userId = await resolveCurrentUserId()
  if (requestSeq !== loadRequestSeq) return
  if (!userId) {
    resetFeedState()
    return
  }
  if (selectedUserId.value === 'all') {
    const nextFeed = await listFollowingFeed(userId, {
      page: page.value,
      size: size.value,
      followingSize: 50,
      perUserSize: 12,
    })
    if (requestSeq === loadRequestSeq) {
      feed.value = nextFeed
    }
    return
  }

  const target = selectedUser.value
  if (!target) {
    feed.value = { items: [], page: page.value, size: size.value, total: 0 }
    return
  }
  const data = await listUserThreads(target.id, { page: page.value, size: size.value })
  if (requestSeq !== loadRequestSeq) return
  const normalized = Array.isArray(data)
    ? { items: data, page: page.value, size: size.value, total: data.length }
    : (data || { items: [], page: page.value, size: size.value, total: 0 })
  feed.value = {
    items: (normalized.items || []).map((thread) => ({
      ...thread,
      authorId: thread.authorId || target.id,
      authorUsername: thread.authorUsername || target.username,
      authorNickname: thread.authorNickname || target.nickname,
      authorAvatarUrl: thread.authorAvatarUrl || target.avatarUrl,
      followedAuthor: target,
    })),
    page: Number(normalized.page || page.value),
    size: Number(normalized.size || size.value),
    total: Number(normalized.total || 0),
  }
}

async function selectUser(id: 'all' | string): Promise<void> {
  if (selectedUserId.value === id) return
  const requestSeq = ++loadRequestSeq
  selectedUserId.value = id
  page.value = 1
  loading.value = true
  error.value = ''
  try {
    await loadFeed(requestSeq)
  } catch (_) {
    if (requestSeq === loadRequestSeq) {
      error.value = '加载动态失败'
    }
  } finally {
    if (requestSeq === loadRequestSeq) {
      loading.value = false
    }
  }
}

function setPage(nextPage: number): void {
  const target = Math.min(Math.max(1, nextPage), totalPages.value)
  if (target === page.value) return
  const requestSeq = ++loadRequestSeq
  page.value = target
  loading.value = true
  error.value = ''
  loadFeed(requestSeq)
    .catch(() => {
      if (requestSeq === loadRequestSeq) {
        error.value = '加载动态失败'
      }
    })
    .finally(() => {
      if (requestSeq === loadRequestSeq) {
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

onMounted(load)
watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

watch([currentUserId, isLoggedIn], () => {
  loadRequestSeq += 1
  loading.value = false
  resetFeedState()
  load()
})
</script>

<template>
  <div class="mx-auto max-w-[920px] space-y-3">
    <section class="overflow-hidden rounded-lg bg-white shadow-sm dark:bg-gray-800">
      <div class="flex h-24 items-center gap-2 px-2 py-3">
        <button
          type="button"
          class="flex size-8 shrink-0 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-50 hover:text-brandDay-600 dark:hover:bg-gray-700 dark:hover:text-brandNight-300"
          @click="scrollChannels('left')"
          aria-label="向左查看更多关注用户"
        >
          ‹
        </button>

        <div ref="channelScroller" class="scrollbar-none flex min-w-0 flex-1 items-center gap-5 overflow-x-auto px-1 py-1">
          <button
            type="button"
            class="flex h-[72px] w-[76px] shrink-0 flex-col items-center justify-start text-center"
            @click="selectUser('all')"
          >
            <div
              class="mx-auto flex h-12 w-12 shrink-0 items-center justify-center rounded-full border-2 bg-white text-brandDay-500 dark:bg-gray-800 dark:text-brandNight-300"
              style="width: 48px; height: 48px; max-width: 48px; max-height: 48px;"
              :class="selectedUserId === 'all' ? 'border-brandDay-500 dark:border-brandNight-400' : 'border-gray-200 dark:border-gray-700'"
            >
              <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8">
                <path stroke-linecap="round" stroke-linejoin="round" d="M12 3v18M3 12h18M5.6 5.6l12.8 12.8M18.4 5.6 5.6 18.4" />
              </svg>
            </div>
            <div
              class="mt-1.5 h-4 w-full truncate text-xs leading-4"
              :class="selectedUserId === 'all' ? 'font-medium text-brandDay-600 dark:text-brandNight-300' : 'text-gray-600 dark:text-gray-300'"
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
                :class="selectedUserId === String(u.id) ? 'border-brandDay-500 dark:border-brandNight-400' : 'border-transparent'"
              />
              <span class="absolute bottom-0 right-0 h-3 w-3 rounded-full border-2 border-white bg-brandDay-500 dark:border-gray-800"></span>
            </div>
            <div
              class="mt-1.5 h-4 w-full truncate text-xs leading-4"
              :class="selectedUserId === String(u.id) ? 'font-medium text-brandDay-600 dark:text-brandNight-300' : 'text-gray-600 dark:text-gray-300'"
            >
              {{ userDisplayName(u) }}
            </div>
          </button>
        </div>

        <button
          type="button"
          class="flex size-8 shrink-0 items-center justify-center rounded-full text-gray-400 transition-colors hover:bg-gray-50 hover:text-brandDay-600 dark:hover:bg-gray-700 dark:hover:text-brandNight-300"
          @click="scrollChannels('right')"
          aria-label="向右查看更多关注用户"
        >
          ›
        </button>
      </div>
    </section>

    <div v-if="loading" class="rounded-lg bg-white py-12 text-center text-sm text-gray-500 shadow-sm dark:bg-gray-800">
      正在加载...
    </div>
    <div v-else-if="error" class="rounded-lg bg-white py-12 text-center text-sm text-red-500 shadow-sm dark:bg-gray-800">
      {{ error }}
    </div>
    <div v-else-if="visibleItems.length === 0" class="px-2 py-2 text-sm text-gray-500 dark:text-gray-400">
      {{ emptyText }}
    </div>

    <div v-else class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm dark:border-gray-700 dark:bg-gray-800">
      <ul class="space-y-4">
        <li
          v-for="item in visibleItems"
          :key="item.id"
          class="group rounded-lg border border-gray-100 bg-white p-4 transition-all hover:border-brandDay-200 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-brandNight-700"
        >
          <router-link :to="`/threads/${item.id}`" class="block">
            <div class="flex flex-col items-start gap-5 md:flex-row">
              <div
                v-if="firstImageUrl(item)"
                class="group/img relative h-48 w-full shrink-0 overflow-hidden rounded-lg border border-gray-100 bg-gray-50 dark:border-gray-700 dark:bg-gray-800 md:h-40 md:w-64"
              >
                <img :src="firstImageUrl(item)" alt="" class="absolute inset-0 h-full w-full scale-110 object-cover opacity-50 blur-sm" />
                <img
                  :src="firstImageUrl(item)"
                  alt="封面"
                  loading="lazy"
                  class="relative z-10 h-full w-full object-contain transition-transform duration-500 group-hover:scale-105"
                  style="-webkit-mask-image: radial-gradient(circle, black 60%, transparent 100%); mask-image: radial-gradient(circle, black 60%, transparent 100%);"
                />
              </div>

              <div class="min-w-0 w-full flex-1">
                <h2 class="mb-2.5 line-clamp-2 text-lg font-bold text-gray-800 transition-colors group-hover:text-brandDay-600 dark:text-gray-100 dark:group-hover:text-brandNight-400">
                  {{ item.title }}
                </h2>
                <p class="line-clamp-3 text-sm leading-6 text-gray-600 dark:text-gray-300">
                  {{ excerpt(item) || '暂无内容摘要' }}
                </p>

                <div class="mt-3 flex flex-wrap items-center gap-3 text-xs text-gray-400">
                  <router-link :to="item.authorId ? (`/users/${item.authorId}`) : '/users'" class="flex items-center gap-1.5 hover:text-gray-600 dark:hover:text-gray-200">
                    <img :src="avatarOf(item)" class="h-5 w-5 rounded-full bg-gray-100 object-cover dark:bg-gray-700" alt="" />
                    {{ displayNameOf(item) }}
                  </router-link>
                  <span>·</span>
                  <span v-if="item.sectionName || item.sectionId" class="rounded bg-gray-100 px-2 py-0.5 font-medium transition-colors dark:bg-gray-700">
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
          </router-link>
        </li>
      </ul>
    </div>

    <div v-if="visibleItems.length > 0" class="flex items-center justify-between rounded-lg bg-white px-5 py-3 shadow-sm dark:bg-gray-800">
      <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ feed.total || 0 }} 条 · 每页 {{ size }} 条</div>
      <div class="flex items-center gap-2">
        <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="loading || page <= 1" @click="setPage(page - 1)">上一页</button>
        <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
        <input
          v-model="inputPage"
          class="w-16 rounded border bg-white px-2 py-1 text-center text-sm focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:placeholder-gray-400 dark:focus:ring-accentCyan-400"
          inputmode="numeric"
          @keyup.enter="goToInputPage"
          @blur="goToInputPage"
        />
        <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ totalPages }} 页</span>
        <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="loading || page >= totalPages" @click="setPage(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.scrollbar-none {
  scrollbar-width: none;
}

.scrollbar-none::-webkit-scrollbar {
  display: none;
}
</style>

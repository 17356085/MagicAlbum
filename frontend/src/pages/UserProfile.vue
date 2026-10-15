<template>
  <section class="ui-page">
    <div v-if="loading" class="ui-panel ui-empty" role="status">加载中...</div>
    <div v-else-if="error" class="ui-error" role="alert">{{ error }}</div>
    <section v-else class="ui-panel space-y-6 p-4 sm:p-6">
      <header class="ui-page-header mb-0">
        <div class="flex min-w-0 items-start gap-4">
          <button type="button" class="ui-button shrink-0 px-3" @click="safeBack()" aria-label="返回上一页" title="返回上一页">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
              <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
            </svg>
          </button>
          <div class="flex min-w-0 items-center gap-4">
            <template v-if="profile.avatarUrl">
              <img :src="normalizeImageUrl(profile.avatarUrl)" alt="头像" class="h-20 w-20 shrink-0 rounded-full border-4 border-[var(--ui-subtle)] object-cover" />
            </template>
            <template v-else>
              <div class="flex h-20 w-20 shrink-0 items-center justify-center rounded-full bg-[var(--ui-subtle)] text-2xl text-[var(--ui-muted)]">
                {{ String(profile.nickname || profile.username || 'U').slice(0,1).toUpperCase() }}
              </div>
            </template>
            <div class="min-w-0">
              <h1 class="ui-title truncate">{{ profile.nickname || profile.username || '未命名用户' }}</h1>
              <p v-if="profile.username" class="ui-description">{{ profile.username }}</p>
              <div class="mt-2 flex flex-wrap items-center gap-2 text-xs">
                <router-link :to="{ name: 'user-followers', params: { id: userId } }" class="ui-tag">粉丝 {{ Number(profile.followerCount || 0) }}</router-link>
                <router-link :to="{ name: 'user-following', params: { id: userId } }" class="ui-tag">关注 {{ Number(profile.followingCount || 0) }}</router-link>
                <span v-if="!isMe && profile.followingMe" class="ui-tag">关注了你</span>
              </div>
              <div v-if="profile.homepageUrl" class="mt-2 truncate text-sm text-[var(--ui-accent)]">
                <span class="mr-1 text-[var(--ui-muted)]">主页链接：</span><a :href="profile.homepageUrl" target="_blank" rel="noopener">{{ profile.homepageUrl }}</a>
              </div>
              <div v-if="profile.location" class="mt-1 text-sm text-[var(--ui-muted)]">所在地：{{ profile.location }}</div>
            </div>
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2 self-start">
          <router-link v-if="isMe" to="/settings" class="ui-button-primary">编辑资料</router-link>
          <button v-else type="button" class="ui-button" :class="profile.followedByMe ? 'text-[var(--ui-muted)]' : 'ui-button-primary'" :disabled="followSaving" @click="toggleFollow">
            {{ followSaving ? '处理中...' : (profile.followedByMe ? '已关注' : '关注') }}
          </button>
        </div>
      </header>

      <section class="border-t border-[var(--ui-border)] pt-5">
        <h2 class="text-sm font-semibold text-[var(--ui-muted)]">个人介绍</h2>
        <template v-if="profile.bio && String(profile.bio).trim()">
          <div class="prose mt-2 max-w-none text-[var(--ui-ink)] dark:prose-invert" v-html="renderBioMarkdownHtml(profile.bio)"></div>
        </template>
        <template v-else>
          <p class="mt-2 text-[var(--ui-muted)]">这个人很神秘，什么都没有写。</p>
        </template>
        <div v-if="followError" class="ui-error mt-3" role="alert">{{ followError }}</div>
      </section>

      <section class="border-t border-[var(--ui-border)] pt-5">
        <h2 class="mb-2 text-sm font-semibold text-[var(--ui-muted)]">Ta的帖子</h2>
        <div v-if="threadsLoading" class="ui-empty" role="status">正在加载...</div>
        <template v-else>
          <div v-if="threadsError" class="ui-error mb-3" role="alert">{{ threadsError }}</div>
          <template v-if="threads.items && threads.items.length">
            <ul>
              <li v-for="t in threads.items" :key="t.id" class="ui-list-row">
                <router-link :to="`/threads/${t.id}`" class="block">
                  <div class="flex flex-wrap items-center justify-between gap-2">
                    <div class="font-medium text-[var(--ui-ink)]">{{ t.title }}</div>
                    <span class="text-xs text-[var(--ui-muted)]">#{{ t.id }}</span>
                  </div>
                  <div class="mt-1 text-xs text-[var(--ui-muted)]">更新于：{{ formatRelativeTime(t.updatedAt || t.createdAt) }}</div>
                </router-link>
              </li>
            </ul>
          </template>
          <div v-else class="ui-empty px-0 py-8" role="status"><h2>暂无帖子</h2></div>
          <div class="mt-3 flex flex-col gap-3 border-t border-[var(--ui-border)] pt-3 text-sm sm:flex-row sm:items-center sm:justify-between">
            <div class="text-xs text-[var(--ui-muted)]">共 {{ threads.total || 0 }} 条 · 每页 {{ size }} 条</div>
            <div class="flex flex-wrap items-center gap-2">
              <button type="button" class="ui-button" :disabled="page<=1" @click="prevPage">上一页</button>
              <span class="text-sm text-[var(--ui-muted)]">第</span>
              <input v-model="inputPage" aria-label="页码" class="ui-field w-16 text-center" inputmode="numeric" @keyup.enter="goToInputPage" @blur="goToInputPage" />
              <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
              <button type="button" class="ui-button" :disabled="page>=totalPages" @click="nextPage">下一页</button>
            </div>
          </div>
        </template>
      </section>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { followUser, getUserProfile, listUserThreads, unfollowUser } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { createMarkdownRenderer, renderMarkdown } from '@/utils/markdown'
import { safeBack as navigateBackSafely } from '@/utils/router'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { formatRelativeTime } from '@/composables/time'
import type { PageResult, Thread, User, UserProfile as UserProfileModel } from '@/types'

const route = useRoute()
const router = useRouter()
type ProfileView = Partial<User> & Partial<UserProfileModel>

function getRouteParamId(value: string | string[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

function createEmptyProfile(): ProfileView {
  return {
    username: '',
    nickname: '',
    bio: '',
    homepageUrl: '',
    location: '',
    avatarUrl: '',
  }
}

function createEmptyThreads(page = 1, size = 10): PageResult<Thread> {
  return { items: [], page, size, total: 0 }
}

const userId = computed(() => getRouteParamId(route.params.id as string | string[] | undefined))
const loading = ref(true)
const error = ref('')
const profile = ref<ProfileView>(createEmptyProfile())
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const isMe = computed(() => String(user?.value?.id || '') === String(userId.value || ''))
const followSaving = ref(false)
const followError = ref('')

const md = createMarkdownRenderer({ katex: true, normalizeImages: true })

function safeBack(): void {
  navigateBackSafely(router)
}

function renderBioMarkdownHtml(raw: string | undefined) {
  return renderMarkdown(md, raw)
}

// 主题帖分页状态
const threadsLoading = ref(false)
const threadsError = ref('')
const threads = ref<PageResult<Thread>>(createEmptyThreads())
const page = ref(1)
const size = ref(10)
const inputPage = ref('1')
const totalPages = computed(() => {
  const s = Math.max(1, Number(size.value || 10))
  const total = Number(threads.value.total || 0)
  return Math.max(1, Math.ceil(total / s))
})

function prevPage() {
  if (page.value > 1) {
    page.value -= 1
    loadThreads()
  }
}

function nextPage() {
  if (page.value < totalPages.value) {
    page.value += 1
    loadThreads()
  }
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  const target = Math.min(Math.max(1, Number(raw)), totalPages.value)
  if (target === page.value) {
    inputPage.value = String(page.value || 1)
    return
  }
  page.value = target
  loadThreads()
}

watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })

async function loadThreads(): Promise<void> {
  threadsLoading.value = true
  threadsError.value = ''
  try {
    const data = await listUserThreads(userId.value, { page: page.value, size: size.value })
    threads.value = data
  } catch (e: unknown) {
    threadsError.value = e instanceof Error ? e.message : '加载帖子失败'
  } finally {
    threadsLoading.value = false
  }
}

async function loadProfile(): Promise<void> {
  try {
    const data = await getUserProfile(userId.value)
    profile.value = data || createEmptyProfile()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '获取用户资料失败'
  } finally {
    loading.value = false
  }
}

async function toggleFollow(): Promise<void> {
  if (!isLoggedIn.value) {
    followError.value = '请先登录后再关注'
    return
  }
  followSaving.value = true
  followError.value = ''
  try {
    const state = profile.value.followedByMe
      ? await unfollowUser(userId.value)
      : await followUser(userId.value)
    profile.value = {
      ...profile.value,
      followedByMe: Boolean(state.followedByMe ?? state.following),
      followingMe: Boolean(state.followingMe),
      followerCount: Number(state.followerCount || 0),
      followingCount: Number(state.followingCount || 0),
    }
    try {
      window.dispatchEvent(new CustomEvent('follow-state-updated', {
        detail: {
          userId: userId.value,
          following: Boolean(state.followedByMe ?? state.following),
        },
      }))
    } catch (_) {}
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } } | null
    followError.value = err?.response?.data?.message || '关注操作失败'
  } finally {
    followSaving.value = false
  }
}

onMounted(async () => {
  await loadProfile()
  // 加载主题帖列表（默认每页10条）
  await loadThreads()
})

// 当路由 id 变化时，刷新个人资料并重置分页后重新加载帖子
watch(userId, async (newId, oldId) => {
  if (String(newId) !== String(oldId)) {
    // 刷新个人资料
    loading.value = true
    error.value = ''
    await loadProfile()
    // 重置分页并加载对应用户的帖子
    page.value = 1
    await loadThreads()
  }
})
</script>

<style scoped>
</style>

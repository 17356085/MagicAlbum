<template>
  <div class="px-0">
    <div v-if="loading" class="text-center text-gray-500">加载中...</div>
    <div v-else-if="error" class="text-center text-red-500">{{ error }}</div>
    <div v-else class="space-y-6 rounded-xl border border-gray-200 bg-white/90 p-6 shadow-sm dark:bg-gray-800/80 dark:border-gray-700">
      <!-- 返回按钮：单独一行显示在卡片顶部 -->
      <div>
<button @click="safeBack()" class="inline-flex items-center p-1 rounded text-brandDay-600 dark:text-brandNight-400 hover:bg-brandDay-50 dark:hover:bg-gray-700" aria-label="返回上一页" title="返回上一页">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="w-5 h-5">
            <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
      <div class="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div class="flex items-center gap-4">
        
          <template v-if="profile.avatarUrl">
            <img :src="normalizeImageUrl(profile.avatarUrl)" alt="头像" class="w-20 h-20 rounded-full object-cover" />
          </template>
          <template v-else>
            <div class="w-20 h-20 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-2xl text-gray-600 dark:text-gray-300">
              {{ String(profile.nickname || profile.username || 'U').slice(0,1).toUpperCase() }}
            </div>
          </template>
          <div>
            <div class="text-xl font-semibold">{{ profile.nickname || profile.username || '未命名用户' }}</div>
            <div v-if="profile.username" class="text-sm text-gray-600 dark:text-gray-300 mt-0.5">{{ profile.username }}</div>
            <div class="mt-2 flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
              <router-link
                :to="{ name: 'user-followers', params: { id: userId } }"
                class="rounded px-2 py-1 transition-colors hover:bg-gray-100 hover:text-brandDay-600 dark:hover:bg-gray-700 dark:hover:text-brandNight-300"
              >
                粉丝 {{ Number(profile.followerCount || 0) }}
              </router-link>
              <router-link
                :to="{ name: 'user-following', params: { id: userId } }"
                class="rounded px-2 py-1 transition-colors hover:bg-gray-100 hover:text-brandDay-600 dark:hover:bg-gray-700 dark:hover:text-brandNight-300"
              >
                关注 {{ Number(profile.followingCount || 0) }}
              </router-link>
              <span v-if="!isMe && profile.followingMe" class="rounded bg-gray-100 px-2 py-0.5 text-gray-600 dark:bg-gray-700 dark:text-gray-200">关注了你</span>
            </div>
  <div v-if="profile.homepageUrl" class="text-sm text-brandDay-600 dark:text-brandNight-400 mt-1">
              <span class="mr-1 text-gray-600 dark:text-gray-300">主页链接：</span>
              <a :href="profile.homepageUrl" target="_blank" rel="noopener">{{ profile.homepageUrl }}</a>
            </div>
            <div v-if="profile.location" class="text-sm text-gray-600 dark:text-gray-300 mt-1">所在地：{{ profile.location }}</div>
          </div>
        </div>
        <div class="flex items-center gap-2 self-start md:self-center">
          <router-link v-if="isMe" to="/settings" class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-500">编辑资料</router-link>
          <button
            v-else
            class="rounded px-4 py-2 text-xs font-medium shadow-sm motion-safe:transition-shadow motion-safe:duration-200 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 disabled:opacity-60 dark:focus:ring-accentCyan-500"
            :class="profile.followedByMe ? 'border border-gray-200 bg-white text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:hover:bg-gray-700' : 'bg-brandDay-600 text-white hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-700'"
            :disabled="followSaving"
            @click="toggleFollow"
          >
            {{ followSaving ? '处理中...' : (profile.followedByMe ? '已关注' : '关注') }}
          </button>
        </div>
      </div>

      <div>
        <div class="text-sm text-gray-500 mb-1">个人介绍</div>
        <template v-if="profile.bio && String(profile.bio).trim()">
          <div class="prose max-w-none dark:prose-invert text-gray-800 dark:text-gray-200" v-html="renderBioMarkdownHtml(profile.bio)"></div>
        </template>
        <template v-else>
          <div class="text-gray-800 dark:text-gray-200">这个人很神秘，什么都没有写。</div>
        </template>
        <div v-if="followError" class="mt-3 text-xs text-red-600">{{ followError }}</div>
      </div>

      <div v-if="profile.links && profile.links.length" class="space-y-2">
        <div class="text-sm text-gray-500">相关链接</div>
        <ul class="list-disc pl-6">
          <li v-for="(l, idx) in profile.links" :key="idx">
            <a :href="getProfileLinkHref(l)" target="_blank" rel="noopener" class="text-brandDay-600 dark:text-brandNight-400">{{ getProfileLinkLabel(l) }}</a>
          </li>
        </ul>
      </div>

      <!-- Ta 的主题帖列表（每页10条，分页） -->
      <div class="pt-4">
        <div class="text-sm text-gray-500 mb-2">Ta的帖子</div>
        <div v-if="threadsLoading" class="text-gray-600 dark:text-gray-300">正在加载...</div>
        <template v-else>
          <div v-if="threadsError" class="text-red-600 mb-3">{{ threadsError }}</div>
          <template v-if="threads.items && threads.items.length">
            <ul class="space-y-2">
              <li v-for="t in threads.items" :key="t.id" class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
                <router-link :to="`/threads/${t.id}`" class="block p-3">
                  <div class="flex items-center justify-between">
                    <div class="font-medium">{{ t.title }}</div>
                    <span class="text-xs text-gray-400">#{{ t.id }}</span>
                  </div>
                  <div class="mt-1 text-xs text-gray-500">更新于：{{ formatRelativeTime(t.updatedAt || t.createdAt) }}</div>
                </router-link>
              </li>
            </ul>
          </template>
          <div v-else class="text-gray-600 dark:text-gray-300">暂无帖子</div>
          <div class="mt-3 flex items-center justify-between gap-3 border-t border-gray-100 pt-3 dark:border-gray-700">
            <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ threads.total || 0 }} 条 · 每页 {{ size }} 条</div>
            <div class="flex items-center gap-2">
              <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="page<=1" @click="prevPage">上一页</button>
              <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
              <input
                v-model="inputPage"
                class="w-16 rounded border bg-white px-2 py-1 text-center text-sm focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:placeholder-gray-400 dark:focus:ring-accentCyan-400"
                inputmode="numeric"
                @keyup.enter="goToInputPage"
                @blur="goToInputPage"
              />
              <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ totalPages }} 页</span>
              <button class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200" :disabled="page>=totalPages" @click="nextPage">下一页</button>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
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
    links: [],
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

function getProfileLinkHref(link: ProfileView['links'][number]): string {
  return typeof link === 'string' ? link : (link?.url || '')
}

function getProfileLinkLabel(link: ProfileView['links'][number]): string {
  if (typeof link === 'string') return link
  return link?.title || link?.url || ''
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
    threads.value = Array.isArray(data)
      ? { items: data, page: page.value, size: size.value, total: data.length }
      : (data || createEmptyThreads(page.value, size.value))
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

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getUserProfile, listUserFollowers, listUserFollowing } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { safeBack as navigateBackSafely } from '@/utils/router'
import type { Id, PageResult, User, UserProfile } from '@/types'

type RelationType = 'followers' | 'following'

const route = useRoute()
const router = useRouter()

const userId = computed(() => String(Array.isArray(route.params.id) ? route.params.id[0] : route.params.id || ''))
const relationType = computed<RelationType>(() => route.name === 'user-followers' ? 'followers' : 'following')
const title = computed(() => relationType.value === 'followers' ? '粉丝' : '关注')

const loading = ref(false)
const error = ref('')
const profile = ref<(UserProfile & Partial<User>) | null>(null)
const list = ref<PageResult<User>>({ items: [], page: 1, size: 20, total: 0 })
const page = ref(1)
const size = ref(20)
const inputPage = ref('1')

const totalPages = computed(() => Math.max(1, Math.ceil(Number(list.value.total || 0) / Number(size.value || 20))))
const ownerName = computed(() => profile.value?.nickname || profile.value?.username || `用户 ${userId.value}`)

function displayName(u: User): string {
  return u.nickname || u.username
}

function avatarUrl(u: User): string {
  return normalizeImageUrl(u.avatarUrl || `https://api.dicebear.com/7.x/initials/svg?seed=${encodeURIComponent(displayName(u) || 'U')}`)
}

function normalizePage(data: PageResult<User> | User[]): PageResult<User> {
  if (Array.isArray(data)) {
    return { items: data, page: page.value, size: size.value, total: data.length }
  }
  return data || { items: [], page: page.value, size: size.value, total: 0 }
}

async function load(): Promise<void> {
  if (!userId.value) return
  loading.value = true
  error.value = ''
  try {
    const [profileData, relationData] = await Promise.all([
      getUserProfile(userId.value),
      relationType.value === 'followers'
        ? listUserFollowers(userId.value, { page: page.value, size: size.value })
        : listUserFollowing(userId.value, { page: page.value, size: size.value }),
    ])
    profile.value = profileData
    list.value = normalizePage(relationData)
  } catch (_) {
    error.value = `加载${title.value}列表失败`
  } finally {
    loading.value = false
  }
}

function setPage(nextPage: number): void {
  const target = Math.min(Math.max(1, nextPage), totalPages.value)
  if (target === page.value) return
  page.value = target
  load()
}

function goToInputPage(): void {
  const raw = String(inputPage.value || '').trim()
  if (!raw || !/^\d+$/.test(raw)) {
    inputPage.value = String(page.value || 1)
    return
  }
  setPage(Math.min(Math.max(1, Number(raw)), totalPages.value))
}

function safeBack(): void {
  navigateBackSafely(router)
}

onMounted(load)

watch(() => [route.params.id, route.name], () => {
  page.value = 1
  load()
})

watch(page, (val) => {
  inputPage.value = String(val || 1)
}, { immediate: true })
</script>

<template>
  <div class="rounded-xl border border-gray-100 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800">
    <div class="mb-5 flex items-center justify-between gap-3">
      <div class="flex items-center gap-3">
        <button
          class="inline-flex items-center rounded p-1 text-brandDay-600 hover:bg-brandDay-50 dark:text-brandNight-400 dark:hover:bg-gray-700"
          title="返回"
          @click="safeBack"
        >
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
            <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
          </svg>
        </button>
        <div>
          <h1 class="text-lg font-bold text-gray-900 dark:text-gray-50">{{ ownerName }} 的{{ title }}</h1>
          <div class="mt-1 text-xs text-gray-500 dark:text-gray-400">共 {{ list.total || 0 }} 人</div>
        </div>
      </div>
      <div class="flex rounded-lg bg-gray-50 p-1 text-sm dark:bg-gray-900/30">
        <router-link
          :to="{ name: 'user-following', params: { id: userId } }"
          class="rounded-md px-3 py-1.5 transition-colors"
          :class="relationType === 'following' ? 'bg-white text-brandDay-600 shadow-sm dark:bg-gray-800 dark:text-brandNight-300' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-100'"
        >
          关注
        </router-link>
        <router-link
          :to="{ name: 'user-followers', params: { id: userId } }"
          class="rounded-md px-3 py-1.5 transition-colors"
          :class="relationType === 'followers' ? 'bg-white text-brandDay-600 shadow-sm dark:bg-gray-800 dark:text-brandNight-300' : 'text-gray-500 hover:text-gray-800 dark:text-gray-400 dark:hover:text-gray-100'"
        >
          粉丝
        </router-link>
      </div>
    </div>

    <div v-if="loading" class="py-10 text-center text-sm text-gray-500">正在加载...</div>
    <div v-else-if="error" class="py-10 text-center text-sm text-red-500">{{ error }}</div>
    <div v-else-if="(list.items || []).length === 0" class="rounded-lg border border-dashed border-gray-200 py-12 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
      暂无{{ title }}
    </div>

    <ul v-else class="divide-y divide-gray-100 dark:divide-gray-700">
      <li v-for="u in list.items" :key="u.id">
        <router-link :to="`/users/${u.id}`" class="flex items-center gap-3 px-2 py-3 transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50">
          <img :src="avatarUrl(u)" alt="avatar" class="h-11 w-11 rounded-full object-cover" />
          <div class="min-w-0 flex-1">
            <div class="truncate text-sm font-semibold text-gray-900 dark:text-gray-50">{{ displayName(u) }}</div>
            <div class="mt-0.5 truncate text-xs text-gray-500 dark:text-gray-400">@{{ u.username }}</div>
          </div>
          <span class="text-xs text-gray-400">查看主页</span>
        </router-link>
      </li>
    </ul>

    <div v-if="(list.items || []).length > 0" class="mt-5 flex items-center justify-between border-t border-gray-100 pt-4 dark:border-gray-700">
      <div class="text-xs text-gray-500 dark:text-gray-400">共 {{ list.total || 0 }} 人 · 每页 {{ size }} 人</div>
      <div class="flex items-center gap-2">
        <button
          class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
          :disabled="loading || page <= 1"
          @click="setPage(page - 1)"
        >
          上一页
        </button>
        <span class="text-sm text-gray-600 dark:text-gray-300">第</span>
        <input
          v-model="inputPage"
          class="w-16 rounded border bg-white px-2 py-1 text-center text-sm focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:placeholder-gray-400 dark:focus:ring-accentCyan-400"
          inputmode="numeric"
          @keyup.enter="goToInputPage"
          @blur="goToInputPage"
        />
        <span class="text-sm text-gray-600 dark:text-gray-300">/ {{ totalPages }} 页</span>
        <button
          class="rounded border px-3 py-1 text-sm disabled:opacity-50 dark:border-gray-700 dark:text-gray-200"
          :disabled="loading || page >= totalPages"
          @click="setPage(page + 1)"
        >
          下一页
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getUserProfile, listUserFollowers, listUserFollowing } from '@/api/users'
import { normalizeImageUrl } from '@/utils/image'
import { safeBack as navigateBackSafely } from '@/utils/router'
import type { PageResult, User, UserProfile } from '@/types'

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
    list.value = relationData
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
  <section class="ui-page">
    <header class="ui-page-header">
      <div class="flex min-w-0 items-center gap-3">
        <button
          type="button"
          class="ui-button shrink-0 px-3"
          title="返回"
          aria-label="返回上一页"
          @click="safeBack"
        >
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-5 w-5">
            <path fill-rule="evenodd" d="M7.22 12.53a.75.75 0 0 1 0-1.06l5.25-5.25a.75.75 0 1 1 1.06 1.06L9.81 11.5H20.25a.75.75 0 0 1 0 1.5H9.81l3.72 4.22a.75.75 0 1 1-1.06 1.06l-5.25-5.25Z" clip-rule="evenodd" />
          </svg>
        </button>
        <div class="min-w-0">
          <h1 class="ui-title truncate">{{ ownerName }} 的{{ title }}</h1>
          <p class="ui-description">共 {{ list.total || 0 }} 人</p>
        </div>
      </div>
      <nav class="flex flex-wrap rounded-lg bg-[var(--ui-subtle)] p-1 text-sm" aria-label="用户关系">
        <router-link
          :to="{ name: 'user-following', params: { id: userId } }"
          class="rounded-md px-3 py-1.5 font-medium"
          :class="relationType === 'following' ? 'bg-[var(--ui-surface)] text-[var(--ui-accent)]' : 'text-[var(--ui-muted)] hover:text-[var(--ui-ink)]'"
        >
          关注
        </router-link>
        <router-link
          :to="{ name: 'user-followers', params: { id: userId } }"
          class="rounded-md px-3 py-1.5 font-medium"
          :class="relationType === 'followers' ? 'bg-[var(--ui-surface)] text-[var(--ui-accent)]' : 'text-[var(--ui-muted)] hover:text-[var(--ui-ink)]'"
        >
          粉丝
        </router-link>
      </nav>
    </header>

    <section class="ui-panel overflow-hidden">
      <div v-if="loading" class="ui-empty" role="status">正在加载...</div>
      <div v-else-if="error" class="ui-error m-4" role="alert">{{ error }}</div>
      <div v-else-if="(list.items || []).length === 0" class="ui-empty" role="status">
        <h2>暂无{{ title }}</h2>
      </div>

      <ul v-else>
        <li v-for="u in list.items" :key="u.id" class="ui-list-row">
          <router-link :to="`/users/${u.id}`" class="flex min-w-0 items-center gap-3">
            <img :src="avatarUrl(u)" alt="" class="h-11 w-11 shrink-0 rounded-full object-cover" />
            <div class="min-w-0 flex-1">
              <div class="truncate text-sm font-semibold text-[var(--ui-ink)]">{{ displayName(u) }}</div>
              <div class="mt-0.5 truncate text-xs text-[var(--ui-muted)]">@{{ u.username }}</div>
            </div>
            <span class="shrink-0 text-xs text-[var(--ui-muted)]">查看主页</span>
          </router-link>
        </li>
      </ul>

      <div v-if="(list.items || []).length > 0" class="flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 text-sm sm:flex-row sm:items-center sm:justify-between sm:px-6">
        <div class="text-xs text-[var(--ui-muted)]">共 {{ list.total || 0 }} 人 · 每页 {{ size }} 人</div>
        <div class="flex flex-wrap items-center gap-2">
          <button type="button" class="ui-button" :disabled="loading || page <= 1" @click="setPage(page - 1)">上一页</button>
          <span class="text-sm text-[var(--ui-muted)]">第</span>
          <input v-model="inputPage" aria-label="页码" class="ui-field w-16 text-center" inputmode="numeric" @keyup.enter="goToInputPage" @blur="goToInputPage" />
          <span class="text-sm text-[var(--ui-muted)]">/ {{ totalPages }} 页</span>
          <button type="button" class="ui-button" :disabled="loading || page >= totalPages" @click="setPage(page + 1)">下一页</button>
        </div>
      </div>
    </section>
  </section>
 </template>

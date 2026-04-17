<template>
  <section class="mx-auto flex min-h-[60vh] max-w-xl items-center justify-center px-4 py-12">
    <div class="w-full rounded-2xl border border-sky-100 bg-white p-6 text-center shadow-sm dark:border-gray-700 dark:bg-gray-900">
      <div class="mx-auto flex h-12 w-12 items-center justify-center rounded-full" :class="success ? 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300' : 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-300'">
        <svg v-if="success" viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M20 6 9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <svg v-else viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>

      <h1 class="mt-4 text-xl font-semibold text-gray-900 dark:text-white">
        {{ success ? '登录成功，正在返回…' : '第三方登录未完成' }}
      </h1>
      <p class="mt-2 text-sm text-gray-500 dark:text-gray-400">
        {{ message }}
      </p>

      <div class="mt-5 flex items-center justify-center gap-3">
        <button
          type="button"
          class="rounded-xl bg-sky-500 px-4 py-2 text-sm font-medium text-white hover:bg-sky-600 dark:bg-cyan-500 dark:hover:bg-cyan-600"
          @click="goNext"
        >
          {{ success ? '立即跳转' : '返回首页' }}
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCurrentUser } from '@/api/users'
import { useAuthStore } from '@/stores/auth'
import { clearPendingAuthRedirect, getPendingAuthRedirect } from '@/utils/authStorage'
import { getSingleQueryValue } from '@/utils/router'
import type { User } from '@/types'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const finished = ref(false)
const success = ref(false)
const message = ref('正在处理第三方登录结果…')
const successRedirect = ref('/discover')

const nextPath = computed(() => {
  const redirect = getPendingAuthRedirect()
  return redirect || '/discover'
})

function buildFallbackUser(): User | null {
  const accessToken = getSingleQueryValue(route.query.accessToken)
  const username = getSingleQueryValue(route.query.username)
  const userIdRaw = getSingleQueryValue(route.query.userId)
  const avatarUrl = getSingleQueryValue(route.query.avatarUrl)
  const nickname = getSingleQueryValue(route.query.nickname)
  const userId = Number(userIdRaw)

  if (!accessToken) return null
  return {
    id: Number.isFinite(userId) ? userId : 0,
    username: username || 'oauth_user',
    nickname: nickname || undefined,
    avatarUrl: avatarUrl || undefined,
  }
}

async function finalizeSuccess(): Promise<void> {
  const accessToken = getSingleQueryValue(route.query.accessToken)
  if (!accessToken) {
    success.value = false
    message.value = '回调缺少 accessToken，请重新发起第三方登录。'
    return
  }

  const fallbackUser = buildFallbackUser()
  authStore.loginWithAuthResponse({
    accessToken,
    user: fallbackUser,
  })

  try {
    const currentUser = await getCurrentUser()
    if (currentUser) {
      authStore.applyAuthState({ accessToken, user: currentUser })
    }
  } catch (_) {}

  success.value = true
  message.value = '已完成登录收口，正在返回原页面。'
  successRedirect.value = nextPath.value
  clearPendingAuthRedirect()

  window.setTimeout(() => {
    void router.replace(successRedirect.value)
  }, 500)
}

function finalizeError(): void {
  const provider = (getSingleQueryValue(route.query.provider) || '').toLowerCase()
  const error = getSingleQueryValue(route.query.error)
  success.value = false
  if (!error) {
    message.value = '第三方登录未完成，请重试。'
    return
  }

  const providerLabel = provider === 'github'
    ? 'GitHub'
    : provider === 'google'
      ? 'Google'
      : provider === 'apple'
        ? 'Apple'
      : provider === 'wechat'
        ? '微信'
        : '第三方'

  message.value = `${providerLabel} 登录失败：${error}`
}

function goNext(): void {
  void router.replace(success.value ? successRedirect.value : '/discover')
}

onMounted(async () => {
  if (finished.value) return
  finished.value = true

  const status = getSingleQueryValue(route.query.status)
  if (status === 'success') {
    await finalizeSuccess()
    return
  }

  finalizeError()
})
</script>

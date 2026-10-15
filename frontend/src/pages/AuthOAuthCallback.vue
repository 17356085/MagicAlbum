<template>
  <section class="ui-page mx-auto flex min-h-[60vh] max-w-xl items-center justify-center px-4 py-12">
    <div class="ui-panel w-full p-6 text-center sm:p-8">
      <div class="mx-auto flex h-12 w-12 items-center justify-center rounded-full" :class="success ? 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300' : 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-300'">
        <svg v-if="success" viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M20 6 9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <svg v-else viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>

      <h1 class="ui-title mt-4">
        {{ success ? '登录成功，正在返回…' : '第三方登录未完成' }}
      </h1>
      <p class="ui-description mx-auto mt-2">
        {{ message }}
      </p>

      <div class="mt-6 flex items-center justify-center gap-3">
        <button
          type="button"
          class="ui-button-primary"
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
import { exchangeOAuthCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { clearPendingAuthRedirect, getPendingAuthRedirect } from '@/utils/authStorage'
import { getSingleQueryValue } from '@/utils/router'

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

async function finalizeSuccess(): Promise<void> {
  const code = getSingleQueryValue(route.query.code).trim()
  if (!code) {
    success.value = false
    message.value = '回调缺少一次性登录 code，请重新发起第三方登录。'
    return
  }

  clearCallbackQuery()

  try {
    const authResponse = await exchangeOAuthCode(code)
    if (!authResponse.accessToken) {
      throw new Error('OAuth code exchange returned no access token')
    }
    authStore.loginWithAuthResponse(authResponse)
  } catch (_) {
    success.value = false
    message.value = '第三方登录 code 无效、已过期或已使用，请重新发起登录。'
    return
  }

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
  success.value = false

  const providerLabel = provider === 'github'
    ? 'GitHub'
    : provider === 'google'
      ? 'Google'
      : provider === 'apple'
        ? 'Apple'
      : provider === 'wechat'
        ? '微信'
        : '第三方'

  message.value = `${providerLabel} 登录未完成，请重新发起登录。`
  clearCallbackQuery()
}

function clearCallbackQuery(): void {
  try {
    const cleanUrl = `${window.location.pathname}${window.location.hash}`
    window.history.replaceState(window.history.state, document.title, cleanUrl)
  } catch (_) {
    // URL cleanup is defense in depth; the one-time code is still exchanged by POST.
  }
}

function goNext(): void {
  void router.replace(success.value ? successRedirect.value : '/discover')
}

onMounted(async () => {
  if (finished.value) return
  finished.value = true

  const code = getSingleQueryValue(route.query.code).trim()
  const error = getSingleQueryValue(route.query.error).trim()
  const status = getSingleQueryValue(route.query.status).trim()
  if ((code && !error) || status === 'success') {
    await finalizeSuccess()
    return
  }

  finalizeError()
})
</script>

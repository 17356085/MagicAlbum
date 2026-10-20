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
          v-if="!success && retryAvailable"
          data-test="oauth-retry"
          type="button"
          class="ui-button-primary"
          :disabled="exchangePending"
          @click="retryExchange"
        >
          {{ exchangePending ? '正在重试…' : '重试登录' }}
        </button>
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
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { exchangeOAuthCode } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { clearPendingAuthRedirect, getPendingAuthRedirect } from '@/utils/authStorage'
import {
  clearOAuthBrowserProof,
  isOAuthProvider,
  readOAuthBrowserProof,
  type OAuthBrowserProof,
} from '@/utils/oauthBrowserProof'
import { getSingleQueryValue } from '@/utils/router'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const finished = ref(false)
const success = ref(false)
const message = ref('正在处理第三方登录结果…')
const successRedirect = ref('/discover')
const pendingCode = ref('')
const pendingProof = ref<OAuthBrowserProof | null>(null)
const pendingGeneration = ref<number | null>(null)
const exchangePending = ref(false)
const retryAvailable = ref(false)
let disposed = false
let redirectTimer: ReturnType<typeof setTimeout> | null = null

function isCurrentOperation(generation: number): boolean {
  return !disposed && authStore.isAuthGenerationCurrent(generation)
}

const nextPath = computed(() => {
  const redirect = getPendingAuthRedirect()
  return redirect || '/discover'
})

function isCurrentBrowserProof(expected: OAuthBrowserProof): boolean {
  const current = readOAuthBrowserProof(expected.provider)
  return !!current
    && current.provider === expected.provider
    && current.verifier === expected.verifier
    && current.expiresAt === expected.expiresAt
}

function responseStatus(error: unknown): number {
  if (typeof error !== 'object' || error === null || !('response' in error)) return 0
  const response = (error as { response?: { status?: unknown } }).response
  const status = Number(response?.status ?? 0)
  return Number.isFinite(status) ? status : 0
}

async function exchangePendingCode(): Promise<boolean> {
  if (exchangePending.value) return false
  const code = pendingCode.value
  const proof = pendingProof.value
  const generation = pendingGeneration.value
  if (!code || !proof || generation === null) return false
  if (!isCurrentOperation(generation)) {
    retryAvailable.value = false
    return false
  }
  if (!isCurrentBrowserProof(proof)) {
    pendingCode.value = ''
    pendingProof.value = null
    retryAvailable.value = false
    message.value = '当前标签页的 OAuth 登录校验已更新，请重新发起登录。'
    return false
  }

  exchangePending.value = true
  try {
    const authResponse = await exchangeOAuthCode(code, proof.verifier)
    if (!isCurrentOperation(generation)) return false
    if (!isCurrentBrowserProof(proof)) {
      pendingCode.value = ''
      pendingProof.value = null
      retryAvailable.value = false
      message.value = '当前标签页的 OAuth 登录校验已更新，请重新发起登录。'
      return false
    }
    if (!authResponse.accessToken) {
      throw new Error('OAuth code exchange returned no access token')
    }
    clearOAuthBrowserProof(proof)
    authStore.loginWithAuthResponse(authResponse)
    pendingCode.value = ''
    pendingProof.value = null
    pendingGeneration.value = null
    return true
  } catch (error) {
    if (!isCurrentOperation(generation)) return false
    if (!isCurrentBrowserProof(proof)) {
      pendingCode.value = ''
      pendingProof.value = null
      retryAvailable.value = false
      message.value = '当前标签页的 OAuth 登录校验已失效，请重新发起登录。'
      return false
    }
    if (responseStatus(error) === 400) {
      pendingCode.value = ''
      pendingProof.value = null
      retryAvailable.value = false
      message.value = '第三方登录 code 已失效或已使用，请重新发起登录。'
      return false
    }
    retryAvailable.value = true
    success.value = false
    message.value = '第三方登录暂未完成，请点击重试。'
    return false
  } finally {
    exchangePending.value = false
  }
}

function completeSuccess(): void {
  if (success.value) return
  success.value = true
  message.value = '已完成登录收口，正在返回原页面。'
  successRedirect.value = nextPath.value
  clearPendingAuthRedirect()

  const committedGeneration = authStore.getAuthGeneration()
  redirectTimer = setTimeout(() => {
    redirectTimer = null
    if (isCurrentOperation(committedGeneration)) void router.replace(successRedirect.value)
  }, 500)
}

async function finalizeSuccess(): Promise<void> {
  const providerValue = getSingleQueryValue(route.query.provider).trim().toLowerCase()
  const code = getSingleQueryValue(route.query.code).trim()
  if (!isOAuthProvider(providerValue)) {
    success.value = false
    message.value = '回调缺少有效的第三方登录 Provider，请重新发起登录。'
    clearCallbackQuery()
    return
  }
  if (!code) {
    success.value = false
    message.value = '回调缺少一次性登录 code，请重新发起第三方登录。'
    clearCallbackQuery()
    return
  }

  const proof = readOAuthBrowserProof(providerValue)
  if (!proof) {
    success.value = false
    message.value = '当前标签页的 OAuth 登录校验已失效，请重新发起登录。'
    clearCallbackQuery()
    return
  }

  const generation = authStore.beginOAuthCallbackOperation(proof)
  if (generation === null) {
    success.value = false
    message.value = '当前标签页的 OAuth 登录校验已更新，请重新发起登录。'
    clearCallbackQuery()
    return
  }

  pendingCode.value = code
  pendingProof.value = proof
  pendingGeneration.value = generation
  clearCallbackQuery()
  if (await exchangePendingCode()) completeSuccess()
}

async function retryExchange(): Promise<void> {
  if (!retryAvailable.value) return
  if (await exchangePendingCode()) completeSuccess()
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
    // URL cleanup is defense in depth; the one-time code remains in component memory.
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

onUnmounted(() => {
  disposed = true
  if (redirectTimer) clearTimeout(redirectTimer)
})
</script>

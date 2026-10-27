<template>
  <section class="ui-page mx-auto flex min-h-[60vh] max-w-xl items-center justify-center px-4 py-12">
    <div class="ui-panel w-full p-6 text-center sm:p-8">
      <div class="mx-auto flex h-12 w-12 items-center justify-center rounded-full" :class="success ? 'bg-emerald-100 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300' : processing ? 'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300' : 'bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-300'" aria-hidden="true">
        <svg v-if="success" viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M20 6 9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <svg v-else-if="processing" viewBox="0 0 24 24" class="h-6 w-6 animate-spin motion-reduce:animate-none" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M20 12a8 8 0 1 1-8-8" stroke-linecap="round" />
        </svg>
        <svg v-else viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </div>

      <h1 class="ui-title mt-4" aria-live="polite">
        {{ success ? '登录成功，正在返回…' : processing ? '正在登录，请稍候…' : '第三方登录未完成' }}
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
import { useAuthStore, type AuthOperationContext } from '@/stores/auth'
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
const pendingContext = ref<AuthOperationContext | null>(null)
const exchangePending = ref(false)
const retryAvailable = ref(false)
const processing = computed(() => !finished.value || exchangePending.value)
let disposed = false
let redirectTimer: ReturnType<typeof setTimeout> | null = null
let committedContext: AuthOperationContext | null = null

function isCurrentOperation(context: AuthOperationContext | null): boolean {
  return !disposed && !!context && authStore.isAuthOperationContextCurrent(context)
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

function clearPendingOperation(): void {
  pendingCode.value = ''
  pendingProof.value = null
  pendingContext.value = null
  retryAvailable.value = false
}

function showInvalidatedOperation(messageText: string): false {
  if (disposed) return false
  clearPendingOperation()
  success.value = false
  message.value = messageText
  return false
}

function ensureCurrentOperation(context: AuthOperationContext, proof: OAuthBrowserProof): boolean {
  if (disposed) return false
  if (!authStore.isAuthOperationContextCurrent(context)) {
    const proofChanged = !isCurrentBrowserProof(proof)
    return showInvalidatedOperation(proofChanged
      ? '当前标签页的 OAuth 登录校验已更新，请重新发起登录。'
      : '当前登录状态已更新，请重新发起登录。')
  }
  if (!isCurrentBrowserProof(proof)) {
    return showInvalidatedOperation('当前标签页的 OAuth 登录校验已更新，请重新发起登录。')
  }
  return true
}

async function exchangePendingCode(): Promise<boolean> {
  if (exchangePending.value) return false
  const code = pendingCode.value
  const proof = pendingProof.value
  const context = pendingContext.value
  if (!code || !proof || !context) return false
  if (!ensureCurrentOperation(context, proof)) return false

  exchangePending.value = true
  message.value = '正在完成第三方登录，请稍候。'
  try {
    const authResponse = await exchangeOAuthCode(code, proof.verifier)
    if (!ensureCurrentOperation(context, proof)) return false
    if (!authResponse.accessToken) {
      throw new Error('OAuth code exchange returned no access token')
    }
    if (!authStore.tryLoginWithAuthResponse(authResponse, context)) {
      return showInvalidatedOperation('当前登录状态已更新，请重新发起登录。')
    }
    if (disposed) return false
    committedContext = authStore.captureAuthOperationContext()
    // The store clears the proof as part of a committed auth state update. The
    // expected-value guard keeps a newer callback proof intact if one replaced
    // this proof before the commit.
    clearOAuthBrowserProof(proof)
    clearPendingOperation()
    return true
  } catch (error) {
    if (disposed) return false
    if (!authStore.isAuthOperationContextCurrent(context)) {
      const proofChanged = !isCurrentBrowserProof(proof)
      return showInvalidatedOperation(proofChanged
        ? '当前标签页的 OAuth 登录校验已失效，请重新发起登录。'
        : '当前登录状态已更新，请重新发起登录。')
    }
    if (!isCurrentBrowserProof(proof)) {
      return showInvalidatedOperation('当前标签页的 OAuth 登录校验已失效，请重新发起登录。')
    }
    if (responseStatus(error) === 400) {
      clearPendingOperation()
      message.value = '第三方登录 code 已失效或已使用，请重新发起登录。'
      return false
    }
    retryAvailable.value = true
    success.value = false
    message.value = '第三方登录暂未完成，请点击重试。'
    return false
  } finally {
    if (!disposed) exchangePending.value = false
  }
}

function completeSuccess(): void {
  if (disposed || success.value) return
  if (!isCurrentOperation(committedContext)) {
    showInvalidatedOperation('当前登录状态已更新，请重新发起登录。')
    return
  }
  success.value = true
  message.value = '登录成功，正在返回原页面。'
  successRedirect.value = nextPath.value
  clearPendingAuthRedirect()

  redirectTimer = setTimeout(() => {
    redirectTimer = null
    if (isCurrentOperation(committedContext)) {
      void router.replace(successRedirect.value)
      return
    }
    if (!disposed) {
      showInvalidatedOperation('当前登录状态已更新，请重新发起登录。')
    }
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
  pendingContext.value = authStore.captureAuthOperationContext()
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
  if (disposed) return
  if (success.value && !isCurrentOperation(committedContext)) {
    showInvalidatedOperation('当前登录状态已更新，请重新发起登录。')
    return
  }
  void router.replace(success.value ? successRedirect.value : '/discover')
}

function handleStorageChange(event: StorageEvent): void {
  if (disposed || (event.key !== null && event.key !== 'accessToken')) return
  const pending = pendingContext.value
  if (pending && !authStore.isAuthOperationContextCurrent(pending)) {
    const proof = pendingProof.value
    if (proof && !isCurrentBrowserProof(proof)) {
      showInvalidatedOperation('当前标签页的 OAuth 登录校验已更新，请重新发起登录。')
    } else {
      showInvalidatedOperation('当前登录状态已更新，请重新发起登录。')
    }
    return
  }
  if (success.value && committedContext && !authStore.isAuthOperationContextCurrent(committedContext)) {
    showInvalidatedOperation('当前登录状态已更新，请重新发起登录。')
  }
}

onMounted(async () => {
  if (finished.value) return
  finished.value = true
  window.addEventListener('storage', handleStorageChange)

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
  window.removeEventListener('storage', handleStorageChange)
  if (redirectTimer) clearTimeout(redirectTimer)
})
</script>

<template>
  <div :class="embedded ? 'h-full px-0 py-0' : 'px-4 py-6'">
    <div class="rounded-lg border border-dashed border-gray-300 p-6 text-center dark:border-gray-700" :class="embedded ? 'h-full border-none bg-transparent p-0 dark:bg-transparent' : ''">
      <div class="space-y-3">

        <div class="mx-auto w-fit max-w-full rounded-[20px] bg-white p-2 shadow-[0_14px_32px_rgba(56,189,248,0.14)] ring-1 ring-sky-100 dark:bg-gray-900 dark:ring-white/10">
          <AuthQrCode :value="session?.qrUrl || ''" :status="status" />
        </div>

        <div class="flex items-center justify-center gap-2">
          <span
            v-if="status !== 'PENDING'"
            class="rounded-full px-3 py-1 text-[11px] font-medium"
            :class="statusClass"
          >
            {{ statusLabel }}
          </span>
          <span v-if="countdownLabel" class="text-xs text-gray-500 dark:text-gray-400">{{ countdownLabel }}</span>
        </div>

        <p v-if="shouldShowMessage && message" class="text-[11px] leading-4" :class="messageClass">{{ message }}</p>

        <div class="flex items-center justify-center gap-2">
          <button
            v-if="session?.qrId"
            type="button"
            class="rounded-xl p-2 text-sm text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"
            @click="openConfirmPage"
            aria-label="打开确认页"
            title="打开确认页"
          >
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="h-4 w-4">
              <path d="M14 5h5v5" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M10 14 19 5" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M19 14v4a1 1 0 0 1-1 1h-12a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h4" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <button type="button" class="rounded-xl p-2 text-sm text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700" @click="refreshSession" :disabled="loading" aria-label="刷新二维码" title="刷新二维码">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="h-4 w-4">
              <path d="M20 11a8 8 0 1 0 2 5.3" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M20 4v7h-7" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <button
            v-if="useMock"
            type="button"
            class="rounded-xl bg-sky-500 px-3.5 py-2 text-sm font-medium text-white hover:bg-sky-600 disabled:opacity-60 dark:bg-cyan-500 dark:hover:bg-cyan-600"
            @click="simulateConfirm"
            :disabled="loading || status === 'CONFIRMED' || status === 'EXPIRED' || status === 'CANCELED'"
          >
            模拟确认登录
          </button>
        </div>

      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { cancelQrLoginSession, createQrLoginSession, getQrLoginStatus } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import type { QrLoginSession, QrLoginStatus } from '@/types'
import AuthQrCode from './AuthQrCode.vue'

const props = withDefaults(defineProps<{
  embedded?: boolean
}>(), {
  embedded: false,
})

const emit = defineEmits<{
  close: []
  notify: [message: string]
  success: []
}>()

const router = useRouter()
const authStore = useAuthStore()
const embedded = computed(() => props.embedded)
const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'
const session = ref<QrLoginSession | null>(null)
const status = ref<QrLoginStatus>('PENDING')
const message = ref('')
const loading = ref(false)
const forcedConfirm = ref(false)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let countdownTimer: ReturnType<typeof setInterval> | null = null
const now = ref(Date.now())

const confirmPageUrl = computed(() => {
  if (!session.value?.qrId) return ''
  try {
    const url = new URL('/auth/qr/confirm', window.location.origin)
    url.searchParams.set('qrId', session.value.qrId)
    return url.toString()
  } catch (_) {
    return `/auth/qr/confirm?qrId=${encodeURIComponent(session.value.qrId)}`
  }
})

const statusLabel = computed(() => {
  if (status.value === 'SCANNED') return '已扫码'
  if (status.value === 'CONFIRMED') return '已确认'
  if (status.value === 'EXPIRED') return '已过期'
  if (status.value === 'CANCELED') return '已取消'
  return ''
})

const statusClass = computed(() => {
  if (status.value === 'SCANNED') return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
  if (status.value === 'CONFIRMED') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
  if (status.value === 'EXPIRED') return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
  if (status.value === 'CANCELED') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
  return 'bg-gray-200 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
})

const messageClass = computed(() => {
  if (status.value === 'EXPIRED' || status.value === 'CANCELED') return 'text-red-600'
  return 'text-gray-500 dark:text-gray-400'
})

const countdownLabel = computed(() => {
  if (!session.value?.expiresAt || status.value === 'CONFIRMED' || status.value === 'CANCELED') return ''
  const remaining = Math.max(0, Math.ceil((new Date(session.value.expiresAt).getTime() - now.value) / 1000))
  return remaining > 0 ? `${remaining}s 后过期` : '即将过期'
})

const shouldShowMessage = computed(() => {
  return status.value === 'EXPIRED' || status.value === 'CANCELED' || status.value === 'CONFIRMED'
})

function clearTimers(): void {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function schedulePoll(): void {
  if (!session.value) return
  clearTimeout(pollTimer as ReturnType<typeof setTimeout>)
  pollTimer = setTimeout(() => {
    void pollStatus()
  }, 2000)
}

function startCountdown(): void {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

async function createSession(): Promise<void> {
  loading.value = true
  forcedConfirm.value = false
  try {
    const nextSession = await createQrLoginSession()
    session.value = nextSession
    status.value = nextSession.status
    message.value = ''
    now.value = Date.now()
    startCountdown()
    schedulePoll()
  } catch {
    message.value = '二维码生成失败，请稍后重试'
    emit('notify', message.value)
  } finally {
    loading.value = false
  }
}

async function pollStatus(): Promise<void> {
  if (!session.value) return
  loading.value = true
  try {
    const response = await getQrLoginStatus(session.value.qrId)
    if (forcedConfirm.value && response.status === 'SCANNED') {
      response.status = 'CONFIRMED'
      response.accessToken = response.accessToken || `mock-token-${Math.random().toString(36).slice(2)}`
      response.user = response.user || { id: Date.now(), username: 'qr_confirmed_user', avatarUrl: '' }
      response.message = '已在移动端确认登录'
    }

    session.value = {
      qrId: response.qrId,
      qrUrl: response.qrUrl,
      expiresAt: response.expiresAt,
      status: response.status,
    }
    status.value = response.status
    message.value = response.message || ''

    if (response.status === 'CONFIRMED' && response.accessToken) {
      authStore.loginWithAuthResponse({
        accessToken: response.accessToken,
        user: response.user || null,
      })
      emit('notify', '二维码登录成功')
      emit('success')
      clearTimers()
      return
    }

    if (response.status === 'EXPIRED') {
      clearTimers()
      message.value = response.message || '二维码已过期，请刷新'
      return
    }
    if (response.status === 'CANCELED') {
      clearTimers()
      message.value = response.message || '二维码登录已取消'
      return
    }

    schedulePoll()
  } catch {
    message.value = useMock ? '二维码状态查询失败，请稍后重试' : '二维码状态查询失败，请稍后重试'
    emit('notify', message.value)
    if (useMock) schedulePoll()
  } finally {
    loading.value = false
  }
}

async function refreshSession(): Promise<void> {
  clearTimers()
  if (session.value) {
    try {
      await cancelQrLoginSession(session.value.qrId)
    } catch {}
  }
  await createSession()
}

function simulateConfirm(): void {
  forcedConfirm.value = true
  message.value = ''
  void pollStatus()
}

function openConfirmPage(): void {
  if (!session.value?.qrId) return
  if (!embedded.value && confirmPageUrl.value) {
    window.open(confirmPageUrl.value, '_blank', 'noopener,noreferrer')
    return
  }
  router.push({ name: 'auth-qr-confirm', query: { qrId: session.value.qrId } })
}

onMounted(() => {
  void createSession()
})

onUnmounted(() => {
  clearTimers()
  if (session.value) {
    void cancelQrLoginSession(session.value.qrId)
  }
})
</script>

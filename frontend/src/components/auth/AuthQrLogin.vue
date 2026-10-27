<template>
  <div :class="embedded ? 'h-full px-0 py-0' : 'px-4 py-6'">
    <div class="border border-dashed border-gray-300 p-5 text-center dark:border-gray-700" :class="embedded ? 'h-full border-none bg-transparent p-0 dark:bg-transparent' : ''">
      <div class="space-y-3">

        <div class="mx-auto w-fit max-w-full border border-gray-200 bg-white p-2 dark:border-gray-700 dark:bg-gray-900">
          <AuthQrCode :value="session?.qrUrl || ''" :status="status" />
        </div>

        <div class="flex items-center justify-center gap-2">
          <span
            v-if="status !== 'PENDING'"
            class="ui-tag text-[11px] font-medium"
            :class="statusClass"
          >
            {{ statusLabel }}
          </span>
          <span v-if="countdownLabel" class="ui-muted text-xs">{{ countdownLabel }}</span>
        </div>

        <p v-if="shouldShowMessage && message" class="text-[11px] leading-4" :class="messageClass">{{ message }}</p>

        <div class="flex items-center justify-center gap-2">
          <button
            v-if="session?.qrId"
            type="button"
            class="ui-button min-h-9 p-2 text-sm"
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
          <button type="button" class="ui-button min-h-9 p-2 text-sm" @click="refreshSession" aria-label="刷新二维码" title="刷新二维码">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" class="h-4 w-4">
              <path d="M20 11a8 8 0 1 0 2 5.3" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M20 4v7h-7" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <button
            v-if="useMock"
            type="button"
            class="ui-button-primary px-3.5 py-2 text-sm disabled:opacity-60"
            @click="simulateConfirm"
            :disabled="loading || status === 'CONFIRMED' || status === 'EXPIRED' || status === 'CANCELLED'"
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
import { cancelQrLoginSession, confirmQrLoginSession, createQrLoginSession, exchangeQrLoginSession, getQrLoginStatus } from '@/api/auth'
import { useAuthStore, type AuthOperationContext } from '@/stores/auth'
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
let pollTimer: ReturnType<typeof setTimeout> | null = null
let countdownTimer: ReturnType<typeof setInterval> | null = null
let lifecycleGeneration = 0
let flowAuthContext: AuthOperationContext | null = null
let disposed = false
const now = ref(Date.now())

type SessionSnapshot = Readonly<QrLoginSession>

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
  if (status.value === 'CANCELLED') return '已取消'
  return ''
})

const statusClass = computed(() => {
  if (status.value === 'SCANNED') return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
  if (status.value === 'CONFIRMED') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
  if (status.value === 'EXPIRED') return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
  if (status.value === 'CANCELLED') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
  return 'bg-gray-200 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
})

const messageClass = computed(() => {
  if (status.value === 'EXPIRED' || status.value === 'CANCELLED') return 'text-red-600'
  return 'text-gray-500 dark:text-gray-400'
})

const countdownLabel = computed(() => {
  if (!session.value?.expiresAt || status.value === 'CONFIRMED' || status.value === 'CANCELLED') return ''
  const remaining = Math.max(0, Math.ceil((new Date(session.value.expiresAt).getTime() - now.value) / 1000))
  return remaining > 0 ? `${remaining}s 后过期` : '即将过期'
})

const shouldShowMessage = computed(() => {
  return status.value === 'EXPIRED' || status.value === 'CANCELLED' || status.value === 'CONFIRMED'
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

function isLifecycleCurrent(generation: number, context: AuthOperationContext | null = flowAuthContext): boolean {
  if (disposed || generation !== lifecycleGeneration) return false
  if (context && authStore.isAuthOperationContextCurrent(context)) return true
  clearTimers()
  loading.value = false
  return false
}

function invalidateLifecycle(): number {
  lifecycleGeneration += 1
  clearTimers()
  loading.value = false
  return lifecycleGeneration
}

function snapshotSession(value: QrLoginSession | null): SessionSnapshot | null {
  return value ? { ...value } : null
}

function schedulePoll(generation: number, snapshot: SessionSnapshot, context: AuthOperationContext): void {
  if (!isLifecycleCurrent(generation, context)) return
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = setTimeout(() => {
    pollTimer = null
    if (isLifecycleCurrent(generation, context)) void pollStatus(generation, snapshot, context)
  }, 2000)
}

function startCountdown(generation: number, context: AuthOperationContext): void {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    if (!isLifecycleCurrent(generation, context)) return
    now.value = Date.now()
  }, 1000)
}

async function createSession(): Promise<void> {
  const generation = ++lifecycleGeneration
  const context = authStore.captureAuthOperationContext()
  flowAuthContext = context
  clearTimers()
  loading.value = true
  try {
    const nextSession = await createQrLoginSession()
    if (!isLifecycleCurrent(generation, context)) {
      void Promise.resolve()
        .then(() => cancelQrLoginSession(nextSession.qrId, nextSession.pollToken))
        .catch(() => {})
      return
    }
    const nextSnapshot = snapshotSession(nextSession) as SessionSnapshot
    session.value = nextSnapshot
    status.value = nextSession.status
    message.value = ''
    now.value = Date.now()
    startCountdown(generation, context)
    schedulePoll(generation, nextSnapshot, context)
  } catch {
    if (!isLifecycleCurrent(generation, context)) return
    message.value = '二维码生成失败，请稍后重试'
    emit('notify', message.value)
    if (!isLifecycleCurrent(generation, context)) return
  } finally {
    if (isLifecycleCurrent(generation, context)) loading.value = false
  }
}

async function pollStatus(generation: number, snapshot: SessionSnapshot, context: AuthOperationContext): Promise<void> {
  if (!isLifecycleCurrent(generation, context)) return
  loading.value = true
  try {
    const response = await getQrLoginStatus(snapshot.qrId, snapshot.pollToken)
    if (!isLifecycleCurrent(generation, context)) return

    session.value = {
      qrId: response.qrId,
      pollToken: snapshot.pollToken,
      qrUrl: response.qrUrl,
      expiresAt: response.expiresAt,
      status: response.status,
    }
    status.value = response.status
    message.value = response.message || ''

    if (response.status === 'CONFIRMED') {
      if (!isLifecycleCurrent(generation, context)) return
      const login = await exchangeQrLoginSession(snapshot.qrId, snapshot.pollToken)
      if (!isLifecycleCurrent(generation, context)) return
      if (!authStore.tryLoginWithAuthResponse(login, context)) {
        isLifecycleCurrent(generation, context)
        return
      }
      if (disposed) return
      const committedGeneration = invalidateLifecycle()
      const committedContext = authStore.captureAuthOperationContext()
      emit('notify', '二维码登录成功')
      if (disposed || committedGeneration !== lifecycleGeneration
        || !authStore.isAuthOperationContextCurrent(committedContext)) return
      emit('success')
      return
    }

    if (response.status === 'EXPIRED') {
      clearTimers()
      message.value = response.message || '二维码已过期，请刷新'
      return
    }
    if (response.status === 'CANCELLED') {
      clearTimers()
      message.value = response.message || '二维码登录已取消'
      return
    }

    schedulePoll(generation, snapshot, context)
  } catch {
    if (!isLifecycleCurrent(generation, context)) return
    message.value = useMock ? '二维码状态查询失败，请稍后重试' : '二维码状态查询失败，请稍后重试'
    emit('notify', message.value)
    if (!isLifecycleCurrent(generation, context)) return
    if (useMock) schedulePoll(generation, snapshot, context)
  } finally {
    if (isLifecycleCurrent(generation, context)) loading.value = false
  }
}

async function refreshSession(): Promise<void> {
  const previous = snapshotSession(session.value)
  const generation = invalidateLifecycle()
  const refreshContext = authStore.captureAuthOperationContext()
  flowAuthContext = refreshContext
  session.value = null
  status.value = 'PENDING'
  message.value = ''
  loading.value = true
  await cancelSession(previous, true, generation, refreshContext)
  if (!isLifecycleCurrent(generation, refreshContext)) return
  await createSession()
}

async function cancelSession(
  snapshot: SessionSnapshot | null,
  notifyOnFailure: boolean,
  generation?: number,
  context?: AuthOperationContext,
): Promise<void> {
  if (!snapshot) return
  try {
    await cancelQrLoginSession(snapshot.qrId, snapshot.pollToken)
  } catch {
    const current = generation === undefined || isLifecycleCurrent(generation, context)
    if (notifyOnFailure && current) {
      message.value = '旧二维码会话取消失败，已继续生成新二维码'
      emit('notify', message.value)
      if (generation !== undefined && !isLifecycleCurrent(generation, context)) return
    } else {
      console.warn('二维码会话清理失败')
    }
  }
}

function simulateConfirm(): void {
  message.value = ''
  const generation = lifecycleGeneration
  const snapshot = snapshotSession(session.value)
  const context = flowAuthContext
  if (!snapshot || !context || !isLifecycleCurrent(generation, context)) return
  loading.value = true
  void confirmQrLoginSession(snapshot.qrId)
    .then(() => {
      if (isLifecycleCurrent(generation, context)) return pollStatus(generation, snapshot, context)
      return undefined
    })
    .catch(() => {
      if (!isLifecycleCurrent(generation, context)) return
      message.value = '模拟确认失败，请稍后重试'
    })
    .finally(() => {
      if (isLifecycleCurrent(generation, context)) loading.value = false
    })
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
  disposed = true
  const current = snapshotSession(session.value)
  invalidateLifecycle()
  session.value = null
  clearTimers()
  void cancelSession(current, false)
})
</script>

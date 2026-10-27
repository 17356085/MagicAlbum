<template>
  <section class="ui-page mx-auto max-w-xl px-4 py-10">
    <div class="ui-panel p-5 sm:p-6">
      <div class="space-y-2">
        <div class="ui-muted text-xs font-semibold uppercase tracking-[0.18em]">QR Confirm</div>
        <h1 class="ui-title">移动端确认登录</h1>
        <p class="ui-description mt-1">先扫码，再确认将当前账号用于桌面端登录。</p>
      </div>

      <div class="ui-toolbar mt-5 flex-col items-stretch border border-gray-200 bg-[var(--ui-subtle)] p-4 text-sm dark:border-gray-700 sm:flex-row sm:items-center">
        <div class="min-w-0 break-all"><span class="font-medium">会话：</span>{{ qrId || '缺少 qrId' }}</div>
        <div class="mt-2 flex items-center gap-2">
          <span class="ui-tag text-xs font-medium" :class="statusClass">{{ statusLabel }}</span>
          <span v-if="countdownLabel" class="ui-muted text-xs">{{ countdownLabel }}</span>
        </div>
      </div>

      <p v-if="message" class="mt-4 text-sm" :class="messageClass" role="status" aria-live="polite">{{ message }}</p>

      <div v-if="!qrId" class="ui-error mt-5 text-sm" role="alert">
        当前链接缺少二维码会话参数，无法继续确认。
      </div>

      <div v-else-if="!isLoggedIn" class="mt-5 border border-amber-200 bg-amber-50 p-4 dark:border-amber-900/50 dark:bg-amber-950/30">
        <div class="text-sm font-medium text-amber-700 dark:text-amber-300">请先登录移动端账号</div>
        <p class="mt-1 text-sm text-amber-600/90 dark:text-amber-200/80">登录后会继续停留在当前确认页。</p>
        <div class="mt-4">
          <button type="button" class="ui-button-primary" @click="openLogin">
            先登录
          </button>
        </div>
      </div>

      <div v-else class="mt-5 space-y-4">
        <div class="border-t border-gray-200 pt-5 dark:border-gray-700">
          <div class="text-sm font-medium">扫码确认</div>
          <p class="ui-muted mt-1 text-sm">确认后，桌面端会收到当前账号的扫码状态。</p>
          <div class="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              class="ui-button disabled:cursor-not-allowed"
              :disabled="scanning || confirming || status === 'SCANNED' || status === 'CONFIRMED' || status === 'EXPIRED' || status === 'CANCELLED'"
              @click="handleScan"
            >
              {{ scanning ? '处理中…' : '扫码并继续' }}
            </button>
            <button
              type="button"
              class="ui-button-primary disabled:cursor-not-allowed"
              :disabled="confirming || scanning || status !== 'SCANNED'"
              @click="handleConfirm"
            >
              {{ confirming ? '确认中…' : '确认登录' }}
            </button>
          </div>
        </div>

        <div v-if="status === 'CONFIRMED'" class="mt-4 border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700 dark:border-emerald-900/50 dark:bg-emerald-950/30 dark:text-emerald-300" role="status" aria-live="polite">
          已确认将当前账号用于桌面端登录，请返回发起登录的页面继续。
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useAuthStore, type AuthOperationContext } from '@/stores/auth'
import { confirmQrLoginSession, scanQrLoginSession } from '@/api/auth'
import { hasRealToken, setPendingAuthRedirect } from '@/utils/authStorage'
import type { QrLoginStatus, QrLoginStatusResponse } from '@/types'

const route = useRoute()
const authStore = useAuthStore()
const { isLoggedIn } = storeToRefs(authStore)

const qrId = computed(() => {
  const raw = route.query.qrId
  if (Array.isArray(raw)) return String(raw[0] || '')
  return String(raw || '')
})

const status = ref<QrLoginStatus>('PENDING')
const message = ref('')
const expiresAt = ref('')
const scanning = ref(false)
const confirming = ref(false)
let countdownTimer: ReturnType<typeof setInterval> | null = null
const now = ref(Date.now())

type OperationKind = 'scan' | 'confirm'
type OperationSnapshot = Readonly<{
  generation: number
  qrId: string
  context: AuthOperationContext
  kind: OperationKind
}>

let lifecycleGeneration = 0
let activeOperation: OperationSnapshot | null = null
let activeQrId = qrId.value
let disposed = false
let mounted = false

function isValidOwnerId(value: unknown): value is number | string {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0
  return typeof value === 'string' && /^[1-9]\d*$/.test(value)
}

const ownerKey = computed(() => {
  const currentUser = authStore.user
  if (!currentUser) return ''
  const ownerId = currentUser.id ?? currentUser.userId
  if (isValidOwnerId(ownerId)) return `id:${String(ownerId)}`
  return ''
})

function isValidExpiry(value: string): number | null {
  if (!value) return null
  const timestamp = new Date(value).getTime()
  return Number.isFinite(timestamp) ? timestamp : null
}

function statusMessage(nextStatus: QrLoginStatus, kind: OperationKind): string {
  if (nextStatus === 'SCANNED') return '已扫码，请确认登录'
  if (nextStatus === 'CONFIRMED') return kind === 'confirm' ? '已确认登录' : '当前账号已完成确认'
  if (nextStatus === 'EXPIRED') return '二维码已过期，请重新扫码'
  if (nextStatus === 'CANCELLED') return '二维码已取消'
  return kind === 'confirm' ? '二维码仍待确认' : '二维码等待扫码'
}

const statusLabel = computed(() => {
  if (status.value === 'SCANNED') return '已扫码'
  if (status.value === 'CONFIRMED') return '已确认'
  if (status.value === 'EXPIRED') return '已过期'
  if (status.value === 'CANCELLED') return '已取消'
  return '待确认'
})

const statusClass = computed(() => {
  if (status.value === 'SCANNED') return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
  if (status.value === 'CONFIRMED') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
  if (status.value === 'EXPIRED') return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
  if (status.value === 'CANCELLED') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
  return 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300'
})

const messageClass = computed(() => {
  if (status.value === 'EXPIRED' || status.value === 'CANCELLED') return 'text-red-600 dark:text-red-300'
  if (status.value === 'CONFIRMED') return 'text-emerald-600 dark:text-emerald-300'
  return 'text-gray-500 dark:text-gray-400'
})

const countdownLabel = computed(() => {
  if (!expiresAt.value || status.value === 'CONFIRMED' || status.value === 'CANCELLED' || status.value === 'EXPIRED') return ''
  const expiry = isValidExpiry(expiresAt.value)
  if (expiry === null) return ''
  const remaining = Math.max(0, Math.ceil((expiry - now.value) / 1000))
  return remaining > 0 ? `${remaining}s 后过期` : '即将过期'
})

function clearTimers(): void {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function startCountdown(): void {
  clearTimers()
  if (!mounted || !qrId.value) return
  countdownTimer = setInterval(() => {
    now.value = Date.now()
    expireIfNeeded()
  }, 1000)
}

function isCurrentLifecycle(operation: OperationSnapshot): boolean {
  return !disposed
    && operation.generation === lifecycleGeneration
    && operation.qrId === qrId.value
    && operation.qrId === activeQrId
}

function isCurrentAuthContext(context: AuthOperationContext): boolean {
  return hasRealToken(context.memoryToken)
    && context.memoryToken === context.persistedToken
    && authStore.isAuthOperationContextCurrent(context)
}

function isCurrentOperation(operation: OperationSnapshot): boolean {
  return isCurrentLifecycle(operation) && isCurrentAuthContext(operation.context)
}

function captureOwnedAuthContext(): AuthOperationContext | null {
  const context = authStore.captureAuthOperationContext()
  if (!isCurrentAuthContext(context)) return null
  return context
}

function resetSession(nextMessage = ''): void {
  lifecycleGeneration += 1
  activeOperation = null
  scanning.value = false
  confirming.value = false
  status.value = 'PENDING'
  message.value = nextMessage
  expiresAt.value = ''
  now.value = Date.now()
  if (mounted) startCountdown()
}

function expireIfNeeded(): void {
  if (status.value === 'CONFIRMED' || status.value === 'EXPIRED' || status.value === 'CANCELLED') return
  const expiry = isValidExpiry(expiresAt.value)
  if (expiry === null || expiry > now.value) return

  lifecycleGeneration += 1
  activeOperation = null
  scanning.value = false
  confirming.value = false
  status.value = 'EXPIRED'
  message.value = '二维码已过期，请重新扫码'
  clearTimers()
}

function beginOperation(kind: OperationKind): OperationSnapshot | null {
  if (disposed || !qrId.value || qrId.value !== activeQrId || scanning.value || confirming.value) return null
  if (kind === 'scan' && status.value !== 'PENDING') return null
  if (kind === 'confirm' && status.value !== 'SCANNED') return null
  now.value = Date.now()
  expireIfNeeded()
  if (status.value === 'EXPIRED') return null

  const context = captureOwnedAuthContext()
  if (!context) {
    resetSession('账号状态已变化，请刷新页面后重试')
    return null
  }

  const operation: OperationSnapshot = {
    generation: ++lifecycleGeneration,
    qrId: qrId.value,
    context,
    kind,
  }
  activeOperation = operation
  if (kind === 'scan') scanning.value = true
  else confirming.value = true
  return operation
}

function finishOperation(operation: OperationSnapshot): void {
  if (!isCurrentOperation(operation)) return
  if (operation.kind === 'scan') scanning.value = false
  else confirming.value = false
  if (activeOperation?.generation === operation.generation) activeOperation = null
}

function handleStaleOperation(operation: OperationSnapshot): void {
  // Storage can change without a storage event in the same tab. Clear only
  // when this operation still owns the current lifecycle; never touch a newer
  // route/account operation from an older callback.
  if (isCurrentLifecycle(operation) && !isCurrentAuthContext(operation.context)) {
    resetSession('账号状态已变化，请刷新页面后重试')
  }
}

function applyResponse(operation: OperationSnapshot, response: QrLoginStatusResponse): boolean {
  if (!isCurrentOperation(operation)) {
    handleStaleOperation(operation)
    return false
  }
  if (response.qrId !== operation.qrId) {
    message.value = '二维码会话不匹配，请重试'
    return isCurrentOperation(operation)
  }
  status.value = response.status
  message.value = response.message || statusMessage(response.status, operation.kind)
  if (response.expiresAt) expiresAt.value = response.expiresAt
  now.value = Date.now()
  expireIfNeeded()
  if (status.value === 'CONFIRMED' || status.value === 'EXPIRED' || status.value === 'CANCELLED') clearTimers()
  return isCurrentOperation(operation)
}

async function runOperation(
  kind: OperationKind,
  request: (qrId: string) => Promise<QrLoginStatusResponse>,
  failureMessage: string,
): Promise<void> {
  const operation = beginOperation(kind)
  if (!operation) return
  try {
    const response = await request(operation.qrId)
    applyResponse(operation, response)
  } catch {
    if (!isCurrentOperation(operation)) {
      handleStaleOperation(operation)
      return
    }
    message.value = failureMessage
  } finally {
    if (isCurrentOperation(operation)) finishOperation(operation)
  }
}

function handleScan(): Promise<void> {
  return runOperation('scan', scanQrLoginSession, '扫码失败，请稍后再试')
}

function handleConfirm(): Promise<void> {
  return runOperation('confirm', confirmQrLoginSession, '确认失败，请稍后再试')
}

function openLogin(): void {
  setPendingAuthRedirect(route.fullPath)
  try {
    window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source: 'qr-confirm' } }))
  } catch (_) {}
}

function handleAuthStateChanged(): void {
  if (disposed) return
  resetSession()
}

watch(qrId, (nextQrId, previousQrId) => {
  if (disposed || nextQrId === previousQrId) return
  activeQrId = nextQrId
  resetSession()
  if (!nextQrId) message.value = '当前链接缺少二维码会话参数'
}, { flush: 'sync' })

watch([() => authStore.token, ownerKey], ([nextToken, nextOwner], [previousToken, previousOwner]) => {
  if (disposed) return
  const tokenChanged = nextToken !== previousToken
  const ownerChanged = nextOwner !== previousOwner
  const sameTokenHydration = !tokenChanged && previousOwner === '' && nextOwner !== ''
  if (tokenChanged || (ownerChanged && !sameTokenHydration)) resetSession()
}, { flush: 'sync' })

onMounted(() => {
  mounted = true
  window.addEventListener('auth-state-changed', handleAuthStateChanged)
  if (!qrId.value) {
    message.value = '当前链接缺少二维码会话参数'
    return
  }
  startCountdown()
})

onUnmounted(() => {
  disposed = true
  lifecycleGeneration += 1
  activeOperation = null
  scanning.value = false
  confirming.value = false
  window.removeEventListener('auth-state-changed', handleAuthStateChanged)
  clearTimers()
})
</script>

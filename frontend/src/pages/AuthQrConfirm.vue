<template>
  <section class="ui-page mx-auto max-w-xl px-4 py-10">
    <div class="ui-panel p-5 sm:p-6">
      <div class="space-y-2">
        <div class="ui-muted text-xs font-semibold uppercase tracking-[0.18em]">QR Confirm</div>
        <h1 class="ui-title">移动端确认登录</h1>
        <p class="ui-description mt-1">确认设备信息后，再完成一次登录确认。</p>
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
          <div class="text-sm font-medium">设备确认</div>
          <p class="ui-muted mt-1 text-sm">确认后，桌面端会收到当前账号的扫码状态。</p>
          <div class="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              class="ui-button disabled:cursor-not-allowed"
              :disabled="scanning || confirming || status === 'SCANNED' || status === 'CONFIRMED' || status === 'EXPIRED' || status === 'CANCELLED'"
              @click="handleScan"
            >
              {{ scanning ? '处理中…' : '确认本设备并扫码' }}
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
          当前账号已完成确认，桌面端将自动进入登录态。
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { confirmQrLoginSession, scanQrLoginSession } from '@/api/auth'
import { setPendingAuthRedirect } from '@/utils/authStorage'
import type { QrLoginStatus } from '@/types'

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
  if (!expiresAt.value || status.value === 'CONFIRMED' || status.value === 'CANCELLED') return ''
  const remaining = Math.max(0, Math.ceil((new Date(expiresAt.value).getTime() - now.value) / 1000))
  return remaining > 0 ? `${remaining}s 后过期` : '即将过期'
})

function clearTimers(): void {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

function startCountdown(): void {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

async function handleScan(): Promise<void> {
  if (!qrId.value) return
  scanning.value = true
  try {
    const response = await scanQrLoginSession(qrId.value)
    status.value = response.status
    message.value = response.message || '已扫码，请确认登录'
    expiresAt.value = response.expiresAt || expiresAt.value
  } catch {
    message.value = '扫码失败，请稍后再试'
  } finally {
    scanning.value = false
  }
}

async function handleConfirm(): Promise<void> {
  if (!qrId.value) return
  confirming.value = true
  try {
    const response = await confirmQrLoginSession(qrId.value)
    status.value = response.status
    message.value = response.message || '已确认登录'
    expiresAt.value = response.expiresAt || expiresAt.value
  } catch {
    message.value = '确认失败，请稍后再试'
  } finally {
    confirming.value = false
  }
}

function openLogin(): void {
  setPendingAuthRedirect(route.fullPath)
  try {
    window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source: 'qr-confirm' } }))
  } catch (_) {}
}

onMounted(() => {
  if (!qrId.value) {
    message.value = '当前链接缺少二维码会话参数'
    return
  }
  startCountdown()
})

onUnmounted(() => {
  clearTimers()
})
</script>

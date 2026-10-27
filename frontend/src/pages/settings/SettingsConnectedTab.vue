<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { listConnectedAccounts } from '@/api/connected'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { ConnectedAccount } from '@/types'

interface ConnectedAccountsState {
  items: ConnectedAccount[]
}

interface SessionContext {
  token: string
  epoch: number
  ownerKey: string | null
}

const connected = ref<ConnectedAccountsState>({ items: [] })
const connectedLoading = ref(false)
const connectedError = ref('')

const authStore = useAuthStore()
const { token, user } = storeToRefs(authStore)

let disposed = false
let sessionEpoch = 0
let requestSequence = 0
let reloadTimer: ReturnType<typeof setTimeout> | null = null
let observedToken = ''
let observedStoredToken = ''
let observedOwnerKey: string | null = null
let stopSessionWatch: (() => void) | null = null

function currentToken(): string {
  return String(token.value || '').trim()
}

function storedToken(): string {
  return String(getStoredAccessToken() || '').trim()
}

function ownerKey(value: unknown): string | null {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? String(value) : null
  if (typeof value === 'string' && value.trim()) return value.trim()
  return null
}

function currentOwnerKey(): string | null {
  return ownerKey(user.value?.id ?? user.value?.userId)
}

function captureSession(): SessionContext | null {
  const accessToken = currentToken()
  if (!hasRealToken(accessToken) || accessToken !== storedToken()) return null
  return { token: accessToken, epoch: sessionEpoch, ownerKey: currentOwnerKey() }
}

function unavailableSessionMessage(): string {
  return hasRealToken(currentToken())
    ? '账号状态已变更，请刷新页面后重试。'
    : '请登录后查看关联状态。'
}

function sameOwner(expected: string | null, actual: string | null): boolean {
  // A token-only session may hydrate its owner after the request starts.
  if (expected === null) return true
  return actual !== null && actual === expected
}

function isCurrentSession(context: SessionContext): boolean {
  return !disposed
    && context.epoch === sessionEpoch
    && currentToken() === context.token
    && storedToken() === context.token
    && sameOwner(context.ownerKey, currentOwnerKey())
}

function isCurrentRequest(context: SessionContext, sequence: number): boolean {
  return isCurrentSession(context) && sequence === requestSequence
}

function invalidateOperations(): void {
  sessionEpoch += 1
  requestSequence += 1
}

function resetConnectedState(): void {
  invalidateOperations()
  connected.value = { items: [] }
  connectedLoading.value = false
  connectedError.value = captureSession() ? '' : unavailableSessionMessage()
}

function scheduleReload(): void {
  if (disposed || reloadTimer !== null) return
  reloadTimer = setTimeout(() => {
    reloadTimer = null
    if (!disposed && captureSession()) void loadConnected()
  }, 0)
}

function observeSessionChange(): boolean {
  const nextToken = currentToken()
  const nextStoredToken = storedToken()
  const nextOwnerKey = currentOwnerKey()
  const tokenChanged = nextToken !== observedToken || nextStoredToken !== observedStoredToken
  const sameTokenHydration = !tokenChanged && observedOwnerKey === null && nextOwnerKey !== null
  const ownerChanged = !sameTokenHydration && nextOwnerKey !== observedOwnerKey
  observedToken = nextToken
  observedStoredToken = nextStoredToken
  observedOwnerKey = nextOwnerKey
  return tokenChanged || ownerChanged
}

function handleSessionSignal(): void {
  if (disposed || !observeSessionChange()) return
  resetConnectedState()
  scheduleReload()
}

async function loadConnected(): Promise<void> {
  if (disposed || connectedLoading.value) return

  const sequence = ++requestSequence
  const context = captureSession()
  if (!context) {
    if (!disposed && sequence === requestSequence) {
      connected.value = { items: [] }
      connectedLoading.value = false
      connectedError.value = unavailableSessionMessage()
    }
    return
  }

  connectedLoading.value = true
  connectedError.value = ''
  try {
    const data = await listConnectedAccounts()
    if (!isCurrentRequest(context, sequence)) return
    connected.value = { items: Array.isArray(data?.items) ? data.items : [] }
  } catch (_) {
    if (!isCurrentRequest(context, sequence)) return
    connectedError.value = '关联状态加载失败，暂时无法确认账号状态。'
  } finally {
    if (!disposed && sequence === requestSequence) {
      if (!isCurrentSession(context)) {
        connected.value = { items: [] }
        connectedError.value = unavailableSessionMessage()
      }
      connectedLoading.value = false
    }
  }
}

function providerLabel(provider: string): string {
  const labels: Record<string, string> = {
    google: 'Google',
    github: 'GitHub',
    apple: 'Apple',
    wechat: '微信',
  }
  const displayProvider = provider.trim()
  return labels[displayProvider.toLowerCase()] || displayProvider
}

function statusLabel(account: ConnectedAccount): string {
  return account.connected === true ? '该第三方账号已与当前账号关联。' : '尚未关联到当前账号。'
}

function onAuthStateChanged(): void {
  if (disposed) return
  // An explicit event represents a fresh session even when its token is reused.
  observeSessionChange()
  resetConnectedState()
  scheduleReload()
}

stopSessionWatch = watch(
  [() => currentToken(), () => storedToken(), () => currentOwnerKey()],
  () => handleSessionSignal(),
  { flush: 'sync' },
)

onMounted(() => {
  observedToken = currentToken()
  observedStoredToken = storedToken()
  observedOwnerKey = currentOwnerKey()
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  void loadConnected()
})

onBeforeUnmount(() => {
  disposed = true
  if (reloadTimer !== null) {
    clearTimeout(reloadTimer)
    reloadTimer = null
  }
  invalidateOperations()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  stopSessionWatch?.()
  stopSessionWatch = null
})
</script>

<template>
  <section aria-labelledby="connected-settings-title" class="space-y-4">
    <h2 id="connected-settings-title" class="text-base font-semibold">第三方关联</h2>
    <p class="ui-muted border border-gray-200 bg-[var(--ui-subtle)] p-4 text-sm leading-6 dark:border-gray-700">
      此页面用于查看第三方账号的关联状态，暂不提供绑定或解绑操作。
    </p>
    <div>
      <div v-if="connectedLoading" class="ui-muted text-sm" role="status" aria-live="polite">正在加载关联状态...</div>
      <div v-else-if="connectedError" class="ui-error flex items-center justify-between gap-3 text-sm" role="alert">
        <span>{{ connectedError }}</span>
        <button type="button" class="ui-button min-h-11 shrink-0 px-3 py-1.5 text-xs" :disabled="connectedLoading" @click="loadConnected">
          {{ connectedLoading ? '重试中...' : '重试' }}
        </button>
      </div>
      <div v-else-if="!(connected.items || []).length" class="ui-empty border border-dashed border-gray-200 dark:border-gray-700">
        暂无已关联账号。当前页面暂不提供绑定操作。
      </div>
      <ul v-else class="divide-y divide-gray-200 text-sm dark:divide-gray-700" aria-label="已关联账号状态">
        <li v-for="account in connected.items || []" :key="account.provider" class="ui-list-row flex flex-col gap-3 px-0 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div class="font-medium">{{ providerLabel(account.provider) }}</div>
            <div class="ui-muted mt-1 text-xs">{{ statusLabel(account) }}</div>
          </div>
          <span :class="account.connected === true ? 'text-[var(--ui-accent)]' : 'ui-muted'" class="ui-tag w-fit text-[11px]">
            {{ account.connected === true ? '已关联' : '未关联' }}
          </span>
        </li>
      </ul>
    </div>
  </section>
</template>

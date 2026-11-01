<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { disconnectAccount, listConnectedAccounts } from '@/api/connected'
import type { DisconnectAccountProof } from '@/api/connected'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { ConnectedAccount } from '@/types'
import ConnectedAccountUnlinkForm from './ConnectedAccountUnlinkForm.vue'

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
const disconnectProvider = ref<string | null>(null)
const disconnectError = ref('')
const disconnectPending = ref(false)
const disconnectFeedback = ref('')
const pendingReadback = ref<string | null>(null)

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
let disconnectSequence = 0
let disconnectContext: SessionContext | null = null
let disconnectBindingTag: string | null = null
let disclosureButton: HTMLElement | null = null

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
  clearDisconnectForm()
  disconnectFeedback.value = ''
  pendingReadback.value = null
  connected.value = { items: [] }
  connectedLoading.value = false
  connectedError.value = captureSession() ? '' : unavailableSessionMessage()
}

function clearDisconnectForm(): void {
  disconnectSequence += 1
  disconnectProvider.value = null
  disconnectError.value = ''
  disconnectPending.value = false
  disconnectContext = null
  disconnectBindingTag = null
  disclosureButton = null
}

function canDisconnect(account: ConnectedAccount): boolean {
  return account.connected === true && typeof account.bindingTag === 'string' && /^[0-9a-f]{64}$/.test(account.bindingTag)
}

function providerKey(provider: string): string {
  return provider.trim().toLowerCase()
}

function beginDisconnect(account: ConnectedAccount, event: Event): void {
  const context = captureSession()
  if (!context || context.ownerKey === null || !canDisconnect(account)) {
    disconnectFeedback.value = '账号信息尚未就绪，请刷新后重试。'
    return
  }
  clearDisconnectForm()
  disconnectFeedback.value = ''
  disconnectProvider.value = providerKey(account.provider)
  disconnectContext = context
  disconnectBindingTag = account.bindingTag ?? null
  disclosureButton = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  void nextTick(() => document.getElementById('connected-disconnect-identifier')?.focus())
}

function cancelDisconnect(): void {
  if (disconnectPending.value) return
  const previousButton = disclosureButton
  clearDisconnectForm()
  void nextTick(() => { if (previousButton?.isConnected) previousButton.focus() })
}

function statusCode(error: unknown): number | null {
  if (typeof error !== 'object' || error === null || !('response' in error)) return null
  const response = error.response
  if (typeof response !== 'object' || response === null || !('status' in response)) return null
  return typeof response.status === 'number' ? response.status : null
}

async function submitDisconnect(proof: Omit<DisconnectAccountProof, 'bindingTag'>): Promise<void> {
  if (disconnectPending.value || !disconnectProvider.value || !disconnectContext || !disconnectBindingTag) return
  const context = disconnectContext
  if (!isCurrentSession(context)) {
    resetConnectedState()
    return
  }
  const provider = disconnectProvider.value
  const sequence = ++disconnectSequence
  disconnectError.value = ''
  disconnectPending.value = true
  try {
    await disconnectAccount(provider, { ...proof, bindingTag: disconnectBindingTag }, context.token)
    if (!isCurrentSession(context) || sequence !== disconnectSequence) return
    disconnectProvider.value = null
    disconnectContext = null
    disconnectBindingTag = null
    pendingReadback.value = provider
    disconnectFeedback.value = '解绑请求已完成，正在刷新关联状态。'
    // A read started before the write may still contain the old binding.
    requestSequence += 1
    connectedLoading.value = false
    await loadConnected()
    if (isCurrentSession(context) && sequence === disconnectSequence) {
      void nextTick(() => document.getElementById('connected-settings-title')?.focus())
    }
  } catch (error) {
    if (!isCurrentSession(context) || sequence !== disconnectSequence) return
    const status = statusCode(error)
    disconnectError.value = status === 400
      ? '邮箱、手机号或密码不正确，请检查后重试。'
      : status === 409
        ? '关联状态已变更，请刷新后重试。'
        : status === 429
        ? '操作过于频繁，请稍后再试。'
        : '暂时无法确认解绑结果，请刷新关联状态后再试。'
  } finally {
    if (!disposed && sequence === disconnectSequence) {
      if (!isCurrentSession(context)) resetConnectedState()
      else disconnectPending.value = false
    }
  }
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
    if (disconnectProvider.value) {
      const provider = disconnectProvider.value
      const current = connected.value.items.find(item => providerKey(item.provider) === provider)
      if (!current) {
        clearDisconnectForm()
        disconnectFeedback.value = '关联列表未返回该平台，请稍后重试。'
      } else if (!current.connected) {
        clearDisconnectForm()
        disconnectFeedback.value = '该账号当前未关联。'
      } else if (current.bindingTag !== disconnectBindingTag) {
        clearDisconnectForm()
        disconnectFeedback.value = '关联状态已变更，请重新确认。'
      }
    }
    if (pendingReadback.value) {
      const provider = pendingReadback.value
      const current = connected.value.items.find(item => providerKey(item.provider) === provider)
      pendingReadback.value = null
      disconnectFeedback.value = !current
        ? '解绑请求已完成；关联列表未返回该平台，请刷新确认。'
        : current.connected ? '账号当前仍显示关联，请刷新后确认。' : '解绑完成。'
    }
  } catch (_) {
    if (!isCurrentRequest(context, sequence)) return
    connectedError.value = '关联状态加载失败，暂时无法确认账号状态。'
    if (pendingReadback.value) disconnectFeedback.value = '解绑请求已完成，但关联状态刷新失败，请重试。'
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
  return labels[providerKey(provider)] || displayProvider
}

function statusLabel(account: ConnectedAccount): string {
  if (!account.connected) return '尚未关联到当前账号。'
  return canDisconnect(account) ? '该第三方账号已与当前账号关联。' : '该账号已关联，当前服务暂不支持安全解绑。'
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
  clearDisconnectForm()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  stopSessionWatch?.()
  stopSessionWatch = null
})
</script>

<template>
  <section aria-labelledby="connected-settings-title" class="space-y-4">
    <h2 id="connected-settings-title" class="text-base font-semibold" tabindex="-1">第三方关联</h2>
    <p class="ui-muted border border-gray-200 bg-[var(--ui-subtle)] p-4 text-sm leading-6 dark:border-gray-700">
      可使用本地邮箱或手机号及密码验证后解绑。仅通过验证码或第三方登录的账号暂不支持解绑；绑定功能尚未开放。
    </p>
    <p v-if="disconnectFeedback" class="text-sm" role="status" aria-live="polite">{{ disconnectFeedback }}</p>
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
        <li v-for="account in connected.items || []" :key="account.provider" class="ui-list-row px-0 py-4">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div class="font-medium">{{ providerLabel(account.provider) }}</div>
              <div class="ui-muted mt-1 text-xs">{{ statusLabel(account) }}</div>
            </div>
            <div class="flex items-center gap-3">
              <span :class="account.connected === true ? 'text-[var(--ui-accent)]' : 'ui-muted'" class="ui-tag w-fit text-[11px]">
                {{ account.connected === true ? '已关联' : '未关联' }}
              </span>
              <button v-if="canDisconnect(account) && disconnectProvider !== providerKey(account.provider)" type="button"
                      class="ui-button min-h-11 px-3 text-xs" :disabled="disconnectProvider !== null || disconnectPending"
                      :aria-label="`解绑 ${providerLabel(account.provider)}`" @click="beginDisconnect(account, $event)">
                解绑
              </button>
            </div>
          </div>
          <ConnectedAccountUnlinkForm v-if="disconnectProvider === providerKey(account.provider)"
                                      :pending="disconnectPending || connectedLoading" :error="disconnectError"
                                      @submit="submitDisconnect" @cancel="cancelDisconnect"
                                      @refresh="loadConnected" @edit="disconnectError = ''" />
        </li>
      </ul>
    </div>
  </section>
</template>

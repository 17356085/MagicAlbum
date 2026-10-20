<script setup lang="ts">
import { isAxiosError } from 'axios'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  cancelSummary,
  createSummaryRequestKey,
  getSummary,
  getSummaryTask,
  triggerSummary,
  type AiSummary,
  type AiSummaryStatus,
} from '@/api/ai'
import { useAuthStore } from '@/stores/auth'
import type { Id } from '@/types'

const props = defineProps<{
  threadId?: Id | null
}>()

const POLL_DELAY_MS = 2000
const POLL_MAX_DURATION_MS = 120000

type SummaryContext = {
  epoch: number
  threadId: Id
  authGeneration: number
  token: string
  contentVersion: number | null
}

const authStore = useAuthStore()
const { isLoggedIn, token, user } = storeToRefs(authStore)
const aiData = ref<AiSummary | null>(null)
const aiLoading = ref(false)
const aiBusy = ref(false)
const aiMessage = ref('')
const canManageFallback = ref(false)
const activeTaskId = ref<string | null>(null)
const pendingRequest = ref<{ key: string; force: boolean } | null>(null)
const pollingPaused = ref(false)

const aiSummary = computed(() => aiData.value?.summary || '')
const aiStatus = computed<AiSummaryStatus | ''>(() => aiData.value?.status || '')
const canManage = computed(() => aiData.value?.canManage ?? canManageFallback.value)
const isPending = computed(() => aiStatus.value === 'PENDING' || aiStatus.value === 'PROCESSING')
const canCancel = computed(() => isPending.value && Boolean(activeTaskId.value || aiData.value?.taskId))
const showSection = computed(() => Boolean(aiData.value || aiLoading.value || aiMessage.value))
const mockProvider = computed(() => aiData.value?.provider?.toLowerCase() === 'mock')
const stateMessage = computed(() => {
  if (aiStatus.value === 'PENDING') return '摘要任务已排队，完成后会自动更新。'
  if (aiStatus.value === 'PROCESSING') return '正在生成摘要，请稍候。'
  if (aiStatus.value === 'FAILED') {
    if (isQuotaError(aiData.value?.errorCode)) return '本次摘要生成额度已用完，请稍后再试。'
    if (aiData.value?.errorCode === 'PERMISSION_REVOKED') return '当前账号没有管理此摘要的权限。'
    if (aiData.value?.errorCode === 'CONTENT_CHANGED') return '帖子内容已更新，请刷新后重新生成摘要。'
    return '摘要生成失败，可以重试。'
  }
  if (aiStatus.value === 'CANCELLED') return '已取消摘要生成。'
  if (aiStatus.value === 'SUPERSEDED') return '帖子内容已更新，请刷新后重新生成摘要。'
  if (aiStatus.value === 'IDLE' && !aiSummary.value) return '还没有可用摘要。'
  return ''
})
const displayMessage = computed(() => aiMessage.value || stateMessage.value)

let epoch = 0
let disposed = false
let activeRequestController: AbortController | null = null
let pollController: AbortController | null = null
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollRun = 0
let actionRun = 0
let loadRun = 0
let stopAuthActionSubscription: (() => void) | null = null
let seenAuthGeneration = authStore.getAuthGeneration()
let seenToken = token.value
let seenUserId = user.value?.id

function sameThread(left: Id, right: Id): boolean {
  return String(left) === String(right)
}

function createContext(threadId: Id): SummaryContext {
  return {
    epoch,
    threadId,
    authGeneration: authStore.getAuthGeneration(),
    token: token.value,
    contentVersion: null,
  }
}

function isCurrent(context: SummaryContext): boolean {
  return !disposed
    && context.epoch === epoch
    && props.threadId != null
    && sameThread(props.threadId, context.threadId)
    && token.value === context.token
    && authStore.isAuthGenerationCurrent(context.authGeneration)
}

function isAbortError(error: unknown): boolean {
  if (isAxiosError(error) && error.code === 'ERR_CANCELED') return true
  if (error instanceof DOMException && error.name === 'AbortError') return true
  return typeof error === 'object' && error !== null && 'name' in error && (error as { name?: unknown }).name === 'AbortError'
}

function stopPolling(): void {
  pollRun += 1
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollController?.abort()
  pollController = null
}

function resetState(): void {
  epoch += 1
  actionRun += 1
  loadRun += 1
  activeRequestController?.abort()
  activeRequestController = null
  stopPolling()
  aiData.value = null
  aiLoading.value = false
  aiBusy.value = false
  aiMessage.value = ''
  canManageFallback.value = false
  activeTaskId.value = null
  pendingRequest.value = null
  pollingPaused.value = false
}

function responseStatus(error: unknown): number {
  if (isAxiosError(error)) return Number(error.response?.status || 0)
  if (typeof error !== 'object' || error === null || !('response' in error)) return 0
  const response = (error as { response?: { status?: unknown } }).response
  return Number(response?.status || 0)
}

function responseCode(error: unknown): string {
  if (!isAxiosError(error)) return ''
  const data = error.response?.data
  if (typeof data !== 'object' || data === null) return ''
  const root = data as { code?: unknown; errorCode?: unknown }
  const code = root.errorCode ?? root.code
  return typeof code === 'string' ? code : ''
}

function isQuotaError(code?: string | null): boolean {
  return Boolean(code && /quota|budget|limit/i.test(code))
}

function errorMessage(error: unknown, action: 'load' | 'generate' | 'cancel'): string {
  const status = responseStatus(error)
  const code = responseCode(error)
  if (status === 401) return '登录后可以查看和生成 AI 摘要。'
  if (status === 403) return action === 'load' ? '当前账号暂时无法读取摘要。' : '当前账号没有管理此摘要的权限。'
  if (status === 409) return '已有摘要任务正在执行，请先停止当前任务或稍后刷新。'
  if (status === 429 || isQuotaError(code)) return '本次摘要生成额度已用完，请稍后再试。'
  if (action === 'cancel') return '暂时无法停止摘要生成，请稍后重试。'
  if (action === 'load') return '摘要状态暂时无法读取，请稍后刷新。'
  return '摘要生成失败，请稍后重试。'
}

function isUncertainGenerateFailure(error: unknown): boolean {
  if (isAbortError(error)) return false
  if (typeof error === 'object' && error !== null && 'name' in error
    && (error as { name?: unknown }).name === 'AiSummaryResponseError') {
    return false
  }
  const status = responseStatus(error)
  return status === 0 || status >= 500
}

function isTerminal(status: AiSummaryStatus): boolean {
  return status === 'IDLE'
    || status === 'COMPLETED'
    || status === 'FAILED'
    || status === 'CANCELLED'
    || status === 'SUPERSEDED'
}

function acceptsSummary(context: SummaryContext, data: AiSummary, expectedTaskId?: string): boolean {
  if (!sameThread(data.threadId, context.threadId)) return false
  if (context.contentVersion !== null && data.contentVersion !== context.contentVersion) return false
  if (expectedTaskId && data.taskId !== expectedTaskId) return false
  if (data.taskContentVersion !== null && data.taskContentVersion !== data.contentVersion) return false
  if (context.contentVersion === null) context.contentVersion = data.contentVersion
  return true
}

function applySummary(context: SummaryContext, data: AiSummary, expectedTaskId?: string): boolean {
  if (!isCurrent(context) || !acceptsSummary(context, data, expectedTaskId)) return false
  if (data.status === 'FAILED') pendingRequest.value = null
  aiData.value = data
  aiMessage.value = ''
  canManageFallback.value = data.canManage
  activeTaskId.value = data.taskId
  if (data.status === 'COMPLETED' || data.status === 'FAILED' || data.status === 'CANCELLED' || data.status === 'SUPERSEDED') {
    aiLoading.value = false
    pollingPaused.value = false
  }
  return true
}

function markContentChanged(): void {
  if (aiData.value) {
    aiData.value = {
      ...aiData.value,
      taskId: null,
      status: 'SUPERSEDED',
      summary: '',
      errorCode: 'CONTENT_CHANGED',
    }
  }
  activeTaskId.value = null
  aiLoading.value = false
  pollingPaused.value = true
}

async function loadSummary(context: SummaryContext): Promise<void> {
  const loadId = ++loadRun
  const controller = new AbortController()
  activeRequestController?.abort()
  activeRequestController = controller
  try {
    const data = await getSummary(context.threadId, controller.signal)
    if (controller.signal.aborted || loadId !== loadRun || !isCurrent(context)) return
    if (!applySummary(context, data)) {
      aiLoading.value = false
      aiMessage.value = '摘要版本已变化，请刷新后查看最新状态。'
      return
    }
    if (!isTerminal(data.status) && data.taskId) {
      startPolling(context, data.taskId, actionRun)
    }
  } catch (error) {
    if (controller.signal.aborted || loadId !== loadRun || !isCurrent(context) || isAbortError(error)) return
    aiData.value = null
    aiLoading.value = false
    canManageFallback.value = false
    activeTaskId.value = null
    pollingPaused.value = false
    aiMessage.value = errorMessage(error, 'load')
  } finally {
    if (activeRequestController === controller) activeRequestController = null
  }
}

function waitForPollDelay(milliseconds: number, signal: AbortSignal): Promise<boolean> {
  if (signal.aborted) return Promise.resolve(false)
  return new Promise((resolve) => {
    const finish = (result: boolean) => {
      signal.removeEventListener('abort', onAbort)
      if (pollTimer) {
        clearTimeout(pollTimer)
        pollTimer = null
      }
      resolve(result)
    }
    const onAbort = () => finish(false)
    signal.addEventListener('abort', onAbort, { once: true })
    pollTimer = setTimeout(() => finish(true), milliseconds)
  })
}

function pausePolling(context: SummaryContext, run: number, message: string): void {
  if (!isCurrent(context) || run !== pollRun) return
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  pollController?.abort()
  pollController = null
  pollRun += 1
  pollingPaused.value = true
  aiLoading.value = false
  aiMessage.value = message
}

function startPolling(context: SummaryContext, taskId: string, actionId: number): void {
  stopPolling()
  const controller = new AbortController()
  pollController = controller
  const run = pollRun
  const startedAt = Date.now()
  aiLoading.value = true
  pollingPaused.value = false

  const poll = async (): Promise<void> => {
    while (isCurrent(context) && run === pollRun && actionId === actionRun && !controller.signal.aborted) {
      const elapsed = Date.now() - startedAt
      if (elapsed >= POLL_MAX_DURATION_MS) {
        pausePolling(context, run, '任务仍在执行，稍后可刷新查看状态。')
        return
      }

      let data: AiSummary
      try {
        data = await getSummaryTask(taskId, controller.signal)
      } catch (error) {
        if (!isCurrent(context) || run !== pollRun || isAbortError(error)) return
        aiMessage.value = errorMessage(error, 'load')
        const remaining = POLL_MAX_DURATION_MS - (Date.now() - startedAt)
        if (remaining <= 0 || !(await waitForPollDelay(Math.min(POLL_DELAY_MS, remaining), controller.signal))) return
        continue
      }

      if (!isCurrent(context) || run !== pollRun || actionId !== actionRun) return
      if (!applySummary(context, data, taskId)) {
        pausePolling(context, run, '摘要版本已变化，请刷新后查看最新状态。')
        return
      }
      if (isTerminal(data.status)) {
        stopPolling()
        aiLoading.value = false
        return
      }

      const remaining = POLL_MAX_DURATION_MS - (Date.now() - startedAt)
      if (remaining <= 0 || !(await waitForPollDelay(Math.min(POLL_DELAY_MS, remaining), controller.signal))) return
    }
  }

  void poll()
}

async function handleGenerateSummary(retry = false): Promise<void> {
  const threadId = props.threadId
  if (threadId == null || !canManage.value || aiBusy.value || isPending.value) return
  if (!isLoggedIn.value) {
    aiMessage.value = '登录后可以生成 AI 摘要。'
    try {
      window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source: 'ai-summary' } }))
    } catch (_) {}
    return
  }

  const context = createContext(threadId)
  context.contentVersion = aiData.value?.contentVersion ?? null
  const actionId = ++actionRun
  stopPolling()
  activeTaskId.value = null
  aiBusy.value = true
  aiLoading.value = true
  aiMessage.value = ''
  pollingPaused.value = false

  const reusableRequest = retry && aiStatus.value === 'FAILED' ? pendingRequest.value : null
  const force = reusableRequest?.force
    ?? (retry || aiStatus.value === 'COMPLETED' || aiStatus.value === 'FAILED' || aiStatus.value === 'CANCELLED')
  const key = reusableRequest?.key ?? createSummaryRequestKey()
  pendingRequest.value = { key, force }
  if (aiData.value) {
    aiData.value = { ...aiData.value, taskId: null, status: 'PENDING', summary: '', errorCode: null }
  }

  const controller = new AbortController()
  activeRequestController = controller
  try {
    const data = await triggerSummary(threadId, force, key, controller.signal)
    if (!isCurrent(context) || actionId !== actionRun) return
    // A decoded response proves that the server saw this request. A later
    // retry after a real FAILED response must receive a fresh idempotency key.
    pendingRequest.value = null
    if (!applySummary(context, data)) {
      markContentChanged()
      aiMessage.value = '帖子内容已变化，请刷新后重新生成摘要。'
      return
    }
    if (!isTerminal(data.status) && data.taskId) {
      startPolling(context, data.taskId, actionId)
    } else {
      aiLoading.value = false
    }
  } catch (error) {
    if (!isCurrent(context) || actionId !== actionRun || isAbortError(error)) return
    const forbidden = responseStatus(error) === 403
    if (aiData.value) {
      aiData.value = {
        ...aiData.value,
        taskId: null,
        status: 'FAILED',
        summary: '',
        canManage: forbidden ? false : aiData.value.canManage,
        errorCode: responseCode(error) || null,
      }
    }
    if (forbidden) canManageFallback.value = false
    if (isUncertainGenerateFailure(error)) {
      // Keep the exact key and force flag only when the response was uncertain
      // (network failure or a 5xx). The next click can safely replay it.
    } else {
      pendingRequest.value = null
    }
    aiLoading.value = false
    aiMessage.value = errorMessage(error, 'generate')
  } finally {
    if (activeRequestController === controller) activeRequestController = null
    if (isCurrent(context) && actionId === actionRun) aiBusy.value = false
  }
}

async function handleCancelSummary(): Promise<void> {
  const threadId = props.threadId
  const taskId = activeTaskId.value || aiData.value?.taskId
  if (threadId == null || !taskId || aiBusy.value || !isPending.value) return

  const context = createContext(threadId)
  context.contentVersion = aiData.value?.contentVersion ?? null
  const actionId = ++actionRun
  stopPolling()
  aiBusy.value = true
  aiMessage.value = ''
  const controller = new AbortController()
  activeRequestController?.abort()
  activeRequestController = controller
  try {
    const data = await cancelSummary(threadId, taskId, controller.signal)
    if (!isCurrent(context) || actionId !== actionRun) return
    if (!applySummary(context, data, taskId)) {
      aiLoading.value = false
      aiMessage.value = '摘要版本已变化，请刷新后查看最新状态。'
      return
    }
    aiLoading.value = false
  } catch (error) {
    if (!isCurrent(context) || actionId !== actionRun || isAbortError(error)) return
    if (responseStatus(error) === 403) {
      if (aiData.value) aiData.value = { ...aiData.value, canManage: false }
      canManageFallback.value = false
      aiLoading.value = false
      aiMessage.value = errorMessage(error, 'cancel')
      return
    }
    aiMessage.value = errorMessage(error, 'cancel')
    if (activeTaskId.value) startPolling(context, activeTaskId.value, actionId)
  } finally {
    if (activeRequestController === controller) activeRequestController = null
    if (isCurrent(context) && actionId === actionRun) aiBusy.value = false
  }
}

async function refreshSummary(): Promise<void> {
  if (aiBusy.value || props.threadId == null) return
  stopPolling()
  aiMessage.value = ''
  pollingPaused.value = false
  const context = createContext(props.threadId)
  await loadSummary(context)
}

function handleAuthContextChange(): void {
  const nextGeneration = authStore.getAuthGeneration()
  const nextToken = token.value
  const nextUserId = user.value?.id
  if (nextGeneration === seenAuthGeneration && nextToken === seenToken && nextUserId === seenUserId) return
  seenAuthGeneration = nextGeneration
  seenToken = nextToken
  seenUserId = nextUserId
  resetState()
  if (props.threadId != null) void loadSummary(createContext(props.threadId))
}

watch(
  () => props.threadId,
  (threadId) => {
    resetState()
    if (threadId != null) void loadSummary(createContext(threadId))
  },
  { immediate: true },
)

watch([token, () => user.value?.id], () => {
  handleAuthContextChange()
})

onMounted(() => {
  window.addEventListener('auth-state-changed', handleAuthContextChange)
  stopAuthActionSubscription = authStore.$onAction(({ name, after }) => {
    if (name === 'invalidateAuthOperations') {
      after(() => queueMicrotask(handleAuthContextChange))
    }
  })
})

onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('auth-state-changed', handleAuthContextChange)
  stopAuthActionSubscription?.()
  stopAuthActionSubscription = null
  resetState()
})
</script>

<template>
  <section
    v-if="showSection"
    class="border-b border-[var(--ui-border)] bg-[var(--ui-accent-soft)] px-5 py-4 sm:px-8"
    aria-label="AI 智能摘要"
  >
    <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
      <div class="flex items-center gap-2">
        <span class="ui-tag !rounded-full !bg-[var(--ui-surface)] !text-[var(--ui-accent)]">AI 摘要</span>
        <span v-if="mockProvider" class="ui-muted text-xs" role="note">测试模式</span>
      </div>
      <div v-if="canManage || pollingPaused" class="flex flex-wrap items-center justify-end gap-2">
        <template v-if="canManage">
          <button
            v-if="canCancel"
            type="button"
            class="ui-button min-h-11 px-3 py-1 text-xs"
            :disabled="aiBusy"
            @click="handleCancelSummary"
          >
            {{ aiBusy ? '处理中...' : '停止生成' }}
          </button>
          <button
            v-else-if="!isPending && !pollingPaused"
            type="button"
            class="ui-button min-h-11 px-3 py-1 text-xs"
            :disabled="aiBusy"
            @click="handleGenerateSummary(aiStatus === 'FAILED')"
          >
            {{ aiStatus === 'FAILED' ? '重试生成' : aiStatus === 'IDLE' ? '生成 AI 摘要' : '重新生成' }}
          </button>
        </template>
        <button
          v-if="pollingPaused"
          type="button"
          class="ui-button min-h-11 px-3 py-1 text-xs"
          :disabled="aiBusy"
          @click="refreshSummary"
        >
          刷新状态
        </button>
      </div>
    </div>
    <p v-if="aiStatus === 'COMPLETED' && aiSummary" class="max-w-3xl text-sm leading-6 text-[var(--ui-ink)]">
      {{ aiSummary }}
    </p>
    <p v-else class="max-w-3xl text-sm leading-6 text-[var(--ui-ink)]" role="status" aria-live="polite">
      {{ aiLoading && isPending ? (aiStatus === 'PROCESSING' ? '正在生成摘要，请稍候...' : '摘要任务已排队，请稍候...') : displayMessage }}
    </p>
    <p v-if="aiMessage && aiMessage !== stateMessage" class="ui-muted mt-2 text-xs" role="alert">
      {{ aiMessage }}
    </p>
  </section>
  <section v-else-if="canManage" class="flex justify-end border-b border-[var(--ui-border)] px-5 py-3 sm:px-8">
    <button type="button" class="ui-button min-h-11 px-3 py-1 text-xs" @click="handleGenerateSummary()">
      生成 AI 摘要
    </button>
  </section>
</template>

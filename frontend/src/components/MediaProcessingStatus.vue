<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { changeMediaJob, getMediaAsset } from '@/api/uploads'
import { dismissMediaAsset, isCurrentMediaCacheOwner, mediaAssets, updateMediaAsset, updateMediaAssetIfCurrent, type MediaAssetState } from '@/composables/mediaProcessingState'
import { hasRealToken } from '@/utils/authStorage'
import { normalizeImageUrl } from '@/utils/image'

const auth = useAuthStore()
defineProps<{ raised?: boolean }>()
const expanded = ref(false)
const pendingAction = ref('')
const error = ref('')
type RowFeedback = { kind: 'success' | 'error'; message: string }
type RefreshOperation = {
  sequence: number
  assetId: string
  token: string
  context: ReturnType<typeof auth.captureAuthOperationContext>
  controller: AbortController
}
const feedback = ref<Record<string, RowFeedback>>({})
const refreshingAssetId = ref('')
let activeRefresh: RefreshOperation | null = null
let actionSequence = 0
let refreshSequence = 0

function clearFeedback(assetId?: string): void {
  if (assetId === undefined) {
    feedback.value = {}
    return
  }
  const next = { ...feedback.value }
  delete next[assetId]
  feedback.value = next
}
function setFeedback(assetId: string, next: RowFeedback): void {
  feedback.value = { ...feedback.value, [assetId]: next }
}
function cancelRefresh(): void {
  if (activeRefresh) activeRefresh.controller.abort()
  activeRefresh = null
  refreshSequence += 1
  refreshingAssetId.value = ''
}
function isRefreshPending(assetId: string): boolean {
  return refreshingAssetId.value === assetId
}
const statusCheckPending = computed(() => refreshingAssetId.value !== '')
function currentItem(assetId: string): MediaAssetState | undefined {
  return mediaAssets.value.find(item => item.assetId === assetId)
}
function isAuthContextCurrent(context: ReturnType<typeof auth.captureAuthOperationContext>): boolean {
  return hasRealToken(context.memoryToken)
    && context.memoryToken === context.persistedToken
    && auth.isAuthOperationContextCurrent(context)
}

watch(() => auth.token, () => {
  actionSequence += 1
  cancelRefresh()
  clearFeedback()
  pendingAction.value = ''
  error.value = ''
  expanded.value = false
})
watch(() => mediaAssets.value.map(item => item.assetId), (assetIds) => {
  const ids = new Set(assetIds)
  if (activeRefresh && !ids.has(activeRefresh.assetId)) cancelRefresh()
  const next = { ...feedback.value }
  let changed = false
  for (const assetId of Object.keys(next)) {
    if (!ids.has(assetId)) {
      delete next[assetId]
      changed = true
    }
  }
  if (changed) feedback.value = next
}, { flush: 'post' })
const pending = computed(() => mediaAssets.value.filter(item => ['PENDING', 'PROCESSING'].includes(item.processingStatus)).length)
const summary = computed(() => pending.value ? `图片预览处理中 · ${pending.value}` : '图片状态')
function label(item: MediaAssetState): string {
  if (item.status === 'UPLOADING') return '正在确认原图上传'
  if (item.status === 'UPLOAD_FAILED') return '原图上传未确认，请重新上传'
  if (item.unavailable) return '暂时无法更新状态，原图仍可使用'
  if (item.processingStatus === 'SUCCEEDED') return '预览已就绪'
  if (item.processingStatus === 'FAILED') return '预览未生成，原图仍可使用'
  if (item.processingStatus === 'CANCELLED') return '已停止处理，原图仍可使用'
  if (item.processingStatus === 'UNSUPPORTED') return '已上传原图'
  if (item.processingStatus === 'NOT_REQUESTED') return '原图已上传，未请求生成预览'
  return '原图已上传，正在生成预览'
}
function errorStatus(cause: unknown): number {
  const raw = (cause as { response?: { status?: unknown } } | null)?.response?.status
  const status = Number(raw)
  return Number.isFinite(status) ? status : 0
}
function mediaHref(url?: string | null): string {
  return url?.startsWith('/uploads/') ? normalizeImageUrl(url) : url || ''
}
async function action(item: MediaAssetState, kind: 'retry' | 'cancel'): Promise<void> {
  if (pendingAction.value || activeRefresh) return
  const context = auth.captureAuthOperationContext()
  const token = context.memoryToken
  if (currentItem(item.assetId) !== item) {
    error.value = '状态已变化，请先查看最新状态。'
    return
  }
  if (!isAuthContextCurrent(context) || !isCurrentMediaCacheOwner(token)) {
    error.value = '账号状态已变化，请重新登录后再操作。'
    return
  }
  const operationSequence = ++actionSequence
  pendingAction.value = item.assetId
  error.value = ''
  clearFeedback(item.assetId)
  try {
    const updated = await changeMediaJob(item.assetId, kind, token)
    if (actionSequence === operationSequence && isAuthContextCurrent(context) && isCurrentMediaCacheOwner(token)) updateMediaAsset(updated, token)
  } catch (cause: unknown) {
    if (actionSequence === operationSequence && isAuthContextCurrent(context) && isCurrentMediaCacheOwner(token)) {
      if (!currentItem(item.assetId)) return
      if (errorStatus(cause) === 403) {
        error.value = '当前账号没有管理此图片处理任务的权限，请刷新后重试。原图不受影响。'
      } else {
        setFeedback(item.assetId, { kind: 'error', message: '操作结果未确认，请点击本行“刷新状态”核对。' })
      }
    }
  }
  finally {
    if (actionSequence === operationSequence && pendingAction.value === item.assetId) {
      pendingAction.value = ''
    }
  }
}
async function refreshStatus(item: MediaAssetState): Promise<void> {
  if (pendingAction.value || activeRefresh || currentItem(item.assetId) !== item) return
  const context = auth.captureAuthOperationContext()
  if (!isAuthContextCurrent(context)) {
    setFeedback(item.assetId, { kind: 'error', message: '账号状态已变化，请重新登录后再刷新状态。' })
    return
  }
  const operation: RefreshOperation = {
    sequence: ++refreshSequence,
    assetId: item.assetId,
    token: context.memoryToken,
    context,
    controller: new AbortController(),
  }
  activeRefresh = operation
  refreshingAssetId.value = item.assetId
  error.value = ''
  try {
    const latest = await getMediaAsset(item.assetId, operation.token, operation.controller.signal)
    if (refreshSequence !== operation.sequence || operation.controller.signal.aborted || !isAuthContextCurrent(operation.context)) return
    const current = currentItem(item.assetId)
    if (!current) return
    if (current !== item) {
      setFeedback(item.assetId, { kind: 'error', message: '状态已变化，请先查看；之前的操作仍可能完成。' })
    } else if (!updateMediaAssetIfCurrent(item, latest, operation.token)) {
      setFeedback(item.assetId, { kind: 'error', message: '账号或图片记录已变化，请重新打开图片状态后核对。' })
    } else {
      setFeedback(item.assetId, { kind: 'success', message: '已获取状态，之前的操作仍可能完成，请稍后再次核对。' })
    }
  } catch {
    if (refreshSequence === operation.sequence && !operation.controller.signal.aborted
      && isAuthContextCurrent(operation.context) && currentItem(item.assetId) === item) {
      setFeedback(item.assetId, { kind: 'error', message: '无法读取最新状态，请再次点击本行“刷新状态”核对。' })
    }
  } finally {
    if (activeRefresh?.sequence === operation.sequence) {
      activeRefresh = null
      refreshingAssetId.value = ''
    }
  }
}
function remove(item: MediaAssetState): void {
  dismissMediaAsset(item.assetId)
  if (currentItem(item.assetId)) return
  if (activeRefresh?.assetId === item.assetId) cancelRefresh()
  clearFeedback(item.assetId)
}
onBeforeUnmount(() => {
  cancelRefresh()
})
</script>

<template>
  <aside v-if="auth.isLoggedIn && mediaAssets.length" class="media-status" :class="{ 'media-status--raised': raised }" aria-label="图片状态">
    <button type="button" class="media-status-toggle" :aria-expanded="expanded" aria-controls="media-processing-items" @click="expanded = !expanded">
      <span>{{ summary }}</span><span aria-hidden="true">{{ expanded ? '收起' : '查看' }}</span>
    </button>
    <div v-if="expanded" id="media-processing-items" class="ui-scroll media-status-body" role="region" aria-label="图片处理记录" tabindex="0">
      <p class="media-status-note">预览处理不影响已上传的原图，就绪后可在这里查看。</p>
      <p v-if="error" role="alert" class="media-status-error">{{ error }}</p>
      <ul class="media-status-list">
        <li v-for="(item, index) in mediaAssets" :key="item.assetId">
          <div class="media-status-label"><span>图片 {{ index + 1 }}</span><button type="button" :aria-label="`移除图片 ${index + 1} 的状态记录`" @click="remove(item)">移除</button></div>
          <p role="status">{{ label(item) }}</p>
          <p v-if="feedback[item.assetId]" :role="feedback[item.assetId].kind === 'error' ? 'alert' : 'status'" :class="{ 'media-status-error': feedback[item.assetId].kind === 'error' }">{{ feedback[item.assetId].message }}</p>
          <div class="media-status-actions">
            <a v-if="!['UPLOADING', 'UPLOAD_FAILED'].includes(item.status || '')" :href="mediaHref(item.url)" target="_blank" rel="noopener noreferrer">查看原图</a>
            <a v-if="item.previewUrl && item.processingStatus === 'SUCCEEDED'" :href="mediaHref(item.previewUrl)" target="_blank" rel="noopener noreferrer">查看预览</a>
            <button v-if="['FAILED', 'CANCELLED'].includes(item.processingStatus)" type="button" :disabled="!!pendingAction || statusCheckPending" @click="action(item, 'retry')">{{ pendingAction === item.assetId ? '处理中…' : '重试预览' }}</button>
            <button v-if="['PENDING', 'PROCESSING'].includes(item.processingStatus)" type="button" :disabled="!!pendingAction || statusCheckPending" @click="action(item, 'cancel')">停止处理</button>
            <button v-if="feedback[item.assetId]" type="button" :aria-label="`刷新图片 ${index + 1} 状态`" :disabled="!!pendingAction || statusCheckPending" @click="refreshStatus(item)">{{ isRefreshPending(item.assetId) ? '读取中…' : '刷新状态' }}</button>
          </div>
        </li>
      </ul>
    </div>
  </aside>
</template>

<style scoped>
.media-status { --media-status-bottom: calc(76px + env(safe-area-inset-bottom, 0px)); position: fixed; z-index: var(--ui-z-floating); left: 12px; bottom: var(--media-status-bottom); width: min(360px, calc(100vw - 24px)); max-height: min(480px, calc(100dvh - var(--media-status-bottom) - 12px)); display: flex; flex-direction: column; border: 1px solid var(--ui-border); border-radius: 12px; background: var(--ui-surface); color: var(--ui-ink); }
.media-status-toggle { display: flex; flex-shrink: 0; width: 100%; min-height: 48px; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 14px; text-align: left; font-size: 14px; font-weight: 600; }
.media-status-toggle > span:last-child { color: var(--ui-accent); font-size: 13px; }
.media-status-body { min-height: 0; overflow-y: auto; padding: 0 14px 12px; }
.media-status-note, .media-status-list p { font-size: 13px; line-height: 1.6; color: var(--ui-muted); }
.media-status-list li { padding: 8px 0; border-top: 1px solid var(--ui-border); }
.media-status-label { display: flex; align-items: center; justify-content: space-between; font-size: 13px; font-weight: 600; }
.media-status-label button, .media-status-actions > * { min-height: 44px; padding: 8px; border-radius: 6px; font-size: 13px; }
.media-status-label button { color: var(--ui-muted); font-weight: 400; }
.media-status-actions { display: flex; flex-wrap: wrap; gap: 4px; color: var(--ui-accent); }
.media-status-actions > *:hover { background: var(--ui-accent-soft); }
.media-status-actions button:disabled { opacity: .6; }
.media-status-error { color: #b91c1c; font-size: 13px; }
.media-status-list p.media-status-error { color: #b91c1c; }
:global(.dark) .media-status-error, :global(.dark) .media-status-list p.media-status-error { color: #fca5a5; }
@media (min-width: 1024px) { .media-status { --media-status-bottom: 24px; left: 24px; } }
@media (max-width: 1023px) { .media-status--raised { --media-status-bottom: calc(136px + env(safe-area-inset-bottom, 0px)); } }
</style>

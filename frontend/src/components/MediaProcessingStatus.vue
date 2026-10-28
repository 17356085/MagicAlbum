<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { changeMediaJob } from '@/api/uploads'
import { dismissMediaAsset, mediaAssets, updateMediaAsset, type MediaAssetState } from '@/composables/mediaProcessingState'
import { normalizeImageUrl } from '@/utils/image'

const auth = useAuthStore()
defineProps<{ raised?: boolean }>()
const expanded = ref(false)
const pendingAction = ref('')
const error = ref('')
let actionSequence = 0
watch(() => auth.token, () => {
  actionSequence += 1
  pendingAction.value = ''
  error.value = ''
  expanded.value = false
})
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
  if (pendingAction.value) return
  const operationSequence = ++actionSequence
  const token = auth.token
  const generation = auth.getAuthGeneration()
  pendingAction.value = item.assetId
  error.value = ''
  try {
    const updated = await changeMediaJob(item.assetId, kind, token)
    if (auth.token === token && auth.isAuthGenerationCurrent(generation)) updateMediaAsset(updated, token)
  } catch (cause: unknown) {
    if (auth.token === token && auth.isAuthGenerationCurrent(generation)) {
      error.value = errorStatus(cause) === 403
        ? '当前账号没有管理此图片处理任务的权限，请刷新后重试。原图不受影响。'
        : '操作结果未能确认，请刷新页面核对状态后再重试。原图不受影响。'
    }
  }
  finally {
    if (actionSequence === operationSequence) pendingAction.value = ''
  }
}
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
          <div class="media-status-label"><span>图片 {{ index + 1 }}</span><button type="button" :aria-label="`移除图片 ${index + 1} 的状态记录`" @click="dismissMediaAsset(item.assetId)">移除</button></div>
          <p role="status">{{ label(item) }}</p>
          <div class="media-status-actions">
            <a v-if="!['UPLOADING', 'UPLOAD_FAILED'].includes(item.status || '')" :href="mediaHref(item.url)" target="_blank" rel="noopener noreferrer">查看原图</a>
            <a v-if="item.previewUrl && item.processingStatus === 'SUCCEEDED'" :href="mediaHref(item.previewUrl)" target="_blank" rel="noopener noreferrer">查看预览</a>
            <button v-if="['FAILED', 'CANCELLED'].includes(item.processingStatus)" type="button" :disabled="!!pendingAction" @click="action(item, 'retry')">{{ pendingAction === item.assetId ? '处理中…' : '重试预览' }}</button>
            <button v-if="['PENDING', 'PROCESSING'].includes(item.processingStatus)" type="button" :disabled="!!pendingAction" @click="action(item, 'cancel')">停止处理</button>
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
:global(.dark) .media-status-error { color: #fca5a5; }
@media (min-width: 1024px) { .media-status { --media-status-bottom: 24px; left: 24px; } }
@media (max-width: 1023px) { .media-status--raised { --media-status-bottom: calc(136px + env(safe-area-inset-bottom, 0px)); } }
</style>

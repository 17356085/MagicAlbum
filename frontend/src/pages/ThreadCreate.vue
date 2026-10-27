<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { WatchStopHandle } from 'vue'
import { listSections } from '@/api/sections'
import { createThread } from '@/api/threadWrites'
import { isThreadWriteUnconfirmedError } from '@/api/threadWriteResponse'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { beautifyMarkdown, useDraft } from '@/composables/useMarkdownTools'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import type { Id, Section } from '@/types'
import { THREAD_CONTENT_MAX_LENGTH } from '@/config/contentLimits'

const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const success = ref('')
const createdThreadId = ref<Id | null>(null)
const creationUnconfirmed = ref(false)
const sections = ref<Section[]>([])
const sectionsError = ref('')
const authStore = useAuthStore()
const { isLoggedIn, token } = storeToRefs(authStore)

interface ThreadCreateForm {
  sectionId: string
  title: string
  content: string
  tags: string
}

function emptyForm(): ThreadCreateForm {
  return { sectionId: '', title: '', content: '', tags: '' }
}

const form = ref<ThreadCreateForm>(emptyForm())

const isUploading = ref(false)
const uploadProgress = ref(0)
const beautifying = ref(false)
const editorBusy = computed(() => submitting.value || isUploading.value || beautifying.value)

function messageFrom(value: unknown): string | undefined {
  if (typeof value !== 'object' || value === null || !('message' in value)) return undefined
  const message = value.message
  return typeof message === 'string' ? message : undefined
}

function errorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    return messageFrom(error.response?.data) || error.message || fallback
  }
  if (typeof error === 'object' && error !== null) {
    const response = 'response' in error ? error.response : undefined
    if (typeof response === 'object' && response !== null && 'data' in response) {
      return messageFrom(response.data) || messageFrom(error) || fallback
    }
    return messageFrom(error) || fallback
  }
  return fallback
}

// 保留无归属的旧键 thread-draft-v1；新草稿使用带稳定账号后缀的键。
const DRAFT_KEY = 'thread-draft-v1'
const userIdentity = computed(() => {
  const currentUser = authStore.user
  const rawId = currentUser?.id ?? currentUser?.userId
  return rawId == null ? '' : String(rawId).trim()
})
const draftKey = computed(() => {
  if (!isLoggedIn.value) return `${DRAFT_KEY}:guest`
  // 登录态尚未补齐稳定用户 ID 时禁用本地读写，避免共享 pending 槽位。
  return userIdentity.value ? `${DRAFT_KEY}:${userIdentity.value}` : ''
})
const draftMessage = ref('')
const identityRefreshPending = ref(false)
const identityRefreshError = ref('')
// 通用草稿：面向整个 form 对象，防抖自动保存
const { draftHasData, saveDraft, flushAutoSave, restoreDraft, clearDraft, refreshDraft, startAutoSave, stopAutoSave } = useDraft(draftKey, {
  sourceRef: form,
  autoSaveMs: 800,
  serialize: (val) => JSON.stringify({
    sectionId: val?.sectionId || '',
    title: val?.title || '',
    content: val?.content || '',
    tags: val?.tags || '',
    savedAt: Date.now(),
  }),
  deserialize: (raw) => JSON.parse(raw),
  restoreMode: 'fill-empty',
})

let stopAuthWatch: WatchStopHandle | null = null
let draftMessageTimer: ReturnType<typeof setTimeout> | null = null
let mounted = false
let sectionsRequestSeq = 0
let identityRefreshSequence = 0
let submissionSequence = 0
let activeSubmissionId: number | null = null
let editorOperationSequence = 0
let activeUploadId: number | null = null
let activeBeautifyId: number | null = null

interface AuthContextSnapshot {
  draftKey: string
  identity: string
  loggedIn: boolean
  token: string
}

function getAuthContextSnapshot(): AuthContextSnapshot {
  return {
    draftKey: draftKey.value,
    identity: userIdentity.value,
    loggedIn: isLoggedIn.value,
    token: token.value,
  }
}

function showDraftMessage(message: string): void {
  if (draftMessageTimer) clearTimeout(draftMessageTimer)
  draftMessage.value = message
  draftMessageTimer = setTimeout(() => {
    draftMessageTimer = null
    draftMessage.value = ''
  }, 1500)
}

function clearDraftMessageTimer(): void {
  if (draftMessageTimer) {
    clearTimeout(draftMessageTimer)
    draftMessageTimer = null
  }
}

function resetIdentityRefreshState(): void {
  identityRefreshSequence += 1
  identityRefreshPending.value = false
  identityRefreshError.value = ''
}

function isCurrentIdentityRefresh(requestToken: string, requestId: number): boolean {
  return mounted && identityRefreshSequence === requestId &&
    isLoggedIn.value && token.value === requestToken
}

async function retryAccountInfo(): Promise<void> {
  if (!mounted || !isLoggedIn.value || userIdentity.value || identityRefreshPending.value) return
  const requestToken = token.value
  if (!requestToken) return
  const requestId = ++identityRefreshSequence
  identityRefreshPending.value = true
  identityRefreshError.value = ''
  try {
    const refreshed = await authStore.refreshCurrentUser()
    if (!isCurrentIdentityRefresh(requestToken, requestId)) return
    if (!refreshed || !userIdentity.value) {
      identityRefreshError.value = '账号信息加载失败，请重试'
    }
  } catch (_) {
    if (isCurrentIdentityRefresh(requestToken, requestId)) {
      identityRefreshError.value = '账号信息加载失败，请重试'
    }
  } finally {
    if (isCurrentIdentityRefresh(requestToken, requestId)) identityRefreshPending.value = false
  }
}

function isCurrentSubmission(submissionId: number, context: AuthContextSnapshot): boolean {
  const current = getAuthContextSnapshot()
  return mounted && activeSubmissionId === submissionId &&
    current.draftKey === context.draftKey &&
    current.identity === context.identity &&
    current.loggedIn === context.loggedIn &&
    current.token === context.token
}

function isCurrentEditorContext(context: AuthContextSnapshot): boolean {
  const current = getAuthContextSnapshot()
  return mounted &&
    current.draftKey === context.draftKey &&
    current.identity === context.identity &&
    current.loggedIn === context.loggedIn &&
    current.token === context.token
}

function isCurrentUpload(uploadId: number, context: AuthContextSnapshot): boolean {
  return activeUploadId === uploadId && isCurrentEditorContext(context)
}

function isCurrentBeautify(beautifyId: number, context: AuthContextSnapshot): boolean {
  return activeBeautifyId === beautifyId && isCurrentEditorContext(context)
}

function restoreActiveDraft(): void {
  refreshDraft()
  if (draftHasData.value) {
    restoreDraft()
    showDraftMessage('已恢复草稿')
  }
}

function hasNonEmptyForm(): boolean {
  return Object.values(form.value).some((value) => String(value || '').trim() !== '')
}

function switchDraftContext({ preserveForm = false }: { preserveForm?: boolean } = {}): void {
  // Stop the old watcher before clearing the form; otherwise the reset itself
  // would be scheduled as the previous account's draft.
  stopAutoSave({ flush: true })
  resetIdentityRefreshState()
  activeSubmissionId = null
  submissionSequence += 1
  editorOperationSequence += 1
  activeUploadId = null
  activeBeautifyId = null
  submitting.value = false
  isUploading.value = false
  uploadProgress.value = 0
  beautifying.value = false
  if (!preserveForm) form.value = emptyForm()
  error.value = ''
  success.value = ''
  creationUnconfirmed.value = false
  createdThreadId.value = null
  clearDraftMessageTimer()
  draftMessage.value = ''
  restoreActiveDraft()
  if (preserveForm && hasNonEmptyForm()) saveDraft()
  startAutoSave()
}

function requestLogin(source = 'thread-create'): void {
  try {
    window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source } }))
  } catch (_) {}
}

async function uploadThreadImage(file: File): Promise<string> {
  if (!mounted || submitting.value || isUploading.value || beautifying.value) {
    if (mounted && (submitting.value || beautifying.value)) error.value = '请等待当前编辑操作完成'
    return ''
  }
  const uploadToken = token.value
  if (!hasRealToken(uploadToken)) {
    error.value = '请先登录后上传图片'
    return ''
  }
  if (uploadToken !== getStoredAccessToken()) {
    error.value = '登录状态已变化，请刷新后重试'
    return ''
  }
  const context = getAuthContextSnapshot()
  const uploadId = ++editorOperationSequence
  activeUploadId = uploadId
  try {
    isUploading.value = true
    uploadProgress.value = 0
    const { url, path } = await uploadImage(file, uploadToken, (p) => {
      if (isCurrentUpload(uploadId, context)) uploadProgress.value = p
    })
    if (!isCurrentUpload(uploadId, context)) return ''
    return String(url || path || '')
  } catch (e: unknown) {
    if (isCurrentUpload(uploadId, context)) error.value = errorMessage(e, '图片上传失败')
    return ''
  } finally {
    if (activeUploadId === uploadId) {
      activeUploadId = null
      isUploading.value = false
      uploadProgress.value = 0
    }
  }
}

async function loadSections(): Promise<void> {
  if (!mounted) return
  const requestSeq = ++sectionsRequestSeq
  loading.value = true
  sectionsError.value = ''
  try {
    const data = await listSections({ size: 100 })
    if (!mounted || requestSeq !== sectionsRequestSeq) return
    sections.value = data.items
  } catch (_) {
    if (!mounted || requestSeq !== sectionsRequestSeq) return
    sectionsError.value = '分区加载失败，请重试'
  } finally {
    if (mounted && requestSeq === sectionsRequestSeq) loading.value = false
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  if (isUploading.value || beautifying.value) {
    error.value = '请等待图片上传或内容美化完成'
    return
  }
  error.value = ''
  success.value = ''
  creationUnconfirmed.value = false
  createdThreadId.value = null
  if (isLoggedIn.value && !userIdentity.value) {
    error.value = '账号信息确认后才能发布，请先重试账号信息'
    return
  }
  if (!form.value.sectionId || !form.value.title || !form.value.content) {
    error.value = '请完整填写分区、标题与内容'
    return
  }
  if (form.value.content.length > THREAD_CONTENT_MAX_LENGTH) {
    error.value = `正文不能超过 ${THREAD_CONTENT_MAX_LENGTH} 字符`
    return
  }
  // 未登录或无令牌时阻止提交
  if (!isLoggedIn.value || !token.value) {
    error.value = '请先登录后再发帖'
    requestLogin()
    return
  }
  if (String(token.value).startsWith('mock-token-')) {
    error.value = '当前为模拟令牌，请退出并用真实账号登录'
    return
  }
  const submissionContext = getAuthContextSnapshot()
  const submissionId = ++submissionSequence
  activeSubmissionId = submissionId
  submitting.value = true
  try {
    const sectionId = form.value.sectionId
    const tags = parsedTags.value
    const payload = {
      sectionId,
      title: form.value.title,
      content: form.value.content,
      tags,
    }
    const created = await createThread(payload)
    try {
      window.dispatchEvent(new CustomEvent('threads-updated', { detail: { reason: 'created', threadId: created?.id, sectionId, tags } }))
      window.dispatchEvent(new CustomEvent('thread-tags-updated', { detail: { reason: 'created', threadId: created?.id, sectionId, tags } }))
    } catch (_) {}
    if (!isCurrentSubmission(submissionId, submissionContext)) return
    success.value = '发布成功'
    createdThreadId.value = created?.id ?? null
    // 发布成功后清除提交时账号的草稿。停掉 watcher 再清空表单，
    // 避免重置动作被自动保存成一个空草稿。
    stopAutoSave()
    clearDraft()
    form.value = emptyForm()
    startAutoSave()
  } catch (e: unknown) {
    if (!isCurrentSubmission(submissionId, submissionContext)) return
    creationUnconfirmed.value = isThreadWriteUnconfirmedError(e)
    error.value = creationUnconfirmed.value
      ? '发布结果暂未确认，当前内容已保留。请先核对我的帖子，避免重复发布。'
      : errorMessage(e, '发布失败，请稍后重试')
  } finally {
    if (activeSubmissionId === submissionId) {
      activeSubmissionId = null
      submitting.value = false
    }
  }
}

const parsedTags = computed(() => parseTags(form.value.tags))

function parseTags(raw: string): string[] {
  return String(raw || '')
    .split(/[,，、\s]+/)
    .map((tag) => tag.trim().replace(/^#+/, ''))
    .filter(Boolean)
    .filter((tag, index, arr) => arr.findIndex((item) => item.toLowerCase() === tag.toLowerCase()) === index)
    .slice(0, 5)
}

// 手动按钮：在页面上给予提示，但逻辑已由 composable 处理
function onSaveDraft(): void {
  if (editorBusy.value) {
    showDraftMessage('请等待当前编辑操作完成')
    return
  }
  if (isLoggedIn.value && !userIdentity.value) {
    showDraftMessage('账号信息尚未确认，当前输入不会保存为草稿，请稍后重试')
    return
  }
  showDraftMessage(saveDraft() ? '草稿已保存到本机浏览器' : '草稿保存失败，请稍后重试')
}

function onClearDraft(): void {
  clearDraft()
  showDraftMessage('已清除草稿')
}

function onLoginClick(): void {
  error.value = '请先登录后再发帖'
  requestLogin()
}

function flushDraftOnPageHide(): void {
  flushAutoSave()
}

// 标准化美化：调用通用 beautifyMarkdown
async function beautifyContent(): Promise<void> {
  if (!mounted || submitting.value || isUploading.value || beautifying.value) {
    if (mounted && (submitting.value || isUploading.value)) showDraftMessage('请等待当前编辑操作完成')
    return
  }
  const context = getAuthContextSnapshot()
  const source = form.value.content
  const beautifyId = ++editorOperationSequence
  activeBeautifyId = beautifyId
  beautifying.value = true
  try {
    const result = await beautifyMarkdown(source)
    if (!isCurrentBeautify(beautifyId, context) || form.value.content !== source) return
    form.value.content = result
    showDraftMessage('已美化内容')
  } catch (_) {
    if (isCurrentBeautify(beautifyId, context)) showDraftMessage('美化失败')
  } finally {
    if (activeBeautifyId === beautifyId) {
      activeBeautifyId = null
      beautifying.value = false
    }
  }
}

onMounted(() => {
  mounted = true
  loadSections()
  window.addEventListener('pagehide', flushDraftOnPageHide)
  // 草稿检测与自动恢复
  restoreActiveDraft()
  startAutoSave()
  stopAuthWatch = watch(
    () => getAuthContextSnapshot(),
    (next, previous) => {
      const identityChanged = next.draftKey !== previous.draftKey || next.loggedIn !== previous.loggedIn
      const tokenChanged = next.token !== previous.token
      const preservingIdentityHydration = next.token === previous.token &&
        previous.loggedIn && !previous.identity && !!next.identity
      if (identityChanged || tokenChanged) switchDraftContext({ preserveForm: preservingIdentityHydration })
    },
  )
})

onUnmounted(() => {
  mounted = false
  sectionsRequestSeq += 1
  activeSubmissionId = null
  submissionSequence += 1
  editorOperationSequence += 1
  identityRefreshSequence += 1
  identityRefreshPending.value = false
  identityRefreshError.value = ''
  activeUploadId = null
  activeBeautifyId = null
  isUploading.value = false
  uploadProgress.value = 0
  beautifying.value = false
  if (stopAuthWatch) {
    stopAuthWatch()
    stopAuthWatch = null
  }
  window.removeEventListener('pagehide', flushDraftOnPageHide)
  stopAutoSave({ flush: true })
  clearDraftMessageTimer()
})

// 自动保存交由 composable 管理
</script>

<template>
  <div class="ui-page">
    <header
      class="ui-page-header sticky grid min-h-14 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-2 gap-y-1 bg-[var(--ui-bg)] lg:static lg:flex lg:min-h-0 lg:bg-transparent"
      style="top: calc(56px + env(safe-area-inset-top)); z-index: var(--ui-z-navigation);"
    >
      <div class="min-w-0 flex-1">
        <div class="flex min-w-0 items-center gap-2">
          <h1 class="ui-title truncate">发帖</h1>
        </div>
        <p class="ui-description hidden lg:block">选择分区，写下标题和正文，再发布到社区。</p>
      </div>
      <div class="flex shrink-0 flex-nowrap items-center justify-end gap-2 lg:hidden">
        <button type="button" class="ui-button min-h-11 whitespace-nowrap px-3 py-2 text-sm" :disabled="editorBusy" @click="onSaveDraft">保存草稿</button>
        <button type="button" class="ui-button-primary min-h-12 whitespace-nowrap px-3 py-2 text-sm" :disabled="editorBusy" @click="submit">
          {{ submitting ? '发布中...' : creationUnconfirmed ? '再次发布' : '发布' }}
        </button>
      </div>
      <span v-if="error" class="ui-error col-span-2 min-w-0 whitespace-normal break-words px-2 py-1 text-xs lg:hidden" role="alert" aria-live="assertive">
        {{ error }}
        <router-link v-if="creationUnconfirmed" data-testid="unconfirmed-create-link" to="/my/threads" target="_blank" rel="noopener noreferrer" class="ml-1 text-[var(--ui-accent)] underline">在新标签页核对我的帖子</router-link>
      </span>
      <span v-else-if="success" class="col-span-2 min-w-0 whitespace-normal break-words px-2 py-1 text-xs text-emerald-700 dark:text-emerald-300 lg:hidden" role="status" aria-live="polite">{{ success }}</span>
      <span v-else-if="draftMessage" class="ui-muted col-span-2 min-w-0 whitespace-normal break-words px-2 py-1 text-xs lg:hidden" role="status" aria-live="polite">{{ draftMessage }}</span>
    </header>

    <div class="ui-panel p-4 lg:p-6">
      <div
        v-if="isLoggedIn && !userIdentity"
        data-testid="thread-create-identity-warning"
        class="mb-4 flex flex-wrap items-center gap-2 rounded-xl border border-amber-300 bg-amber-50 px-3 py-3 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950/40 dark:text-amber-100"
        role="status"
        aria-live="polite"
      >
        <span>账号信息正在加载，草稿暂时无法保存，请保持页面打开。</span>
        <button type="button" class="ui-button min-h-9 px-3 py-1 text-sm" :disabled="identityRefreshPending" @click="retryAccountInfo">
          {{ identityRefreshPending ? '正在获取账号信息...' : '重试账号信息' }}
        </button>
        <span v-if="identityRefreshError" class="basis-full text-sm" role="alert" aria-live="assertive">{{ identityRefreshError }}</span>
      </div>

      <div class="space-y-4">
        <div>
          <label for="thread-create-section" class="mb-1 block text-sm font-medium">分区</label>
          <select id="thread-create-section" v-model="form.sectionId" :disabled="loading || submitting" class="ui-field min-h-12 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]">
            <option value="" disabled>请选择分区</option>
            <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <p v-if="loading" class="ui-muted mt-1 text-xs" role="status" aria-live="polite">正在加载分区...</p>
          <div v-else-if="sectionsError" class="ui-error mt-2 flex flex-wrap items-center gap-2 text-sm" role="alert" aria-live="assertive">
            <span>{{ sectionsError }}</span>
            <button type="button" class="ui-button min-h-9 px-3 py-1 text-sm" @click="loadSections">重试</button>
          </div>
          <p v-else-if="sections.length === 0" class="ui-muted mt-2 text-sm" role="status">暂无可用分区</p>
        </div>

        <div>
          <label for="thread-create-title" class="mb-1 block text-sm font-medium">标题</label>
          <input id="thread-create-title" v-model="form.title" :disabled="submitting" type="text" class="ui-field min-h-12 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="请输入标题" />
        </div>

        <details class="rounded-xl border border-[var(--ui-border)] px-3">
          <summary class="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium">
            <span>标签（可选）</span>
            <span v-if="parsedTags.length" class="ui-muted text-xs">已设置 {{ parsedTags.length }} 个</span>
          </summary>
          <div class="pb-3">
            <label for="thread-create-tags" class="sr-only">标签</label>
            <input
              id="thread-create-tags"
              v-model="form.tags" :disabled="submitting"
              type="text"
              class="ui-field min-h-11 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
              placeholder="用空格或逗号分隔，最多 5 个"
            />
            <div v-if="parsedTags.length" class="mt-2 flex flex-wrap gap-2">
              <span v-for="tag in parsedTags" :key="tag" class="ui-tag">
                #{{ tag }}
              </span>
            </div>
          </div>
        </details>

        <div>
          <label class="mb-1 block text-sm font-medium">内容（支持 Markdown）</label>
          <MarkdownTextareaEditor
            v-model="form.content"
            :max-length="THREAD_CONTENT_MAX_LENGTH"
            :rows="16"
            :uploading="isUploading"
            :upload-progress="uploadProgress"
            :upload-image="uploadThreadImage"
            :disabled="submitting"
            :preview-default-collapsed="true"
            :mobile-preview-exclusive="true"
            placeholder="请输入帖子正文，支持 Markdown"
            preview-label="正文预览"
          />
        </div>

        <div class="flex flex-col items-stretch gap-3 border-t border-gray-200 pt-4 dark:border-gray-700 lg:flex-row lg:flex-wrap lg:items-center">
          <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm" :disabled="editorBusy" @click="onSaveDraft">保存到本机</button>
          <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm" :disabled="editorBusy" @click="beautifyContent">{{ beautifying ? '美化中...' : '美化内容' }}</button>
          <button type="button" :disabled="editorBusy" class="ui-button-primary min-h-12 px-3 py-2 text-sm hidden lg:inline-flex" @click="submit">
            {{ submitting ? '发布中...' : creationUnconfirmed ? '再次发布' : '发布' }}
          </button>
          <div v-if="!isLoggedIn" class="flex flex-wrap items-center gap-2 text-sm ui-muted">
            <span>请先登录后再发帖</span>
            <button
              type="button"
              class="ui-button min-h-11 px-3 py-2 text-sm"
              @click="onLoginClick"
            >
              立即登录
            </button>
          </div>
          <span v-if="success" class="hidden text-sm text-emerald-700 dark:text-emerald-300 lg:inline" role="status" aria-live="polite">{{ success }}</span>
          <router-link
            v-if="createdThreadId != null"
            :to="`/threads/${createdThreadId}`"
            class="text-sm text-[var(--ui-accent)] underline hover:opacity-80"
          >
            查看帖子
          </router-link>
          <span v-if="error" class="ui-error hidden py-2 text-sm lg:inline" role="alert" aria-live="assertive">
            {{ error }}
            <router-link v-if="creationUnconfirmed" data-testid="unconfirmed-create-link" to="/my/threads" target="_blank" rel="noopener noreferrer" class="ml-1 text-[var(--ui-accent)] underline">在新标签页核对我的帖子</router-link>
          </span>
          <span v-if="draftMessage" class="ui-muted hidden text-xs lg:inline" role="status" aria-live="polite">{{ draftMessage }}</span>
          <button v-if="draftHasData" type="button" class="ui-button min-h-11 px-3 py-2 text-sm" @click="onClearDraft">清除草稿</button>
        </div>
      </div>
    </div>
  </div>
  </template>

<style scoped>
</style>

<script setup lang="ts">
import { THREAD_CONTENT_MAX_LENGTH } from '@/config/contentLimits'
import { isAxiosError } from 'axios'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listSections } from '@/api/sections'
import { getThread } from '@/api/threads'
import { updateThread } from '@/api/threadWrites'
import { isThreadWriteUnconfirmedError } from '@/api/threadWriteResponse'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { beautifyMarkdown } from '@/composables/useMarkdownTools'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import type { Id, Section } from '@/types'

interface ThreadEditForm {
  sectionId: string
  title: string
  content: string
  tags: string
}

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const { token } = storeToRefs(authStore)

const loading = ref(false)
const submitting = ref(false)
const isUploading = ref(false)
const uploadProgress = ref(0)
const beautifying = ref(false)
const editorBusy = computed(() => submitting.value || isUploading.value || beautifying.value)
const error = ref('')
const threadLoadError = ref('')
const success = ref('')
const saveUnconfirmed = ref(false)
const sections = ref<Section[]>([])
const sectionsLoading = ref(false)
const sectionsError = ref('')
const form = ref<ThreadEditForm>({ sectionId: '', title: '', content: '', tags: '' })

interface AuthContextSnapshot {
  routeId: string
  token: string
  generation: number
}

let mounted = false
let loadSequence = 0
let sectionsRequestSeq = 0
let submissionSequence = 0
let activeSubmissionId: number | null = null
let successTimer: ReturnType<typeof setTimeout> | null = null
let navigationTimer: ReturnType<typeof setTimeout> | null = null
let editorOperationSequence = 0
let activeUploadId: number | null = null
let activeBeautifyId: number | null = null

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

const threadId = computed<Id>(() => String(route.params.id || ''))

function emptyForm(): ThreadEditForm {
  return { sectionId: '', title: '', content: '', tags: '' }
}

function getAuthContextSnapshot(): AuthContextSnapshot {
  return {
    routeId: String(threadId.value || ''),
    token: token.value,
    generation: authStore.getAuthGeneration(),
  }
}

function isCurrentContext(context: AuthContextSnapshot): boolean {
  return mounted &&
    String(threadId.value || '') === context.routeId &&
    token.value === context.token &&
    authStore.isAuthGenerationCurrent(context.generation)
}

function isCurrentSubmission(submissionId: number, context: AuthContextSnapshot): boolean {
  return activeSubmissionId === submissionId && isCurrentContext(context)
}

function isCurrentUpload(uploadId: number, context: AuthContextSnapshot): boolean {
  return activeUploadId === uploadId && isCurrentContext(context)
}

function isCurrentBeautify(beautifyId: number, context: AuthContextSnapshot): boolean {
  return activeBeautifyId === beautifyId && isCurrentContext(context)
}

function clearSuccessTimer(): void {
  if (successTimer) {
    clearTimeout(successTimer)
    successTimer = null
  }
}

function clearNavigationTimer(): void {
  if (navigationTimer) {
    clearTimeout(navigationTimer)
    navigationTimer = null
  }
}

function showTransientSuccess(message: string): void {
  clearSuccessTimer()
  success.value = message
  successTimer = setTimeout(() => {
    successTimer = null
    if (mounted) success.value = ''
  }, 1500)
}

function invalidatePageContext(resetForm = true): void {
  loadSequence += 1
  submissionSequence += 1
  editorOperationSequence += 1
  activeSubmissionId = null
  activeUploadId = null
  activeBeautifyId = null
  submitting.value = false
  isUploading.value = false
  uploadProgress.value = 0
  beautifying.value = false
  clearSuccessTimer()
  clearNavigationTimer()
  error.value = ''
  success.value = ''
  threadLoadError.value = ''
  saveUnconfirmed.value = false
  if (resetForm) form.value = emptyForm()
}

async function loadSections(): Promise<void> {
  if (!mounted) return
  const requestSeq = ++sectionsRequestSeq
  sectionsLoading.value = true
  sectionsError.value = ''
  try {
    const data = await listSections({ size: 100 })
    if (mounted && requestSeq === sectionsRequestSeq) sections.value = data.items
  } catch (_) {
    if (mounted && requestSeq === sectionsRequestSeq) sectionsError.value = '分区加载失败，请重试'
  } finally {
    if (mounted && requestSeq === sectionsRequestSeq) sectionsLoading.value = false
  }
}

async function loadThread(): Promise<void> {
  const requestId = ++loadSequence
  const context = getAuthContextSnapshot()
  loading.value = true
  error.value = ''
  threadLoadError.value = ''
  try {
    const thread = await getThread(context.routeId)
    if (!isCurrentContext(context) || requestId !== loadSequence) return
    threadLoadError.value = ''
    form.value = {
      sectionId: String(thread.sectionId || ''),
      title: thread.title || '',
      content: thread.content || thread.contentMd || '',
      tags: Array.isArray(thread.tags) ? thread.tags.join(' ') : '',
    }
  } catch (_) {
    if (isCurrentContext(context) && requestId === loadSequence) threadLoadError.value = '加载帖子失败，请重试'
  } finally {
    if (mounted && requestId === loadSequence) loading.value = false
  }
}

function retryThread(): void {
  void loadThread()
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
    const { url, path } = await uploadImage(file, uploadToken, (progress) => {
      if (isCurrentUpload(uploadId, context)) uploadProgress.value = progress
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

async function beautifyContent(): Promise<void> {
  if (!mounted || submitting.value || isUploading.value || beautifying.value) {
    if (mounted && (submitting.value || isUploading.value)) showTransientSuccess('请等待当前编辑操作完成')
    return
  }
  const context = getAuthContextSnapshot()
  const source = form.value.content
  const beautifyId = ++editorOperationSequence
  activeBeautifyId = beautifyId
  beautifying.value = true
  try {
    const content = await beautifyMarkdown(source)
    if (!isCurrentBeautify(beautifyId, context) || form.value.content !== source) return
    form.value.content = content
    showTransientSuccess('已美化内容')
  } catch (_) {
    if (isCurrentBeautify(beautifyId, context)) error.value = '美化失败'
  } finally {
    if (activeBeautifyId === beautifyId) {
      activeBeautifyId = null
      beautifying.value = false
    }
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
  saveUnconfirmed.value = false
  clearSuccessTimer()
  clearNavigationTimer()
  if (form.value.content.length > THREAD_CONTENT_MAX_LENGTH) {
    error.value = `正文不能超过 ${THREAD_CONTENT_MAX_LENGTH} 字符`
    return
  }
  if (!form.value.title.trim() || !form.value.content.trim()) {
    error.value = '请填写标题和内容'
    return
  }
  const context = getAuthContextSnapshot()
  const submissionId = ++submissionSequence
  activeSubmissionId = submissionId
  const originalThreadId = context.routeId
  const sectionId = form.value.sectionId || undefined
  const tags = parsedTags.value
  submitting.value = true
  let navigationScheduled = false
  try {
    await updateThread(originalThreadId, {
      title: form.value.title,
      content: form.value.content,
      sectionId,
      tags,
    })
    try {
      // The write is durable even if this component has since left the page;
      // use the original id for public list/tag refreshes and guard local UI
      // state and navigation separately below.
      window.dispatchEvent(new CustomEvent('threads-updated', { detail: { reason: 'updated', threadId: originalThreadId, sectionId, tags } }))
      window.dispatchEvent(new CustomEvent('thread-tags-updated', { detail: { reason: 'updated', threadId: originalThreadId, sectionId, tags } }))
    } catch (_) {}
    if (!isCurrentSubmission(submissionId, context)) return
    showTransientSuccess('保存成功')
    navigationScheduled = true
    navigationTimer = setTimeout(() => {
      navigationTimer = null
      if (!isCurrentSubmission(submissionId, context)) return
      void Promise.resolve()
        .then(() => router.push(`/threads/${originalThreadId}`))
        .then((failure) => {
          if (!isCurrentSubmission(submissionId, context)) return
          if (failure) {
            clearSuccessTimer()
            success.value = ''
            error.value = '保存成功，但跳转失败，请重试'
          }
          activeSubmissionId = null
          submitting.value = false
        })
        .catch(() => {
          if (!isCurrentSubmission(submissionId, context)) return
          clearSuccessTimer()
          success.value = ''
          error.value = '保存成功，但跳转失败，请重试'
          activeSubmissionId = null
          submitting.value = false
        })
    }, 400)
  } catch (e: unknown) {
    if (isCurrentSubmission(submissionId, context)) {
      saveUnconfirmed.value = isThreadWriteUnconfirmedError(e)
      error.value = saveUnconfirmed.value
        ? '保存结果暂未确认，当前内容已保留。请先查看帖子核对。'
        : errorMessage(e, '保存失败')
    }
  } finally {
    if (activeSubmissionId === submissionId) {
      if (!navigationScheduled) {
        submitting.value = false
        activeSubmissionId = null
      }
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

function cancel(): void {
  invalidatePageContext(false)
  router.push('/my/threads')
}

function onAuthStateChanged(): void {
  if (!mounted) return
  invalidatePageContext()
  if (token.value) void loadThread()
}

function onRouteThreadChanged(): void {
  if (!mounted) return
  invalidatePageContext()
  void loadThread()
}

onMounted(() => {
  mounted = true
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  watch(() => String(threadId.value || ''), onRouteThreadChanged)
  void Promise.all([loadSections(), loadThread()])
})

onUnmounted(() => {
  mounted = false
  sectionsRequestSeq += 1
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  invalidatePageContext(false)
  clearSuccessTimer()
  clearNavigationTimer()
})
</script>

<template>
  <div class="ui-page">
    <header
      class="ui-page-header sticky grid min-h-14 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-2 gap-y-1 bg-[var(--ui-bg)] lg:static lg:flex lg:min-h-0 lg:bg-transparent"
      style="top: calc(56px + env(safe-area-inset-top)); z-index: var(--ui-z-navigation);"
    >
      <div class="min-w-0 flex-1">
        <div class="flex min-w-0 items-center gap-2">
          <h1 class="ui-title truncate">编辑帖子</h1>
        </div>
        <p class="ui-description hidden lg:block">修改标题、分区和正文内容。</p>
      </div>
      <div class="flex shrink-0 flex-nowrap items-center justify-end gap-2 lg:hidden">
        <button type="button" class="ui-button-primary min-h-12 whitespace-nowrap px-3 py-2 text-sm" :disabled="editorBusy" @click="submit">
          {{ submitting ? '保存中...' : saveUnconfirmed ? '再次保存' : '保存修改' }}
        </button>
      </div>
      <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm hidden lg:inline-flex" @click="cancel">
        返回我的帖子
      </button>
      <span v-if="error" class="ui-error col-span-2 min-w-0 whitespace-normal break-words px-2 py-1 text-xs lg:hidden" role="alert" aria-live="assertive">
        {{ error }}
        <router-link v-if="saveUnconfirmed" data-testid="unconfirmed-edit-link" :to="`/threads/${threadId}`" target="_blank" rel="noopener noreferrer" class="ml-1 text-[var(--ui-accent)] underline">在新标签页查看帖子</router-link>
      </span>
      <span v-else-if="success" class="col-span-2 min-w-0 whitespace-normal break-words px-2 py-1 text-xs text-emerald-700 dark:text-emerald-300 lg:hidden" role="status" aria-live="polite">{{ success }}</span>
    </header>

    <div class="ui-panel p-4 lg:p-6">
      <div v-if="loading" class="ui-muted ui-empty" role="status" aria-live="polite">正在加载帖子...</div>
      <div v-else-if="threadLoadError" class="ui-error ui-empty" role="alert" aria-live="assertive">
        <p>{{ threadLoadError }}</p>
        <button type="button" class="ui-button mt-3" @click="retryThread">重试</button>
      </div>
      <div v-else class="space-y-4">
      <div>
        <label for="thread-edit-section" class="mb-1 block text-sm font-medium">分区</label>
        <select id="thread-edit-section" v-model="form.sectionId" :disabled="submitting || sectionsLoading" class="ui-field min-h-12 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]">
          <option value="">保持原分区</option>
          <option v-for="section in sections" :key="section.id" :value="section.id">{{ section.name }}</option>
        </select>
        <p v-if="sectionsLoading" class="ui-muted mt-1 text-xs" role="status" aria-live="polite">正在加载分区...</p>
        <div v-else-if="sectionsError" class="ui-error mt-2 flex flex-wrap items-center gap-2 text-sm" role="alert" aria-live="assertive">
          <span>{{ sectionsError }}</span>
          <button type="button" class="ui-button min-h-9 px-3 py-1 text-sm" @click="loadSections">重试</button>
        </div>
        <p v-else-if="sections.length === 0" class="ui-muted mt-2 text-sm" role="status">暂无可用分区</p>
      </div>

      <div>
        <label for="thread-edit-title" class="mb-1 block text-sm font-medium">标题</label>
        <input id="thread-edit-title" v-model="form.title" :disabled="submitting" type="text" class="ui-field min-h-12 text-base focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="请输入标题" />
      </div>

      <details class="rounded-xl border border-[var(--ui-border)] px-3">
        <summary class="flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium">
          <span>标签（可选）</span>
          <span v-if="parsedTags.length" class="ui-muted text-xs">已设置 {{ parsedTags.length }} 个</span>
        </summary>
        <div class="pb-3">
          <label for="thread-edit-tags" class="sr-only">标签</label>
          <input
            id="thread-edit-tags"
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
        <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm" :disabled="editorBusy" @click="beautifyContent">{{ beautifying ? '美化中...' : '美化内容' }}</button>
        <button type="button" :disabled="editorBusy" class="ui-button-primary min-h-12 px-3 py-2 text-sm hidden lg:inline-flex" @click="submit">
          {{ submitting ? '保存中...' : saveUnconfirmed ? '再次保存' : '保存修改' }}
        </button>
        <button type="button" class="ui-button min-h-11 px-3 py-2 text-sm" @click="cancel">取消</button>
        <span v-if="success" class="hidden text-sm text-emerald-700 dark:text-emerald-300 lg:inline" role="status" aria-live="polite">{{ success }}</span>
        <span v-if="error" class="ui-error hidden py-2 text-sm lg:inline" role="alert" aria-live="assertive">
          {{ error }}
          <router-link v-if="saveUnconfirmed" data-testid="unconfirmed-edit-link" :to="`/threads/${threadId}`" target="_blank" rel="noopener noreferrer" class="ml-1 text-[var(--ui-accent)] underline">在新标签页查看帖子</router-link>
        </span>
      </div>
    </div>
    </div>
  </div>
</template>

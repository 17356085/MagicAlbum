<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { WatchStopHandle } from 'vue'
import { listSections } from '@/api/sections'
import { createThread } from '@/api/threads'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { beautifyMarkdown, useDraft } from '@/composables/useMarkdownTools'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import type { Id, Section } from '@/types'
import { THREAD_CONTENT_MAX_LENGTH } from '@/config/contentLimits'

const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const success = ref('')
const createdThreadId = ref<Id | null>(null)
const sections = ref<Section[]>([])
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
// 通用草稿：面向整个 form 对象，防抖自动保存
const { draftHasData, saveDraft, restoreDraft, clearDraft, refreshDraft, startAutoSave, stopAutoSave } = useDraft(draftKey, {
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
let submissionSequence = 0
let activeSubmissionId: number | null = null

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

function isCurrentSubmission(submissionId: number, context: AuthContextSnapshot): boolean {
  const current = getAuthContextSnapshot()
  return mounted && activeSubmissionId === submissionId &&
    current.draftKey === context.draftKey &&
    current.identity === context.identity &&
    current.loggedIn === context.loggedIn &&
    current.token === context.token
}

function restoreActiveDraft(): void {
  refreshDraft()
  if (draftHasData.value) {
    restoreDraft()
    showDraftMessage('已恢复草稿')
  }
}

function switchDraftContext(): void {
  // Stop the old watcher before clearing the form; otherwise the reset itself
  // would be scheduled as the previous account's draft.
  stopAutoSave()
  activeSubmissionId = null
  submissionSequence += 1
  submitting.value = false
  form.value = emptyForm()
  error.value = ''
  success.value = ''
  createdThreadId.value = null
  clearDraftMessageTimer()
  draftMessage.value = ''
  restoreActiveDraft()
  startAutoSave()
}

function requestLogin(source = 'thread-create'): void {
  try {
    window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source } }))
  } catch (_) {}
}

async function uploadThreadImage(file: File): Promise<string> {
  try {
    isUploading.value = true
    uploadProgress.value = 0
    const { url, path } = await uploadImage(file, token.value, (p) => {
      uploadProgress.value = p
    })
    return String(url || path || '')
  } catch (e: unknown) {
    error.value = errorMessage(e, '图片上传失败')
    return ''
  } finally {
    isUploading.value = false
    uploadProgress.value = 0
  }
}

async function loadSections(): Promise<void> {
  loading.value = true
  try {
    const data = await listSections({ size: 100 })
    sections.value = data.items
  } catch (_) {
    // 保持空列表即可
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  error.value = ''
  success.value = ''
  createdThreadId.value = null
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
    error.value = errorMessage(e, '发布失败，请稍后重试')
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
  saveDraft()
  showDraftMessage('草稿已保存到本机浏览器')
}

function onClearDraft(): void {
  clearDraft()
  showDraftMessage('已清除草稿')
}

function onLoginClick(): void {
  error.value = '请先登录后再发帖'
  requestLogin()
}

// 标准化美化：调用通用 beautifyMarkdown
async function beautifyContent(): Promise<void> {
  try {
    form.value.content = await beautifyMarkdown(form.value.content)
    showDraftMessage('已美化内容')
  } catch (_) {
    showDraftMessage('美化失败')
  }
}

onMounted(() => {
  mounted = true
  loadSections()
  // 草稿检测与自动恢复
  restoreActiveDraft()
  startAutoSave()
  stopAuthWatch = watch(
    () => getAuthContextSnapshot(),
    (next, previous) => {
      const identityChanged = next.draftKey !== previous.draftKey || next.loggedIn !== previous.loggedIn
      const unknownIdentityTokenChanged = !next.identity && !previous.identity && next.token !== previous.token
      if (identityChanged || unknownIdentityTokenChanged) switchDraftContext()
    },
  )
})

onUnmounted(() => {
  mounted = false
  activeSubmissionId = null
  submissionSequence += 1
  if (stopAuthWatch) {
    stopAuthWatch()
    stopAuthWatch = null
  }
  stopAutoSave()
  clearDraftMessageTimer()
})

// 自动保存交由 composable 管理
</script>

<template>
  <div class="ui-page">
    <header class="ui-page-header">
      <div class="min-w-0">
        <h1 class="ui-title">发帖</h1>
        <p class="ui-description">选择分区，写下标题和正文，再发布到社区。</p>
      </div>
    </header>

    <div class="ui-panel p-4 sm:p-6">

      <div class="space-y-4">
        <div>
          <label for="thread-create-section" class="mb-1 block text-sm font-medium">分区</label>
          <select id="thread-create-section" v-model="form.sectionId" :disabled="loading" class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]">
            <option value="" disabled>请选择分区</option>
            <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
          <p v-if="loading" class="ui-muted mt-1 text-xs" role="status" aria-live="polite">正在加载分区...</p>
        </div>

        <div>
          <label for="thread-create-title" class="mb-1 block text-sm font-medium">标题</label>
          <input id="thread-create-title" v-model="form.title" type="text" class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="请输入标题" />
        </div>

        <div>
          <label for="thread-create-tags" class="mb-1 block text-sm font-medium">标签</label>
          <input
            id="thread-create-tags"
            v-model="form.tags"
            type="text"
            class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
            placeholder="用空格或逗号分隔，最多 5 个"
          />
          <div v-if="parsedTags.length" class="mt-2 flex flex-wrap gap-2">
            <span v-for="tag in parsedTags" :key="tag" class="ui-tag">
              #{{ tag }}
            </span>
          </div>
        </div>

        <div>
          <label class="mb-1 block text-sm font-medium">内容（支持 Markdown）</label>
          <MarkdownTextareaEditor
            v-model="form.content"
            :max-length="THREAD_CONTENT_MAX_LENGTH"
            :rows="16"
            :uploading="isUploading"
            :upload-progress="uploadProgress"
            :upload-image="uploadThreadImage"
            placeholder="请输入帖子正文，支持 Markdown"
            preview-label="正文预览"
          />
        </div>

        <div class="flex flex-col items-stretch gap-3 border-t border-gray-200 pt-4 dark:border-gray-700 sm:flex-row sm:flex-wrap sm:items-center">
          <button type="button" class="ui-button" @click="onSaveDraft">保存到本机</button>
          <button type="button" class="ui-button" @click="beautifyContent">美化内容</button>
          <button :disabled="submitting" class="ui-button-primary" @click="submit">
            {{ submitting ? '发布中...' : '发布' }}
          </button>
          <div v-if="!isLoggedIn" class="flex flex-wrap items-center gap-2 text-sm ui-muted">
            <span>请先登录后再发帖</span>
            <button
              type="button"
              class="ui-button px-2.5 py-1 text-xs"
              @click="onLoginClick"
            >
              立即登录
            </button>
          </div>
          <span v-if="success" class="text-sm text-emerald-700 dark:text-emerald-300" role="status" aria-live="polite">{{ success }}</span>
          <router-link
            v-if="createdThreadId != null"
            :to="`/threads/${createdThreadId}`"
            class="text-sm text-[var(--ui-accent)] underline hover:opacity-80"
          >
            查看帖子
          </router-link>
          <span v-if="error" class="ui-error py-2 text-sm" role="alert" aria-live="assertive">{{ error }}</span>
          <span v-if="draftMessage" class="ui-muted text-xs" role="status" aria-live="polite">{{ draftMessage }}</span>
          <button v-if="draftHasData" type="button" class="ui-button px-2.5 py-1 text-xs" @click="onClearDraft">清除草稿</button>
        </div>
      </div>
    </div>
  </div>
  </template>

<style scoped>
</style>

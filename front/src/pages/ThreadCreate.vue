<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { listSections } from '@/api/sections'
import { createThread } from '@/api/threads'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
import { beautifyMarkdown, useDraft } from '@/composables/useMarkdownTools'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import type { Section } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const success = ref('')
const sections = ref<Section[]>([])
const authStore = useAuthStore()
const { isLoggedIn, token } = storeToRefs(authStore)

interface ThreadCreateForm {
  sectionId: string
  title: string
  content: string
  tags: string
}

const form = ref<ThreadCreateForm>({
  sectionId: '',
  title: '',
  content: '',
  tags: '',
})

const isUploading = ref(false)
const uploadProgress = ref(0)

// 草稿存储键与提示信息
const DRAFT_KEY = 'thread-draft-v1'
const draftMessage = ref('')
// 通用草稿：面向整个 form 对象，防抖自动保存
const { draftHasData, saveDraft, restoreDraft, clearDraft, startAutoSave, stopAutoSave } = useDraft(DRAFT_KEY, {
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
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || '图片上传失败'
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
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (_) {
    // 保持空列表即可
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  error.value = ''
  success.value = ''
  if (!form.value.sectionId || !form.value.title || !form.value.content) {
    error.value = '请完整填写分区、标题与内容'
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
    success.value = '发布成功'
    // 发布成功后清除草稿
    clearDraft()
    // 成功后重置所有输入：分区、标题、内容
    form.value = { sectionId: '', title: '', content: '', tags: '' }
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '发布失败，请稍后重试'
    error.value = msg
  } finally {
    submitting.value = false
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
  draftMessage.value = '已保存草稿'
  setTimeout(() => { draftMessage.value = '' }, 1500)
}

function onClearDraft(): void {
  clearDraft()
  draftMessage.value = '已清除草稿'
  setTimeout(() => { draftMessage.value = '' }, 1500)
}

function onLoginClick(): void {
  error.value = '请先登录后再发帖'
  requestLogin()
}

// 标准化美化：调用通用 beautifyMarkdown
async function beautifyContent(): Promise<void> {
  try {
    form.value.content = await beautifyMarkdown(form.value.content)
    draftMessage.value = '已美化内容'
    setTimeout(() => { draftMessage.value = '' }, 1500)
  } catch (_) {
    draftMessage.value = '美化失败'
    setTimeout(() => { draftMessage.value = '' }, 1500)
  }
}

onMounted(() => {
  loadSections()
  // 草稿检测与自动恢复
  if (draftHasData.value && !form.value.title && !form.value.content) {
    restoreDraft()
    draftMessage.value = '已恢复草稿'
    setTimeout(() => { draftMessage.value = '' }, 1500)
  }
  startAutoSave()
})

onUnmounted(() => {
  stopAutoSave()
})

// 自动保存交由 composable 管理
</script>

<template>
  <div>
    <div class="rounded-xl border border-gray-100 bg-white p-4 shadow-sm dark:bg-gray-800 dark:border-gray-700">
      <h1 class="text-2xl font-semibold mb-4">发帖</h1>

      <div class="space-y-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">分区</label>
          <select v-model="form.sectionId" class="w-full rounded border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400">
            <option value="" disabled>请选择分区</option>
            <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name }}</option>
          </select>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">标题</label>
          <input v-model="form.title" type="text" class="w-full rounded border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="请输入标题" />
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">标签</label>
          <input
            v-model="form.tags"
            type="text"
            class="w-full rounded border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
            placeholder="用空格或逗号分隔，最多 5 个"
          />
          <div v-if="parsedTags.length" class="mt-2 flex flex-wrap gap-2">
            <span v-for="tag in parsedTags" :key="tag" class="rounded-md bg-brandDay-50 px-2.5 py-1 text-xs font-medium text-brandDay-600 dark:bg-brandNight-900/30 dark:text-brandNight-300">
              #{{ tag }}
            </span>
          </div>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">内容（支持 Markdown）</label>
          <MarkdownTextareaEditor
            v-model="form.content"
            :rows="16"
            :uploading="isUploading"
            :upload-progress="uploadProgress"
            :upload-image="uploadThreadImage"
            placeholder="请输入帖子正文，支持 Markdown"
            preview-label="正文预览"
          />
        </div>

        <div class="flex items-center gap-2">
          <button type="button" class="rounded border px-3 py-1 text-sm dark:border-gray-700 dark:text-gray-200" @click="onSaveDraft">保存草稿</button>
          <button type="button" class="rounded border px-3 py-1 text-sm dark:border-gray-700 dark:text-gray-200" @click="beautifyContent">美化内容</button>
          <button :disabled="submitting" class="inline-flex items-center rounded bg-brandDay-600 dark:bg-brandNight-600 px-4 py-2 text-sm font-medium text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:cursor-not-allowed disabled:opacity-50 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="submit">
            {{ submitting ? '发布中...' : '发布' }}
          </button>
          <div v-if="!isLoggedIn" class="flex items-center gap-2 text-sm text-gray-600 dark:text-gray-300">
            <span>请先登录后再发帖</span>
            <button
              type="button"
              class="rounded border border-gray-300 px-2.5 py-1 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700"
              @click="onLoginClick"
            >
              立即登录
            </button>
          </div>
          <span v-if="success" class="text-sm text-green-600 dark:text-green-400">{{ success }}</span>
          <span v-if="error" class="text-sm text-red-600 dark:text-red-400">{{ error }}</span>
          <span v-if="draftMessage" class="text-xs text-gray-500 dark:text-gray-400">{{ draftMessage }}</span>
          <button v-if="draftHasData" type="button" class="text-xs text-gray-500 underline decoration-dotted" @click="onClearDraft">清除草稿</button>
        </div>
      </div>
    </div>
  </div>
  </template>

<style scoped>
</style>

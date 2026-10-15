<script setup lang="ts">
import { isAxiosError } from 'axios'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listSections } from '@/api/sections'
import { getThread } from '@/api/threads'
import { updateThread } from '@/api/my'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { storeToRefs } from 'pinia'
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
const error = ref('')
const success = ref('')
const sections = ref<Section[]>([])
const form = ref<ThreadEditForm>({ sectionId: '', title: '', content: '', tags: '' })

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

async function loadSections(): Promise<void> {
  try {
    const data = await listSections({ size: 100 })
    sections.value = data.items
  } catch (_) {
    sections.value = []
  }
}

async function loadThread(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const thread = await getThread(threadId.value)
    form.value = {
      sectionId: String(thread.sectionId || ''),
      title: thread.title || '',
      content: thread.content || thread.contentMd || '',
      tags: Array.isArray(thread.tags) ? thread.tags.join(' ') : '',
    }
  } catch (_) {
    error.value = '加载帖子失败'
  } finally {
    loading.value = false
  }
}

async function uploadThreadImage(file: File): Promise<string> {
  try {
    isUploading.value = true
    uploadProgress.value = 0
    const { url, path } = await uploadImage(file, token.value, (progress) => {
      uploadProgress.value = progress
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

async function beautifyContent(): Promise<void> {
  try {
    form.value.content = await beautifyMarkdown(form.value.content)
    success.value = '已美化内容'
    setTimeout(() => { success.value = '' }, 1500)
  } catch (_) {
    error.value = '美化失败'
  }
}

async function submit(): Promise<void> {
  error.value = ''
  success.value = ''
  if (!form.value.title.trim() || !form.value.content.trim()) {
    error.value = '请填写标题和内容'
    return
  }
  submitting.value = true
  try {
    const sectionId = form.value.sectionId || undefined
    const tags = parsedTags.value
    await updateThread(threadId.value, {
      title: form.value.title,
      content: form.value.content,
      sectionId,
      tags,
    })
    try {
      window.dispatchEvent(new CustomEvent('threads-updated', { detail: { reason: 'updated', threadId: threadId.value, sectionId, tags } }))
      window.dispatchEvent(new CustomEvent('thread-tags-updated', { detail: { reason: 'updated', threadId: threadId.value, sectionId, tags } }))
    } catch (_) {}
    success.value = '保存成功'
    setTimeout(() => {
      router.push(`/threads/${threadId.value}`)
    }, 400)
  } catch (e: unknown) {
    error.value = errorMessage(e, '保存失败')
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

function cancel(): void {
  router.push('/my/threads')
}

onMounted(async () => {
  await Promise.all([loadSections(), loadThread()])
})
</script>

<template>
  <div class="ui-page">
    <header class="ui-page-header">
      <div>
        <h1 class="ui-title">编辑帖子</h1>
        <p class="ui-description">修改标题、分区和正文内容。</p>
      </div>
      <button class="ui-button" @click="cancel">
        返回我的帖子
      </button>
    </header>

    <div class="ui-panel p-4 sm:p-6">
      <div v-if="loading" class="ui-muted ui-empty" role="status" aria-live="polite">正在加载帖子...</div>
      <div v-else class="space-y-4">
      <div>
        <label for="thread-edit-section" class="mb-1 block text-sm font-medium">分区</label>
        <select id="thread-edit-section" v-model="form.sectionId" class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]">
          <option value="">保持原分区</option>
          <option v-for="section in sections" :key="section.id" :value="section.id">{{ section.name }}</option>
        </select>
      </div>

      <div>
        <label for="thread-edit-title" class="mb-1 block text-sm font-medium">标题</label>
        <input id="thread-edit-title" v-model="form.title" type="text" class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="请输入标题" />
      </div>

      <div>
        <label for="thread-edit-tags" class="mb-1 block text-sm font-medium">标签</label>
        <input
          id="thread-edit-tags"
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
          :rows="16"
          :uploading="isUploading"
          :upload-progress="uploadProgress"
          :upload-image="uploadThreadImage"
          placeholder="请输入帖子正文，支持 Markdown"
          preview-label="正文预览"
        />
      </div>

      <div class="flex flex-col items-stretch gap-3 border-t border-gray-200 pt-4 dark:border-gray-700 sm:flex-row sm:flex-wrap sm:items-center">
        <button type="button" class="ui-button" @click="beautifyContent">美化内容</button>
        <button :disabled="submitting" class="ui-button-primary" @click="submit">
          {{ submitting ? '保存中...' : '保存修改' }}
        </button>
        <button type="button" class="ui-button" @click="cancel">取消</button>
        <span v-if="success" class="text-sm text-emerald-700 dark:text-emerald-300" role="status" aria-live="polite">{{ success }}</span>
        <span v-if="error" class="ui-error py-2 text-sm" role="alert" aria-live="assertive">{{ error }}</span>
      </div>
    </div>
    </div>
  </div>
</template>

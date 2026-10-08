<script setup lang="ts">
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

const threadId = computed<Id>(() => String(route.params.id || ''))

async function loadSections(): Promise<void> {
  try {
    const data = await listSections({ size: 100 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
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
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || '图片上传失败'
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
  } catch (e: any) {
    error.value = e?.response?.data?.message || e?.message || '保存失败'
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
  <div class="rounded-xl border border-gray-100 bg-white p-5 shadow-sm dark:border-gray-700 dark:bg-gray-800">
    <div class="mb-5 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
      <div>
        <h1 class="text-xl font-bold text-gray-900 dark:text-gray-100">编辑帖子</h1>
        <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">修改标题、分区和正文内容</p>
      </div>
      <button class="rounded border border-gray-200 px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-gray-700" @click="cancel">
        返回我的帖子
      </button>
    </div>

    <div v-if="loading" class="py-10 text-center text-sm text-gray-500">正在加载...</div>
    <div v-else class="space-y-4">
      <div>
        <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-200">分区</label>
        <select v-model="form.sectionId" class="w-full rounded border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100">
          <option value="">保持原分区</option>
          <option v-for="section in sections" :key="section.id" :value="section.id">{{ section.name }}</option>
        </select>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-200">标题</label>
        <input v-model="form.title" type="text" class="w-full rounded border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100" placeholder="请输入标题" />
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-200">标签</label>
        <input
          v-model="form.tags"
          type="text"
          class="w-full rounded border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100"
          placeholder="用空格或逗号分隔，最多 5 个"
        />
        <div v-if="parsedTags.length" class="mt-2 flex flex-wrap gap-2">
          <span v-for="tag in parsedTags" :key="tag" class="rounded-md bg-brandDay-50 px-2.5 py-1 text-xs font-medium text-brandDay-600 dark:bg-brandNight-900/30 dark:text-brandNight-300">
            #{{ tag }}
          </span>
        </div>
      </div>

      <div>
        <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-200">内容（支持 Markdown）</label>
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

      <div class="flex flex-wrap items-center gap-2">
        <button type="button" class="rounded border px-3 py-1.5 text-sm dark:border-gray-700 dark:text-gray-200" @click="beautifyContent">美化内容</button>
        <button :disabled="submitting" class="rounded bg-brandDay-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-brandDay-700 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-brandNight-600 dark:hover:bg-brandNight-700" @click="submit">
          {{ submitting ? '保存中...' : '保存修改' }}
        </button>
        <button type="button" class="rounded border border-gray-200 px-3 py-2 text-sm text-gray-600 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300 dark:hover:bg-gray-700" @click="cancel">取消</button>
        <span v-if="success" class="text-sm text-green-600 dark:text-green-400">{{ success }}</span>
        <span v-if="error" class="text-sm text-red-600 dark:text-red-400">{{ error }}</span>
      </div>
    </div>
  </div>
</template>

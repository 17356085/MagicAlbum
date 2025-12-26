<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { MdEditor } from 'md-editor-v3'
import 'md-editor-v3/lib/style.css'
import { listSections } from '@/api/sections'
import { createThread } from '@/api/threads'
import { uploadImage } from '@/api/uploads'
import { useAuth } from '@/composables/useAuth'

const loading = ref(false)
const submitting = ref(false)
const error = ref('')
const success = ref('')
const sections = ref([])
const { isLoggedIn, token } = useAuth()

const form = ref({
  sectionId: '',
  title: '',
  content: ''
})

const isUploading = ref(false)
const uploadProgress = ref(0)

// 跟随全局暗色模式：监听 html.dark 与系统偏好
const isDark = ref(false)
function updateIsDark() {
  const hasDarkClass = document.documentElement.classList.contains('dark')
  const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
  isDark.value = hasDarkClass || prefersDark
}
let themeObserver = null

// 规范化图片URL：将相对路径（/uploads/... 或 uploads/...）拼接为后端完整地址
function normalizeImageUrl(u) {
  if (!u) return ''
  const url = String(u).trim()
  if (!url) return ''
  if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
    return url
  }
  const apiBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1'
  const backendBase = apiBase.replace(/\/api\/v1$/, '')
  if (url.startsWith('/')) return backendBase + url
  return backendBase + '/' + url
}

async function onUploadImg(files, callback) {
  try {
    isUploading.value = true
    uploadProgress.value = 0
    const urls = []
    for (const f of files) {
      const { url } = await uploadImage(f, token.value, (p) => {
        uploadProgress.value = p
      })
      urls.push(normalizeImageUrl(url))
    }
    callback(urls)
  } catch (e) {
    error.value = e?.response?.data?.message || e?.message || '图片上传失败'
  } finally {
    isUploading.value = false
    uploadProgress.value = 0
  }
}

async function loadSections() {
  try {
    const data = await listSections({ size: 100 })
    sections.value = Array.isArray(data) ? data : (data.items || [])
  } catch (e) {
    // 保持空列表即可
  }
}

async function submit() {
  error.value = ''
  success.value = ''
  if (!form.value.sectionId || !form.value.title || !form.value.content) {
    error.value = '请完整填写分区、标题与内容'
    return
  }
  // 未登录或无令牌时阻止提交
  if (!isLoggedIn.value || !token.value) {
    error.value = '请先登录后再发帖'
    return
  }
  if (String(token.value).startsWith('mock-token-')) {
    error.value = '当前为模拟令牌，请退出并用真实账号登录'
    return
  }
  submitting.value = true
  try {
    const payload = {
      sectionId: form.value.sectionId,
      title: form.value.title,
      content: form.value.content
    }
    await createThread(payload)
    success.value = '发布成功'
    form.value.content = ''
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '发布失败，请稍后重试'
    error.value = msg
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadSections()
  // 初始化与监听暗色状态
  updateIsDark()
  if (window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => updateIsDark()
    if (typeof mq.addEventListener === 'function') mq.addEventListener('change', handler)
    else if (typeof mq.addListener === 'function') mq.addListener(handler)
  }
  themeObserver = new MutationObserver(updateIsDark)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
})

onUnmounted(() => {
  if (themeObserver) {
    try { themeObserver.disconnect() } catch (_) {}
    themeObserver = null
  }
})
</script>

<template>
  <div class="container mx-auto p-6">
    <h1 class="text-2xl font-semibold mb-4">发帖</h1>

    <div class="space-y-4">
      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">分区</label>
        <select v-model="form.sectionId" class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100">
          <option value="" disabled>请选择分区</option>
          <option v-for="s in sections" :key="s.id" :value="s.id">{{ s.name || s.title }}</option>
        </select>
      </div>

      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">标题</label>
        <input v-model="form.title" type="text" class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100" placeholder="请输入标题" />
      </div>

      <div>
        <label class="block text-sm font-medium text-gray-700 mb-1 dark:text-gray-200">内容（支持 Markdown）</label>
        <div class="relative">
          <MdEditor v-model="form.content" :onUploadImg="onUploadImg" :theme="isDark ? 'dark' : 'light'" class="rounded-md border border-gray-300 dark:border-gray-700" />
          <div v-if="isUploading" class="absolute top-2 right-3 text-xs bg-white/80 px-2 py-1 rounded border border-gray-200 dark:bg-gray-800/80 dark:border-gray-700 dark:text-gray-200">
            上传中 {{ uploadProgress }}%
          </div>
        </div>
      </div>

      <div class="flex items-center gap-2">
        <button :disabled="submitting" class="inline-flex items-center rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50" @click="submit">
          {{ submitting ? '发布中...' : '发布' }}
        </button>
        <span v-if="!isLoggedIn" class="text-sm text-gray-600 dark:text-gray-300">请先登录后再发帖</span>
        <span v-if="success" class="text-sm text-green-600 dark:text-green-400">{{ success }}</span>
        <span v-if="error" class="text-sm text-red-600 dark:text-red-400">{{ error }}</span>
      </div>
    </div>
  </div>
  </template>

<style scoped>
</style>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getSummary, triggerSummary } from '@/api/ai'
import type { AiSummaryData } from '@/api/ai'
import type { Id } from '@/types'

const props = defineProps<{
  threadId?: Id | null
}>()

const aiSummary = ref('')
const aiStatus = ref('')
const aiLoading = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

function resetState(): void {
  aiSummary.value = ''
  aiStatus.value = ''
  aiLoading.value = false
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function loadSummary(threadId: Id): Promise<void> {
  try {
    const res = await getSummary(threadId)
    const payload = (res.data || {}) as AiSummaryData
    aiSummary.value = payload.summary || ''
    aiStatus.value = payload.status || ''
  } catch (_) {}
}

async function handleGenerateSummary(): Promise<void> {
  if (!props.threadId) return
  aiLoading.value = true
  try {
    await triggerSummary(props.threadId, true)
    aiStatus.value = 'PENDING'
    let checks = 0
    if (pollTimer) clearInterval(pollTimer)
    pollTimer = setInterval(async () => {
      checks += 1
      await loadSummary(props.threadId as Id)
      if (aiStatus.value === 'COMPLETED' || checks > 10) {
        if (pollTimer) {
          clearInterval(pollTimer)
          pollTimer = null
        }
        aiLoading.value = false
      }
    }, 2000)
  } catch (_) {
    aiLoading.value = false
  }
}

watch(
  () => props.threadId,
  async (threadId) => {
    resetState()
    if (threadId) {
      await loadSummary(threadId)
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => resetState())
</script>

<template>
  <div v-if="aiSummary || aiLoading" class="mx-5 mt-5 rounded-lg border border-indigo-100 bg-indigo-50/50 p-4 dark:border-indigo-900/50 dark:bg-indigo-900/10">
    <div class="mb-2 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="flex h-6 w-6 items-center justify-center rounded-full bg-indigo-100 text-indigo-600 dark:bg-indigo-900 dark:text-indigo-300">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-3.5 w-3.5">
            <path fill-rule="evenodd" d="M9.315 7.584C12.195 3.883 16.695 1.5 21.75 1.5a.75.75 0 0 1 .75.75c0 5.056-2.383 9.555-6.084 12.436A6.753 6.753 0 0 1 9.75 22.5a.75.75 0 0 1-.75-.75v-4.131A15.838 15.838 0 0 1 6.382 15H2.25a.75.75 0 0 1-.75-.75 6.75 6.75 0 0 1 7.815-6.666ZM15 6.75a2.25 2.25 0 1 0 0 4.5 2.25 2.25 0 0 0 0-4.5Z" clip-rule="evenodd" />
          </svg>
        </span>
        <span class="text-sm font-bold text-indigo-900 dark:text-indigo-100">AI 智能摘要</span>
      </div>
      <button
        :disabled="aiLoading || aiStatus === 'PENDING'"
        class="text-xs font-medium text-indigo-600 disabled:opacity-50 hover:text-indigo-700 dark:text-indigo-400 dark:hover:text-indigo-300"
        @click="handleGenerateSummary"
      >
        {{ aiLoading || aiStatus === 'PENDING' ? '生成中...' : '重新生成' }}
      </button>
    </div>
    <div class="pl-8 text-sm leading-relaxed text-indigo-900/80 dark:text-indigo-100/80">
      {{ aiSummary || '正在分析帖子内容，请稍候...' }}
    </div>
  </div>
  <div v-else class="mx-5 mt-2 flex justify-end">
    <button class="flex items-center gap-1 text-xs text-gray-400 transition-colors hover:text-indigo-500" @click="handleGenerateSummary">
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="h-3 w-3">
        <path fill-rule="evenodd" d="M9.315 7.584C12.195 3.883 16.695 1.5 21.75 1.5a.75.75 0 0 1 .75.75c0 5.056-2.383 9.555-6.084 12.436A6.753 6.753 0 0 1 9.75 22.5a.75.75 0 0 1-.75-.75v-4.131A15.838 15.838 0 0 1 6.382 15H2.25a.75.75 0 0 1-.75-.75 6.75 6.75 0 0 1 7.815-6.666ZM15 6.75a2.25 2.25 0 1 0 0 4.5 2.25 2.25 0 0 0 0-4.5Z" clip-rule="evenodd" />
      </svg>
      生成 AI 摘要
    </button>
  </div>
</template>

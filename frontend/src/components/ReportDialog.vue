<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { createReport, type ReportReason, type ReportTargetType } from '@/api/reports'

const props = defineProps<{
  modelValue: boolean
  targetType: ReportTargetType
  targetId: number
  isLoggedIn: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const dialogRef = ref<HTMLElement | null>(null)
const reason = ref<ReportReason>('SPAM')
const detail = ref('')
const isSubmitting = ref(false)
const submitted = ref(false)
const errorMessage = ref('')
const successMessage = ref('')
let previousActiveElement: HTMLElement | null = null

function getErrorMessage(cause: unknown): string {
  const failure = cause as {
    response?: { status?: unknown; data?: { message?: unknown } }
  } | null
  const status = Number(failure?.response?.status || 0)
  if (status === 401) return '请先登录后再举报'

  const responseMessage = failure?.response?.data?.message
  return typeof responseMessage === 'string' && responseMessage.trim()
    ? responseMessage.trim()
    : '提交举报失败，请稍后重试'
}

function resetForm(): void {
  reason.value = 'SPAM'
  detail.value = ''
  submitted.value = false
  errorMessage.value = ''
  successMessage.value = ''
}

function restoreFocus(): void {
  const element = previousActiveElement
  previousActiveElement = null
  try {
    if (element && document.contains(element)) element.focus()
  } catch (_) {}
}

function close(): void {
  emit('update:modelValue', false)
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  }
}

async function submit(): Promise<void> {
  if (isSubmitting.value || submitted.value) return
  if (!props.isLoggedIn) {
    errorMessage.value = '请先登录后再举报'
    successMessage.value = ''
    return
  }

  const targetId = Number(props.targetId)
  if (!Number.isSafeInteger(targetId) || targetId <= 0) {
    errorMessage.value = '举报目标无效，请刷新页面后重试'
    successMessage.value = ''
    return
  }

  isSubmitting.value = true
  errorMessage.value = ''
  successMessage.value = ''
  const normalizedDetail = detail.value.trim()
  try {
    await createReport({
      targetType: props.targetType,
      targetId,
      reason: reason.value,
      ...(normalizedDetail ? { detail: normalizedDetail } : {}),
    })
    submitted.value = true
    successMessage.value = '举报已提交，感谢你的反馈'
  } catch (cause: unknown) {
    errorMessage.value = getErrorMessage(cause)
  } finally {
    isSubmitting.value = false
  }
}

watch(
  () => props.modelValue,
  (open, wasOpen) => {
    if (open) {
      if (!wasOpen) {
        previousActiveElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
        resetForm()
      }
      window.addEventListener('keydown', onKeydown)
      void nextTick(() => dialogRef.value?.focus())
      return
    }

    window.removeEventListener('keydown', onKeydown)
    if (wasOpen) restoreFocus()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  restoreFocus()
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="ui-dialog-layer fixed inset-0 flex items-center justify-center bg-black/40 p-4"
      data-report-dialog-backdrop
      @click.self="close"
    >
      <section
        ref="dialogRef"
        class="ui-panel ui-scroll relative w-full max-w-md p-6 max-h-[calc(100dvh-2rem)] overflow-y-auto"
        role="dialog"
        aria-modal="true"
        aria-labelledby="report-dialog-title"
        tabindex="-1"
        @click.stop
      >
        <div class="flex items-start justify-between gap-4">
          <div>
            <h2 id="report-dialog-title" class="text-lg font-semibold text-gray-900 dark:text-gray-100">举报内容</h2>
            <p class="mt-1 text-sm text-gray-500 dark:ui-muted">请选择举报原因，我们会尽快处理。</p>
          </div>
          <button
            type="button"
            class="rounded p-1 text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:ui-muted dark:hover:bg-gray-800 dark:hover:text-gray-200"
            aria-label="关闭举报对话框"
            @click="close"
          >
            ✕
          </button>
        </div>

        <form class="mt-5 space-y-4" @submit.prevent="submit">
          <div>
            <label for="report-reason" class="block text-sm font-medium text-gray-700 dark:text-gray-200">举报原因</label>
            <select
              id="report-reason"
              v-model="reason"
              class="ui-field mt-2 text-sm"
              :disabled="isSubmitting || submitted"
            >
              <option value="SPAM">垃圾广告或灌水</option>
              <option value="HARASSMENT">骚扰、人身攻击或仇恨内容</option>
              <option value="OTHER">其他违规内容</option>
            </select>
          </div>

          <div>
            <label for="report-detail" class="block text-sm font-medium text-gray-700 dark:text-gray-200">补充说明 <span class="font-normal ui-muted">（可选）</span></label>
            <textarea
              id="report-detail"
              v-model="detail"
              maxlength="500"
              rows="4"
              class="ui-field mt-2 resize-y text-sm"
              placeholder="请简要说明问题"
              :disabled="isSubmitting || submitted"
            />
            <div class="mt-1 text-right text-xs ui-muted">{{ detail.length }}/500</div>
          </div>

          <p v-if="errorMessage" class="text-sm text-red-600 dark:text-red-300" role="alert">{{ errorMessage }}</p>
          <p v-if="successMessage" class="text-sm text-green-700 dark:text-green-300" role="status">{{ successMessage }}</p>
          <p v-if="!isLoggedIn && !errorMessage" class="text-sm text-amber-700 dark:text-amber-300" role="alert">请先登录后再举报</p>

          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="ui-button"
              @click="close"
            >
              取消
            </button>
            <button
              type="submit"
              class="ui-button-primary disabled:opacity-60"
              :disabled="isSubmitting || submitted || !isLoggedIn"
              :aria-busy="isSubmitting"
            >
              {{ isSubmitting ? '提交中…' : submitted ? '已提交' : '提交举报' }}
            </button>
          </div>
        </form>
      </section>
    </div>
  </Teleport>
</template>

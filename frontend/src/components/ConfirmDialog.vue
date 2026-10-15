<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const dialogPanel = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
interface ConfirmDialogProps {
  title?: string
  message?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  loading?: boolean
}

const props = withDefaults(defineProps<ConfirmDialogProps>(), {
  title: '确认操作',
  message: '',
  confirmText: '确认',
  cancelText: '取消',
  danger: false,
  loading: false,
})

const emit = defineEmits<{
  confirm: []
  cancel: []
}>()

function onConfirm(): void {
  if (props.loading) return
  emit('confirm')
}
function onCancel(): void {
  if (props.loading) return
  emit('cancel')
}
function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    onCancel()
    return
  }
  if (event.key !== 'Tab') return
  const controls = Array.from(dialogPanel.value?.querySelectorAll<HTMLElement>('button:not(:disabled), [tabindex="0"]') || [])
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (!first) {
    event.preventDefault()
    dialogPanel.value?.focus()
  } else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialogPanel.value)) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}
onMounted(() => {
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  void nextTick(() => dialogPanel.value?.focus())
})
onBeforeUnmount(() => {
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<template>
  <div class="ui-dialog-layer fixed inset-0 flex items-center justify-center p-4">
    <div class="absolute inset-0 bg-black/30" @click="onCancel"></div>
    <div ref="dialogPanel" class="ui-panel relative z-10 w-full max-w-sm" role="dialog" aria-modal="true" aria-labelledby="confirm-dialog-title" tabindex="-1" @keydown="onDialogKeydown">
      <div class="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h3 id="confirm-dialog-title" class="text-base font-semibold">{{ title }}</h3>
        <button class="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-700 disabled:opacity-60" @click="onCancel" aria-label="关闭" :disabled="loading">✕</button>
      </div>
      <div class="px-4 py-4 text-sm text-gray-700 dark:text-gray-200">{{ message }}</div>
      <div class="px-4 pb-4 flex items-center justify-end gap-2">
        <button class="ui-button" @click="onCancel" :disabled="loading">{{ cancelText }}</button>
        <button
          class="ui-button disabled:opacity-60 !text-white"
    :class="danger ? 'bg-red-600 hover:bg-red-700' : 'bg-brandDay-600 dark:bg-brandNight-600 hover:bg-brandDay-700 dark:hover:bg-brandNight-700'"
          @click="onConfirm"
          :disabled="loading"
        >
          {{ loading ? '处理中…' : confirmText }}
        </button>
      </div>
    </div>
  </div>
  
  <!-- 防止页面滚动 -->
  <div aria-hidden="true" style="overflow:hidden;height:0;width:0"></div>
</template>

<style scoped>
</style>

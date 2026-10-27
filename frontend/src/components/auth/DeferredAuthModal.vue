<template>
  <div
    v-if="!closed && !authModal"
    ref="fallbackDialog"
    class="ui-dialog-layer fixed inset-0 flex items-center justify-center p-3 sm:p-6"
    role="dialog"
    aria-modal="true"
    aria-labelledby="deferred-auth-title"
    aria-describedby="deferred-auth-status"
    :aria-busy="isLoading"
    tabindex="-1"
    @keydown="onFallbackKeydown"
  >
    <div class="absolute inset-0 bg-black/30" aria-hidden="true" @click="requestClose"></div>
    <section class="ui-panel relative z-10 w-full max-w-sm p-5">
      <h2 id="deferred-auth-title" class="text-lg font-semibold">账户中心</h2>
      <p
        id="deferred-auth-status"
        class="ui-muted mt-2 text-sm"
        :role="loadError ? 'alert' : 'status'"
        aria-live="polite"
      >
        {{ loadError || '正在加载登录窗口…' }}
      </p>
      <div class="mt-5 flex justify-end gap-2">
        <button ref="closeButton" type="button" class="ui-button" aria-label="关闭" @click="requestClose">关闭</button>
        <button v-if="loadError" ref="retryButton" type="button" class="ui-button-primary" @click="retry">重试</button>
      </div>
    </section>
  </div>

  <component
    :is="authModal"
    v-if="authModal && !closed"
    @close="forwardClose"
    @success="forwardSuccess"
  />
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, shallowRef, type Component } from 'vue'

type AuthModalModule = { default: Component }

const props = defineProps<{
  loader?: () => Promise<AuthModalModule>
}>()

const emit = defineEmits<{
  close: []
  success: []
}>()

const authModal = shallowRef<Component>()
const fallbackDialog = ref<HTMLElement | null>(null)
const closeButton = ref<HTMLButtonElement | null>(null)
const retryButton = ref<HTMLButtonElement | null>(null)
const loadError = ref('')
const isLoading = ref(false)
const closed = ref(false)
let originalFocus: HTMLElement | null = null
let disposed = false
let loadRequestId = 0

function focusFallback(): void {
  const focus = (): void => {
    if (disposed || closed.value || authModal.value) return
    const first = loadError.value ? retryButton.value : closeButton.value
    const target = first || fallbackDialog.value
    target?.focus()
  }

  if (loadError.value ? retryButton.value : closeButton.value) {
    focus()
  } else {
    void nextTick(focus)
  }
}

async function loadAuthModal(): Promise<void> {
  if (disposed || closed.value || isLoading.value || authModal.value) return
  isLoading.value = true
  loadError.value = ''
  focusFallback()
  const requestId = ++loadRequestId
  try {
    const module = await (props.loader ? props.loader() : import('./AuthModal.vue'))
    if (disposed || closed.value || requestId !== loadRequestId) return
    authModal.value = module.default
  } catch (_) {
    if (disposed || closed.value || requestId !== loadRequestId) return
    loadError.value = '登录窗口加载失败，请重试。'
    focusFallback()
  } finally {
    if (requestId === loadRequestId) isLoading.value = false
  }
}

function restoreOriginalFocus(): void {
  if (originalFocus?.isConnected) originalFocus.focus()
}

function requestClose(): void {
  if (closed.value) return
  closed.value = true
  disposed = true
  loadRequestId += 1
  emit('close')
  void nextTick(restoreOriginalFocus)
}

function retry(): void {
  void loadAuthModal()
}

function forwardClose(): void {
  requestClose()
}

function forwardSuccess(): void {
  if (!disposed && !closed.value) emit('success')
}

function onFallbackKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    requestClose()
    return
  }
  if (event.key !== 'Tab') return

  const controls = Array.from(fallbackDialog.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]',
  ) || [])
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (!first) {
    event.preventDefault()
    fallbackDialog.value?.focus()
  } else if (!fallbackDialog.value?.contains(document.activeElement)) {
    event.preventDefault()
    first.focus()
  } else if (event.shiftKey && (document.activeElement === first || document.activeElement === fallbackDialog.value)) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === fallbackDialog.value)) {
    event.preventDefault()
    first.focus()
  }
}

onMounted(() => {
  originalFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  focusFallback()
  void loadAuthModal()
})

onUnmounted(() => {
  disposed = true
  loadRequestId += 1
  restoreOriginalFocus()
})
</script>

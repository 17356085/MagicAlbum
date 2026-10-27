<template>
  <div class="ui-dialog-layer fixed inset-0 flex items-center justify-center p-3 sm:p-6">
    <div class="absolute inset-0 bg-black/30 motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none" @click="onClose"></div>
    <div ref="dialogPanel" class="ui-panel ui-scroll relative z-10 max-h-[calc(100vh-1.5rem)] w-full max-w-[700px] overflow-y-auto motion-safe:transition-colors motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none sm:max-h-[calc(100vh-3rem)]" role="dialog" aria-modal="true" aria-labelledby="auth-modal-title" tabindex="-1" @keydown="onDialogKeydown">
      <div class="grid md:grid-cols-[236px_minmax(0,1fr)]">
        <section class="order-1 flex min-h-[360px] flex-col md:order-2 md:min-h-[392px]">
          <div class="flex flex-wrap items-center justify-between gap-3 border-b border-gray-200 px-4 py-3 dark:border-gray-700">
            <div class="min-w-0 flex-1">
              <div class="ui-muted text-xs font-medium uppercase tracking-[0.18em]">账户中心</div>
              <h3 id="auth-modal-title" class="mt-0.5 text-lg font-semibold">{{ currentTitle }}</h3>
            </div>
            <div class="flex shrink-0 gap-1" role="group" aria-label="认证方式">
              <button
                type="button"
                :aria-pressed="authView === 'login'"
                class="auth-view-tab ui-button min-h-9 px-3 py-1 text-xs"
                @click="switchAuthView('login')"
              >
                登录
              </button>
              <button
                type="button"
                :aria-pressed="authView === 'register'"
                class="auth-view-tab ui-button min-h-9 px-3 py-1 text-xs"
                @click="switchAuthView('register')"
              >
                注册
              </button>
            </div>
            <button type="button" class="ui-button min-h-9 px-2 py-1 text-sm" @click="onClose" aria-label="关闭">✕</button>
          </div>

          <div class="flex-1 bg-[var(--ui-surface)]">
            <div class="mx-auto flex h-full w-full max-w-lg flex-col justify-center">
              <AuthLoginForm
                v-if="authView === 'login'"
                :initial-phone="seedPhone"
                :initial-email="seedEmail"
                @close="onClose"
                @success="onLoginSuccess"
                @notify="showToast"
                @switch-register="switchAuthView('register')"
              />

              <AuthRegisterForm
                v-else
                @close="onClose"
                @notify="showToast"
                @switch-login="switchAuthView('login')"
                @registered="onRegistered"
              />
            </div>
          </div>

          <AuthThirdPartyLogin @select="notifyThirdParty" />
        </section>

        <section class="order-2 overflow-hidden border-b border-gray-200 bg-[var(--ui-subtle)] px-3.5 py-4 dark:border-gray-700 md:order-1 md:border-b-0 md:border-r">
          <div>
            <div class="mb-3">
              <div class="ui-muted text-xs font-semibold">{{ SITE_NAME }}</div>
              <h3 class="ui-title mt-1.5 text-xl">扫码登录</h3>
            </div>

            <AuthQrLogin
              embedded
              @close="onClose"
              @notify="showToast"
              @success="onLoginSuccess"
            />

          </div>
        </section>
      </div>
    </div>
  </div>

  <div v-if="toast" class="ui-panel fixed bottom-4 left-1/2 z-50 -translate-x-1/2 px-3 py-2 text-xs" role="status" aria-live="polite">
    {{ toast }}
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import AuthLoginForm from './AuthLoginForm.vue'
import AuthQrLogin from './AuthQrLogin.vue'
import AuthRegisterForm from './AuthRegisterForm.vue'
import AuthThirdPartyLogin from './AuthThirdPartyLogin.vue'
import { startOAuthAuthorize } from '@/services/thirdPartyAuth'
import { useAuthStore } from '@/stores/auth'
import type { OAuthProvider } from '@/types'
import { SITE_NAME } from '@/config/brand'

type AuthView = 'login' | 'register' | 'qr'

const emit = defineEmits<{
  close: []
  success: []
}>()

const authView = ref<AuthView>('login')
const authStore = useAuthStore()
const dialogPanel = ref<HTMLElement | null>(null)
let previousFocus: HTMLElement | null = null
const seedPhone = ref('')
const seedEmail = ref('')
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null
let oauthRequestId = 0

const currentTitle = computed(() => {
  if (authView.value === 'register') return `注册 ${SITE_NAME}`
  if (authView.value === 'qr') return '二维码登录'
  return `登录 ${SITE_NAME}`
})

function showToast(message: string): void {
  toast.value = message
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = ''
    toastTimer = null
  }, 2000)
}

function switchAuthView(view: AuthView): void {
  authView.value = view
}

function onDialogKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    onClose()
    return
  }
  if (event.key !== 'Tab') return

  const controls = Array.from(dialogPanel.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]',
  ) || [])
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

function onRegistered(payload: { phone: string; email: string }): void {
  seedPhone.value = payload.phone
  seedEmail.value = payload.email
  authView.value = 'login'
}

function onLoginSuccess(): void {
  emit('success')
  oauthRequestId += 1
  emit('close')
}

function onClose(): void {
  oauthRequestId += 1
  authStore.invalidateAuthOperations()
  emit('close')
}

async function notifyThirdParty(provider: OAuthProvider): Promise<void> {
  const requestId = ++oauthRequestId
  const generation = authStore.invalidateAuthOperations()
  const result = await startOAuthAuthorize(
    provider,
    window.location.pathname + window.location.search + window.location.hash,
    () => requestId === oauthRequestId && authStore.isAuthGenerationCurrent(generation),
  )
  if (requestId !== oauthRequestId || !authStore.isAuthGenerationCurrent(generation)) return
  showToast(result.message)
}

onMounted(() => {
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  void nextTick(() => dialogPanel.value?.focus())
})

onBeforeUnmount(() => {
  oauthRequestId += 1
  if (previousFocus?.isConnected) previousFocus.focus()
})
</script>

<style scoped>
.auth-view-tab[aria-pressed='true'] {
  border-color: var(--ui-accent);
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
}
</style>

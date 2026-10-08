<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/30 motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none" @click="onClose"></div>
    <div class="relative z-10 w-full max-w-[700px] overflow-hidden rounded-2xl border border-sky-100 bg-white shadow-[0_18px_56px_rgba(15,23,42,0.14)] dark:border-gray-700 dark:bg-gray-900 motion-safe:transition-colors motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none">
      <div class="grid md:grid-cols-[236px_minmax(0,1fr)]">
        <section class="relative overflow-hidden border-b border-sky-100 bg-gradient-to-br from-sky-50 via-cyan-50 to-white px-3.5 py-4 dark:border-gray-700 dark:from-slate-900 dark:via-slate-900 dark:to-gray-900 md:border-b-0 md:border-r">
          <div class="absolute inset-0 opacity-70">
            <div class="absolute -left-10 top-10 h-28 w-28 rounded-full bg-sky-200/50 blur-2xl dark:bg-sky-500/10"></div>
            <div class="absolute right-0 top-24 h-36 w-36 rounded-full bg-cyan-200/40 blur-3xl dark:bg-cyan-400/10"></div>
            <div class="absolute bottom-6 left-10 h-24 w-24 rounded-full bg-blue-200/40 blur-2xl dark:bg-blue-400/10"></div>
          </div>
          <div class="relative">
            <div class="mb-3">
              <div class="text-xs font-semibold uppercase tracking-[0.25em] text-sky-500 dark:text-cyan-300">MagicAlbum</div>
              <h3 class="mt-1.5 text-[22px] font-semibold tracking-tight text-gray-900 dark:text-white">扫码登录</h3>
            </div>

            <AuthQrLogin
              embedded
              @close="onClose"
              @notify="showToast"
              @success="onLoginSuccess"
            />

          </div>
        </section>

        <section class="flex min-h-[360px] flex-col md:min-h-[392px]">
          <div class="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
            <div>
              <div class="text-xs font-medium uppercase tracking-[0.22em] text-gray-400 dark:text-gray-500">Account Center</div>
              <h3 class="mt-0.5 text-[19px] font-semibold text-gray-900 dark:text-white">{{ currentTitle }}</h3>
            </div>
            <div class="inline-flex rounded-full border border-sky-100 bg-sky-50/80 p-0.5 text-sm dark:border-gray-700 dark:bg-gray-800">
              <button
                type="button"
                class="rounded-full px-3.5 py-1.5 text-[13px]"
                :class="authView === 'login' ? 'bg-sky-500 text-white shadow-sm dark:bg-cyan-500' : 'text-gray-600 hover:bg-white dark:text-gray-300 dark:hover:bg-gray-700'"
                @click="switchAuthView('login')"
              >
                登录
              </button>
              <button
                type="button"
                class="rounded-full px-3.5 py-1.5 text-[13px]"
                :class="authView === 'register' ? 'bg-sky-500 text-white shadow-sm dark:bg-cyan-500' : 'text-gray-600 hover:bg-white dark:text-gray-300 dark:hover:bg-gray-700'"
                @click="switchAuthView('register')"
              >
                注册
              </button>
            </div>
            <button class="rounded-full p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-200" @click="onClose" aria-label="关闭">✕</button>
          </div>

          <div class="flex-1 bg-white dark:bg-gray-900">
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
      </div>
    </div>
  </div>

  <div v-if="toast" class="fixed bottom-4 left-1/2 z-50 -translate-x-1/2 rounded bg-black/80 px-3 py-2 text-xs text-white">
    {{ toast }}
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import AuthLoginForm from './AuthLoginForm.vue'
import AuthQrLogin from './AuthQrLogin.vue'
import AuthRegisterForm from './AuthRegisterForm.vue'
import AuthThirdPartyLogin from './AuthThirdPartyLogin.vue'
import { startOAuthAuthorize } from '@/services/thirdPartyAuth'
import type { OAuthProvider } from '@/types'

type AuthView = 'login' | 'register' | 'qr'

const emit = defineEmits<{
  close: []
  success: []
}>()

const authView = ref<AuthView>('login')
const seedPhone = ref('')
const seedEmail = ref('')
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null

const currentTitle = computed(() => {
  if (authView.value === 'register') return '注册 MagicAlbum'
  if (authView.value === 'qr') return '二维码登录'
  return '登录 MagicAlbum'
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

function onRegistered(payload: { phone: string; email: string }): void {
  seedPhone.value = payload.phone
  seedEmail.value = payload.email
  authView.value = 'login'
}

function onLoginSuccess(): void {
  emit('success')
  onClose()
}

function onClose(): void {
  emit('close')
}

function notifyThirdParty(provider: OAuthProvider): void {
  const result = startOAuthAuthorize(provider, window.location.pathname + window.location.search + window.location.hash)
  showToast(result.message)
}
</script>

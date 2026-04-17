<template>
  <form class="space-y-3 px-5 py-3" @submit.prevent="onSubmitLogin">
    <div class="flex items-center gap-2.5 text-[10px] uppercase tracking-[0.18em] text-gray-400 dark:text-gray-500">
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
      <span>{{ modeLabel }}</span>
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
    </div>

    <div v-if="mode === 'phone-password'">
      <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">手机号</label>
      <input
        v-model.trim="form.phone"
        type="tel"
        placeholder="请输入手机号"
        class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
      />
      <p v-if="errors.phone" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
    </div>

    <div v-else-if="mode === 'email-password' || mode === 'email-code'">
      <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">邮箱</label>
      <input
        v-model.trim="form.email"
        type="email"
        placeholder="name@example.com"
        class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
      />
      <p v-if="errors.email" class="mt-1 text-xs text-red-600">{{ errors.email }}</p>
    </div>

    <div v-else>
      <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">手机号</label>
      <input
        v-model.trim="form.phone"
        type="tel"
        placeholder="请输入手机号"
        class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
      />
      <p v-if="errors.phone" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
    </div>

    <div v-if="mode === 'phone-password' || mode === 'email-password'">
      <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">密码</label>
      <input
        v-model="form.password"
        type="password"
        autocomplete="current-password"
        placeholder="请输入密码"
        class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
      />
      <p v-if="errors.password" class="mt-1 text-xs text-red-600">{{ errors.password }}</p>
    </div>

    <div v-else class="space-y-2">
      <div class="flex items-end gap-2">
        <div class="min-w-0 flex-1">
          <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">验证码</label>
          <input
            v-model.trim="form.code"
            type="text"
            inputmode="numeric"
            :placeholder="mode === 'email-code' ? '请输入邮箱验证码' : '请输入手机验证码'"
            class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
          />
        </div>
        <button
          type="button"
          class="h-10 shrink-0 rounded-xl border border-sky-200 bg-sky-50 px-3 text-xs font-medium text-sky-600 transition hover:bg-sky-100 disabled:cursor-not-allowed disabled:opacity-60 dark:border-cyan-700 dark:bg-cyan-950/30 dark:text-cyan-300 dark:hover:bg-cyan-950/50"
          :disabled="sendingCode || codeCountdown > 0"
          @click="mode === 'email-code' ? onSendEmailCode() : onSendPhoneCode()"
        >
          {{ codeButtonLabel }}
        </button>
      </div>
      <p v-if="errors.code" class="text-xs text-red-600">{{ errors.code }}</p>
      <p v-if="codeSession.maskedAddress" class="text-[11px] text-gray-500 dark:text-gray-400">
        验证码已发送至 {{ codeSession.maskedAddress }}
      </p>
    </div>

    <div class="flex items-center justify-between text-xs text-sky-600 dark:text-cyan-400">
      <button
        v-if="mode === 'phone-password'"
        type="button"
        class="hover:underline"
        @click="switchMode('email-password')"
      >
        切换到邮箱登录
      </button>
      <button
        v-else-if="mode === 'email-password'"
        type="button"
        class="hover:underline"
        @click="switchMode('phone-password')"
      >
        切换到手机号登录
      </button>
      <button v-else type="button" class="hover:underline" @click="switchMode(mode === 'email-code' ? 'email-password' : 'phone-password')">
        返回密码登录
      </button>
      <button
        v-if="mode === 'phone-password' || mode === 'email-password'"
        type="button"
        class="hover:underline"
        @click="mode === 'email-password' ? switchMode('email-code') : switchMode('phone-code')"
      >
        验证码登录
      </button>
      <button
        v-else
        type="button"
        class="hover:underline"
        @click="mode === 'email-code' ? switchMode('phone-code') : switchMode('email-code')"
      >
        {{ mode === 'email-code' ? '手机验证码登录' : '邮箱验证码登录' }}
      </button>
    </div>

    <div class="rounded-xl bg-sky-50/70 px-3.5 py-2 text-[11px] text-gray-500 dark:bg-gray-800/80 dark:text-gray-400">
      还没有账号？
      <button type="button" class="text-brandDay-600 hover:underline dark:text-accentCyan-400" @click="emit('switch-register')">立即注册</button>
    </div>

    <AuthVerifyBlock v-if="verifyEnabled && showVerify" ref="verifyBlockRef" scene="login" />

    <div class="flex items-center justify-end pt-0.5">
      <button
        type="submit"
        class="rounded-xl bg-sky-500 px-5 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-sky-600 disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-sky-100 dark:bg-cyan-500 dark:hover:bg-cyan-600 dark:focus:ring-cyan-500/20"
        :disabled="submitting"
      >
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import AuthVerifyBlock from '@/components/auth/AuthVerifyBlock.vue'
import { isAuthVerifyEnabled, toVerifyPayload } from '@/services/authVerify'
import { startEmailCodeLogin, startPhoneCodeLogin } from '@/api/auth'
import type { ApiError } from '@/types'
import type { AuthVerifyResult } from '@/services/authVerify'

type LoginMode = 'phone-password' | 'email-password' | 'email-code' | 'phone-code'

interface LoginForm {
  phone: string
  email: string
  password: string
  code: string
}

interface LoginErrors {
  phone: string
  email: string
  password: string
  code: string
}

const props = withDefaults(defineProps<{
  initialPhone?: string
  initialEmail?: string
}>(), {
  initialPhone: '',
  initialEmail: '',
})

const emit = defineEmits<{
  close: []
  success: []
  notify: [message: string]
  'switch-register': []
}>()

const mode = ref<LoginMode>('phone-password')
const submitting = ref(false)
const sendingCode = ref(false)
const showVerify = ref(false)
const codeCountdown = ref(0)
const countdownTimer = ref<number | null>(null)
const verifyEnabled = isAuthVerifyEnabled()
const verifyBlockRef = ref<{
  ensureVerified: () => Promise<AuthVerifyResult | null>
  reset: () => void
} | null>(null)
const form = reactive<LoginForm>({
  phone: props.initialPhone,
  email: props.initialEmail,
  password: '',
  code: '',
})
const errors = reactive<LoginErrors>({ phone: '', email: '', password: '', code: '' })
const codeSession = reactive<{ session: string; maskedAddress: string }>({
  session: '',
  maskedAddress: '',
})

const modeLabel = computed(() => {
  if (mode.value === 'phone-password') return 'Phone Login'
  if (mode.value === 'email-password') return 'Email Login'
  if (mode.value === 'email-code') return 'Email Code Login'
  return 'Phone Code Login'
})

const codeButtonLabel = computed(() => {
  if (sendingCode.value) return '发送中…'
  if (codeCountdown.value > 0) return `${codeCountdown.value}s 后重试`
  return '获取验证码'
})

watch(() => props.initialPhone, (value) => {
  if (value) {
    form.phone = value
    mode.value = 'phone-password'
  }
})

watch(() => props.initialEmail, (value) => {
  if (value) {
    form.email = value
  }
})

function resetErrors(): void {
  errors.phone = ''
  errors.email = ''
  errors.password = ''
  errors.code = ''
}

function validatePhone(): boolean {
  if (!form.phone) {
    errors.phone = '请输入手机号'
    return false
  }
  if (!/^1\d{10}$/.test(form.phone)) {
    errors.phone = '手机号格式不正确'
    return false
  }
  errors.phone = ''
  return true
}

function validateEmail(): boolean {
  if (!form.email) {
    errors.email = '请输入邮箱'
    return false
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)) {
    errors.email = '邮箱格式不正确'
    return false
  }
  errors.email = ''
  return true
}

function validatePassword(): boolean {
  if (!form.password) {
    errors.password = '请输入密码'
    return false
  }
  if (form.password.length < 8) {
    errors.password = '密码不少于 8 位'
    return false
  }
  errors.password = ''
  return true
}

function validateCode(): boolean {
  if (!form.code) {
    errors.code = mode.value === 'email-code' ? '请输入邮箱验证码' : '请输入手机验证码'
    return false
  }
  if (!/^\d{4,8}$/.test(form.code)) {
    errors.code = '验证码格式不正确'
    return false
  }
  errors.code = ''
  return true
}

function getLoginErrorMessage(error: unknown): string {
  const e = error as ApiError | null | undefined
  return (
    e?.response?.data?.message ||
    String((e?.response?.data as { detail?: string } | undefined)?.detail || '') ||
    e?.message ||
    '登录失败，请稍后再试'
  )
}

async function onSubmitLogin(): Promise<void> {
  resetErrors()
  const okUser = mode.value === 'phone-password' || mode.value === 'phone-code' ? validatePhone() : validateEmail()
  const okPwd = mode.value === 'email-code' || mode.value === 'phone-code' ? true : validatePassword()
  const okCode = mode.value === 'email-code' || mode.value === 'phone-code' ? validateCode() : true
  if (!okUser || !okPwd || !okCode) {
    emit('notify', '请完成必填项并修正错误')
    return
  }

  let verifyResult: AuthVerifyResult | null | undefined
  if (verifyEnabled) {
    if (!showVerify.value) {
      showVerify.value = true
      await nextTick()
    }

    verifyResult = await verifyBlockRef.value?.ensureVerified()
    if (!verifyResult) {
      emit('notify', '请先完成人工验证')
      return
    }
  }

  submitting.value = true
  try {
    const authStore = useAuthStore()
    if (mode.value === 'email-code') {
      if (!codeSession.session) {
        emit('notify', '请先获取邮箱验证码')
        return
      }
      await authStore.loginWithEmailCode({
        channel: 'email',
        address: form.email,
        code: form.code,
        session: codeSession.session,
      })
    } else if (mode.value === 'phone-code') {
      if (!codeSession.session) {
        emit('notify', '请先获取手机验证码')
        return
      }
      await authStore.loginWithPhoneCode({
        channel: 'phone',
        address: form.phone,
        code: form.code,
        session: codeSession.session,
      })
    } else {
      const verifyPayload = toVerifyPayload(verifyResult)
      if (mode.value === 'phone-password') {
        await authStore.loginWithPhonePassword({ phone: form.phone, password: form.password, ...verifyPayload })
      } else {
        await authStore.loginWithEmailPassword({ email: form.email, password: form.password, ...verifyPayload })
      }
    }
    emit('notify', '登录成功')
    emit('success')
    verifyBlockRef.value?.reset()
    showVerify.value = false
  } catch (error: unknown) {
    emit('notify', getLoginErrorMessage(error))
  } finally {
    submitting.value = false
  }
}

async function onSendEmailCode(): Promise<void> {
  resetErrors()
  if (!validateEmail()) {
    emit('notify', '请先输入正确的邮箱')
    return
  }

  let verifyResult: AuthVerifyResult | null | undefined
  if (verifyEnabled) {
    if (!showVerify.value) {
      showVerify.value = true
      await nextTick()
    }
    verifyResult = await verifyBlockRef.value?.ensureVerified()
    if (!verifyResult) {
      emit('notify', '请先完成人工验证')
      return
    }
  }

  sendingCode.value = true
  try {
    const verifyPayload = toVerifyPayload(verifyResult)
    const response = await startEmailCodeLogin({
      channel: 'email',
      address: form.email,
      ...verifyPayload,
    })
    codeSession.session = response.session
    codeSession.maskedAddress = response.maskedAddress
    form.code = ''
    startCountdown(response.cooldownSeconds || 60)
    emit('notify', '验证码已生成，开发环境请查看后端日志中的邮箱验证码')
  } catch (error: unknown) {
    emit('notify', getLoginErrorMessage(error) || '验证码发送失败')
  } finally {
    sendingCode.value = false
  }
}

async function onSendPhoneCode(): Promise<void> {
  resetErrors()
  if (!validatePhone()) {
    emit('notify', '请先输入正确的手机号')
    return
  }

  let verifyResult: AuthVerifyResult | null | undefined
  if (verifyEnabled) {
    if (!showVerify.value) {
      showVerify.value = true
      await nextTick()
    }
    verifyResult = await verifyBlockRef.value?.ensureVerified()
    if (!verifyResult) {
      emit('notify', '请先完成人工验证')
      return
    }
  }

  sendingCode.value = true
  try {
    const verifyPayload = toVerifyPayload(verifyResult)
    const response = await startPhoneCodeLogin({
      channel: 'phone',
      address: form.phone,
      ...verifyPayload,
    })
    codeSession.session = response.session
    codeSession.maskedAddress = response.maskedAddress
    form.code = ''
    startCountdown(response.cooldownSeconds || 60)
    emit('notify', '验证码已发送，请查收短信')
  } catch (error: unknown) {
    emit('notify', getLoginErrorMessage(error) || '验证码发送失败')
  } finally {
    sendingCode.value = false
  }
}

function switchMode(nextMode: LoginMode): void {
  mode.value = nextMode
  resetErrors()
  codeSession.session = ''
  codeSession.maskedAddress = ''
  form.code = ''
  clearCountdown()
}

function startCountdown(seconds: number): void {
  clearCountdown()
  codeCountdown.value = seconds
  countdownTimer.value = window.setInterval(() => {
    if (codeCountdown.value <= 1) {
      clearCountdown()
      codeCountdown.value = 0
      return
    }
    codeCountdown.value -= 1
  }, 1000)
}

function clearCountdown(): void {
  if (countdownTimer.value !== null) {
    window.clearInterval(countdownTimer.value)
    countdownTimer.value = null
  }
}

onBeforeUnmount(() => {
  clearCountdown()
})
</script>

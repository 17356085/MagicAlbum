<template>
  <form class="space-y-3 px-5 py-3" @submit.prevent="onSubmitRegister">
    <div class="flex items-center gap-2.5 text-[10px] uppercase tracking-[0.18em] text-gray-400 dark:text-gray-500">
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
      <span>Register</span>
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
    </div>

    <div>
      <label class="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-200">用户名</label>
      <input
        v-model.trim="form.username"
        @blur="validateUsername"
        type="text"
        placeholder="请输入用户名"
        class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
      />
      <p v-if="errors.username" class="mt-1 text-xs text-red-600">{{ errors.username }}</p>
    </div>

    <div class="grid gap-3 md:grid-cols-2">
      <div>
        <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">手机号</label>
        <input
          v-model.trim="form.phone"
          type="tel"
          placeholder="请输入手机号"
          class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
        />
        <p v-if="errors.phone" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
      </div>

      <div>
        <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">邮箱</label>
        <input
          v-model.trim="form.email"
          type="email"
          placeholder="name@example.com"
          class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
        />
        <p v-if="errors.email" class="mt-1 text-xs text-red-600">{{ errors.email }}</p>
      </div>

      <div>
        <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">密码</label>
        <input
          v-model="form.password"
          type="password"
          autocomplete="new-password"
          placeholder="不少于 8 位"
          class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
        />
        <p v-if="errors.password" class="mt-1 text-xs text-red-600">{{ errors.password }}</p>
      </div>

      <div>
        <label class="mb-1 block text-[13px] font-medium text-gray-700 dark:text-gray-200">确认密码</label>
        <input
          v-model="form.confirmPassword"
          type="password"
          autocomplete="new-password"
          placeholder="再次输入密码"
          class="h-10 w-full rounded-xl border border-gray-200 bg-gray-50 px-3.5 text-sm shadow-sm outline-none transition focus:border-sky-400 focus:bg-white focus:ring-2 focus:ring-sky-100 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-cyan-400 dark:focus:bg-gray-900 dark:focus:ring-cyan-500/20"
        />
        <p v-if="errors.confirmPassword" class="mt-1 text-xs text-red-600">{{ errors.confirmPassword }}</p>
      </div>
    </div>

    <div class="rounded-xl bg-sky-50/70 px-3.5 py-2 text-[11px] text-gray-500 dark:bg-gray-800/80 dark:text-gray-400">
      已有账号？
      <button type="button" class="text-brandDay-600 hover:underline dark:text-accentCyan-400" @click="emit('switch-login')">返回登录</button>
    </div>

    <AuthVerifyBlock v-if="verifyEnabled && showVerify" ref="verifyBlockRef" scene="register" />

    <div class="flex items-center justify-end pt-0.5">
      <button
        type="submit"
        class="rounded-xl bg-sky-500 px-5 py-2 text-sm font-medium text-white shadow-sm transition hover:bg-sky-600 disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-sky-100 dark:bg-cyan-500 dark:hover:bg-cyan-600 dark:focus:ring-cyan-500/20"
        :disabled="submitting"
      >
        {{ submitting ? '提交中…' : '注册' }}
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { nextTick, reactive, ref } from 'vue'
import AuthVerifyBlock from '@/components/auth/AuthVerifyBlock.vue'
import { checkUsernameAvailable, registerUser } from '@/api/users'
import { isAuthVerifyEnabled, toVerifyPayload } from '@/services/authVerify'
import type { AuthVerifyResult } from '@/services/authVerify'
import { getPasswordError, isStrongPassword, isValidEmail, isValidPhone, isValidUsername } from '@/utils/validators'
import type { ApiError } from '@/types'

interface RegisterForm {
  username: string
  phone: string
  email: string
  password: string
  confirmPassword: string
}

interface RegisterErrors {
  username: string
  phone: string
  email: string
  password: string
  confirmPassword: string
}

const emit = defineEmits<{
  close: []
  notify: [message: string]
  'switch-login': []
  registered: [payload: { phone: string; email: string }]
}>()

const submitting = ref(false)
const showVerify = ref(false)
const verifyEnabled = isAuthVerifyEnabled()
const verifyBlockRef = ref<{
  ensureVerified: () => Promise<AuthVerifyResult | null>
  reset: () => void
} | null>(null)
const form = reactive<RegisterForm>({
  username: '',
  phone: '',
  email: '',
  password: '',
  confirmPassword: '',
})
const errors = reactive<RegisterErrors>({
  username: '',
  phone: '',
  email: '',
  password: '',
  confirmPassword: '',
})

function resetErrors(): void {
  errors.username = ''
  errors.phone = ''
  errors.email = ''
  errors.password = ''
  errors.confirmPassword = ''
}

async function validateUsername(): Promise<boolean> {
  if (!form.username) {
    errors.username = '请输入用户名'
    return false
  }
  if (!isValidUsername(form.username)) {
    errors.username = '3-20 位字母、数字或下划线'
    return false
  }
  try {
    const available = await checkUsernameAvailable(form.username)
    if (!available) {
      errors.username = '用户名不可重复'
      return false
    }
    errors.username = ''
    return true
  } catch {
    errors.username = '无法验证用户名，请稍后再试'
    return false
  }
}

function validatePhone(): boolean {
  if (!form.phone) {
    errors.phone = '请输入手机号'
    return false
  }
  if (!isValidPhone(form.phone)) {
    errors.phone = '手机号格式不正确，需 1 开头 11 位'
    return false
  }
  errors.phone = ''
  return true
}

function validateEmail(): boolean {
  if (!form.email) {
    errors.email = '请输入电子邮箱'
    return false
  }
  if (!isValidEmail(form.email)) {
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
  if (!isStrongPassword(form.password)) {
    errors.password = getPasswordError(form.password) || '至少 8 位且包含大小写字母与数字'
    return false
  }
  errors.password = ''
  return true
}

function validateConfirmPassword(): boolean {
  if (!form.confirmPassword) {
    errors.confirmPassword = '请再次输入密码'
    return false
  }
  if (form.confirmPassword !== form.password) {
    errors.confirmPassword = '两次输入的密码不一致'
    return false
  }
  errors.confirmPassword = ''
  return true
}

function getRegisterErrorMessage(error: unknown): string {
  const e = error as ApiError | null | undefined
  return e?.response?.data?.message || e?.message || ''
}

async function onSubmitRegister(): Promise<void> {
  resetErrors()
  const okUsername = await validateUsername()
  const okPhone = validatePhone()
  const okEmail = validateEmail()
  const okPwd = validatePassword()
  const okConfirm = validateConfirmPassword()
  if (!okUsername || !okPhone || !okEmail || !okPwd || !okConfirm) {
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
    const verifyPayload = toVerifyPayload(verifyResult)
    await registerUser({
      username: form.username,
      phone: form.phone,
      email: form.email,
      password: form.password,
      ...verifyPayload,
    })
    emit('notify', '注册成功，请登录')
    verifyBlockRef.value?.reset()
    showVerify.value = false
    emit('registered', { phone: form.phone, email: form.email })
  } catch (error: unknown) {
    const rawMsg = getRegisterErrorMessage(error)
    let toastMsg = '注册失败，请稍后再试'
    if (rawMsg && /用户名/.test(rawMsg) && /(不可重复|已被使用)/.test(rawMsg)) {
      errors.username = '该用户名已被注册'
      toastMsg = '注册失败，该用户名已被注册'
    } else if (rawMsg && /邮箱/.test(rawMsg) && /(已被使用|不可重复)/.test(rawMsg)) {
      errors.email = '该邮箱已被注册'
      toastMsg = '注册失败，该邮箱已被注册'
    } else if (rawMsg && /手机号/.test(rawMsg) && /(已被使用|不可重复)/.test(rawMsg)) {
      errors.phone = '该手机号已被注册'
      toastMsg = '注册失败，该手机号已被注册'
    } else if (rawMsg && /手机号/.test(rawMsg) && /格式不正确/.test(rawMsg)) {
      errors.phone = '手机号格式不正确，需 1 开头 11 位'
      toastMsg = '注册失败：手机号格式不正确'
    } else if (rawMsg) {
      toastMsg = '注册失败：' + rawMsg
    }
    emit('notify', toastMsg)
  } finally {
    submitting.value = false
  }
}
</script>

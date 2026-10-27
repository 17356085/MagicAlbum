<template>
  <form class="space-y-4 p-4 sm:p-5" @submit.prevent="onSubmitRegister">
    <div class="ui-muted flex items-center gap-2.5 text-[10px] uppercase tracking-[0.18em]">
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
      <span>Register</span>
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
    </div>

    <div>
      <label for="auth-register-username" class="mb-1 block text-sm font-medium">用户名</label>
      <input
        v-model.trim="form.username"
        id="auth-register-username"
        @blur="validateUsername"
        type="text"
        maxlength="64"
        :disabled="submitting"
        :aria-invalid="Boolean(errors.username)"
        :aria-describedby="errors.username ? 'auth-register-username-error' : undefined"
        placeholder="请输入用户名"
        class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
      />
      <p v-if="errors.username" id="auth-register-username-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.username }}</p>
    </div>

    <div class="grid gap-3 md:grid-cols-2">
      <div>
        <label for="auth-register-phone" class="mb-1 block text-sm font-medium">手机号</label>
        <input
          v-model.trim="form.phone"
          id="auth-register-phone"
          type="tel"
          :disabled="submitting"
          :aria-invalid="Boolean(errors.phone)"
          :aria-describedby="errors.phone ? 'auth-register-phone-error' : undefined"
          placeholder="请输入手机号"
          class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
        />
        <p v-if="errors.phone" id="auth-register-phone-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
      </div>

      <div>
        <label for="auth-register-email" class="mb-1 block text-sm font-medium">邮箱</label>
        <input
          v-model.trim="form.email"
          id="auth-register-email"
          type="email"
          :disabled="submitting"
          :aria-invalid="Boolean(errors.email)"
          :aria-describedby="errors.email ? 'auth-register-email-error' : undefined"
          placeholder="name@example.com"
          class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
        />
        <p v-if="errors.email" id="auth-register-email-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.email }}</p>
      </div>

      <div>
        <label for="auth-register-password" class="mb-1 block text-sm font-medium">密码</label>
        <input
          v-model="form.password"
          id="auth-register-password"
          type="password"
          autocomplete="new-password"
          :disabled="submitting"
          :aria-invalid="Boolean(errors.password)"
          :aria-describedby="errors.password ? 'auth-register-password-error' : undefined"
          placeholder="不少于 8 位"
          class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
        />
        <p v-if="errors.password" id="auth-register-password-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.password }}</p>
      </div>

      <div>
        <label for="auth-register-confirm-password" class="mb-1 block text-sm font-medium">确认密码</label>
        <input
          v-model="form.confirmPassword"
          id="auth-register-confirm-password"
          type="password"
          autocomplete="new-password"
          :disabled="submitting"
          :aria-invalid="Boolean(errors.confirmPassword)"
          :aria-describedby="errors.confirmPassword ? 'auth-register-confirm-password-error' : undefined"
          placeholder="再次输入密码"
          class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
        />
        <p v-if="errors.confirmPassword" id="auth-register-confirm-password-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.confirmPassword }}</p>
      </div>
    </div>

    <div class="border border-gray-200 bg-[var(--ui-subtle)] px-3.5 py-2 text-xs ui-muted dark:border-gray-700">
      已有账号？
      <button type="button" class="text-[var(--ui-accent)] hover:underline" :disabled="submitting" @click="emit('switch-login')">返回登录</button>
    </div>

    <AuthVerifyBlock v-if="verifyEnabled && showVerify" ref="verifyBlockRef" scene="register" />

    <div class="flex items-center justify-end pt-0.5">
      <button
        type="submit"
        class="ui-button-primary disabled:opacity-60"
        :disabled="submitting"
      >
        {{ submitting ? '提交中…' : '注册' }}
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, reactive, ref } from 'vue'
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

type RegisterSnapshot = Readonly<RegisterForm>

type SnapshotValidationResult = 'valid' | 'invalid' | 'inactive'

type VerifyBlockHandle = {
  ensureVerified: () => Promise<AuthVerifyResult | null>
  reset: () => void
  resetIfMatches: (expected: AuthVerifyResult) => boolean
}

type RegisterSubmission = Readonly<{
  verifyBlock: VerifyBlockHandle | null
  verifyResult: AuthVerifyResult | null
}>

const emit = defineEmits<{
  close: []
  notify: [message: string]
  'switch-login': []
  registered: [payload: { phone: string; email: string }]
}>()

const submitting = ref(false)
const showVerify = ref(false)
const verifyEnabled = isAuthVerifyEnabled()
const verifyBlockRef = ref<VerifyBlockHandle | null>(null)
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

let isMounted = true
let submitSequence = 0
let activeSubmitId: number | null = null
let usernameValidationSequence = 0
const usernameAvailabilityInFlight = new Map<string, Promise<boolean>>()

function isSubmitActive(submitId: number): boolean {
  return isMounted && activeSubmitId === submitId
}

function captureSnapshot(): RegisterSnapshot {
  return Object.freeze({ ...form })
}

function checkUsernameAvailability(username: string): Promise<boolean> {
  const existing = usernameAvailabilityInFlight.get(username)
  if (existing) return existing

  let request!: Promise<boolean>
  request = checkUsernameAvailable(username).finally(() => {
    if (usernameAvailabilityInFlight.get(username) === request) {
      usernameAvailabilityInFlight.delete(username)
    }
  })
  usernameAvailabilityInFlight.set(username, request)
  return request
}

function resetErrors(): void {
  errors.username = ''
  errors.phone = ''
  errors.email = ''
  errors.password = ''
  errors.confirmPassword = ''
}

function usernameValidationError(username: string): string {
  if (!username) return '请输入用户名'
  if (!isValidUsername(username)) return '3-64 位字母、数字或下划线'
  return ''
}

function phoneValidationError(phone: string): string {
  if (!phone) return '请输入手机号'
  if (!isValidPhone(phone)) return '手机号格式不正确，需 1 开头 11 位'
  return ''
}

function emailValidationError(email: string): string {
  if (!email) return '请输入电子邮箱'
  if (!isValidEmail(email)) return '邮箱格式不正确'
  return ''
}

function passwordValidationError(password: string): string {
  if (!password) return '请输入密码'
  if (!isStrongPassword(password)) return getPasswordError(password) || '至少 8 位且包含大小写字母与数字'
  return ''
}

function confirmPasswordValidationError(password: string, confirmPassword: string): string {
  if (!confirmPassword) return '请再次输入密码'
  if (confirmPassword !== password) return '两次输入的密码不一致'
  return ''
}

async function validateUsername(): Promise<boolean> {
  if (!isMounted || activeSubmitId !== null) return false

  const username = form.username
  const validationId = ++usernameValidationSequence
  const localError = usernameValidationError(username)
  if (localError) {
    if (isMounted && validationId === usernameValidationSequence && form.username === username) {
      errors.username = localError
    }
    return false
  }

  try {
    const available = await checkUsernameAvailability(username)
    if (!isMounted || validationId !== usernameValidationSequence || form.username !== username) return available
    errors.username = available ? '' : '用户名不可重复'
    return available
  } catch {
    if (isMounted && validationId === usernameValidationSequence && form.username === username) {
      errors.username = '无法验证用户名，请稍后再试'
    }
    return false
  }
}

async function validateSnapshot(snapshot: RegisterSnapshot, submitId: number): Promise<SnapshotValidationResult> {
  // Invalidate older blur results before taking ownership of the form errors.
  usernameValidationSequence += 1
  errors.username = usernameValidationError(snapshot.username)
  errors.phone = phoneValidationError(snapshot.phone)
  errors.email = emailValidationError(snapshot.email)
  errors.password = passwordValidationError(snapshot.password)
  errors.confirmPassword = confirmPasswordValidationError(snapshot.password, snapshot.confirmPassword)

  if (Object.values(errors).some(Boolean)) return 'invalid'

  try {
    const available = await checkUsernameAvailability(snapshot.username)
    if (!isSubmitActive(submitId)) return 'inactive'
    errors.username = available ? '' : '用户名不可重复'
    return available ? 'valid' : 'invalid'
  } catch {
    if (!isSubmitActive(submitId)) return 'inactive'
    errors.username = '无法验证用户名，请稍后再试'
    return 'invalid'
  }
}

function getRegisterErrorMessage(error: unknown): string {
  const e = error as ApiError | null | undefined
  return e?.response?.data?.message || e?.message || ''
}

function invalidateConsumedVerification(submitId: number, submission: RegisterSubmission): void {
  if (!isSubmitActive(submitId)) return
  if (!submission.verifyBlock || !submission.verifyResult) return
  if (verifyBlockRef.value !== submission.verifyBlock) return
  submission.verifyBlock.resetIfMatches(submission.verifyResult)
}

function onSubmitRegister(): void {
  if (!isMounted || activeSubmitId !== null) return

  const submitId = ++submitSequence
  activeSubmitId = submitId
  submitting.value = true
  void runSubmit(submitId, captureSnapshot())
}

async function runSubmit(submitId: number, snapshot: RegisterSnapshot): Promise<void> {
  try {
    resetErrors()
    const validation = await validateSnapshot(snapshot, submitId)
    if (!isSubmitActive(submitId)) return
    if (validation === 'inactive') return
    if (validation === 'invalid') {
      emit('notify', '请完成必填项并修正错误')
      return
    }

    let verifyBlock: VerifyBlockHandle | null = null
    let verifyResult: AuthVerifyResult | null | undefined
    if (verifyEnabled) {
      if (!showVerify.value) {
        showVerify.value = true
        await nextTick()
        if (!isSubmitActive(submitId)) return
      }

      verifyBlock = verifyBlockRef.value
      if (!verifyBlock) {
        if (isSubmitActive(submitId)) emit('notify', '请先完成人工验证')
        return
      }
      try {
        verifyResult = await verifyBlock.ensureVerified()
      } catch {
        verifyResult = null
      }
      if (!isSubmitActive(submitId)) return
      if (!verifyResult) {
        emit('notify', '请先完成人工验证')
        return
      }
    }

    if (!isSubmitActive(submitId)) return
    const submission: RegisterSubmission = Object.freeze({
      verifyBlock,
      verifyResult: verifyResult || null,
    })
    let registerRequestIssued = false
    try {
      const verifyPayload = toVerifyPayload(submission.verifyResult)
      registerRequestIssued = true
      await registerUser({
        username: snapshot.username,
        phone: snapshot.phone,
        email: snapshot.email,
        password: snapshot.password,
        ...verifyPayload,
      })
      if (!isSubmitActive(submitId)) return
      invalidateConsumedVerification(submitId, submission)
      if (!isSubmitActive(submitId)) return
      emit('notify', '注册成功，请登录')
      if (!isSubmitActive(submitId)) return
      showVerify.value = false
      if (!isSubmitActive(submitId)) return
      emit('registered', { phone: snapshot.phone, email: snapshot.email })
    } catch (error: unknown) {
      if (!registerRequestIssued || !isSubmitActive(submitId)) return
      invalidateConsumedVerification(submitId, submission)
      if (!isSubmitActive(submitId)) return
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
      if (isSubmitActive(submitId)) emit('notify', toastMsg)
    }
  } finally {
    if (activeSubmitId === submitId) {
      activeSubmitId = null
      if (isMounted) submitting.value = false
    }
  }
}

onBeforeUnmount(() => {
  isMounted = false
  activeSubmitId = null
  usernameValidationSequence += 1
})
</script>

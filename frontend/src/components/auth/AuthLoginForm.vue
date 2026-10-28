<template>
  <form class="space-y-4 p-4 sm:p-5" :aria-busy="submitting || sendingCode" @submit.prevent="onSubmitLogin">
    <div class="ui-muted flex items-center gap-2.5 text-[10px] uppercase tracking-[0.18em]">
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
      <span>{{ modeLabel }}</span>
      <span class="h-px flex-1 bg-gray-200 dark:bg-gray-700"></span>
    </div>

    <div v-if="mode === 'phone-password'">
      <label for="auth-login-phone" class="mb-1 block text-sm font-medium">手机号</label>
      <input
        v-model.trim="form.phone"
        id="auth-login-phone"
        type="tel"
        placeholder="请输入手机号"
        :aria-invalid="Boolean(errors.phone)"
        :aria-describedby="errors.phone ? 'auth-login-phone-error' : undefined"
        class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
      />
      <p v-if="errors.phone" id="auth-login-phone-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
    </div>

    <div v-else-if="mode === 'email-password' || mode === 'email-code'">
      <label for="auth-login-email" class="mb-1 block text-sm font-medium">邮箱</label>
      <input
        v-model.trim="form.email"
        id="auth-login-email"
        type="email"
        placeholder="name@example.com"
        :aria-invalid="Boolean(errors.email)"
        :aria-describedby="errors.email ? 'auth-login-email-error' : undefined"
        class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
      />
      <p v-if="errors.email" id="auth-login-email-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.email }}</p>
    </div>

    <div v-else>
      <label for="auth-login-phone-code" class="mb-1 block text-sm font-medium">手机号</label>
      <input
        v-model.trim="form.phone"
        id="auth-login-phone-code"
        type="tel"
        placeholder="请输入手机号"
        :aria-invalid="Boolean(errors.phone)"
        :aria-describedby="errors.phone ? 'auth-login-phone-error' : undefined"
        class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
      />
      <p v-if="errors.phone" id="auth-login-phone-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
    </div>

    <div v-if="mode === 'phone-password' || mode === 'email-password'">
      <label for="auth-login-password" class="mb-1 block text-sm font-medium">密码</label>
      <input
        v-model="form.password"
        id="auth-login-password"
        type="password"
        autocomplete="current-password"
        placeholder="请输入密码"
        :aria-invalid="Boolean(errors.password)"
        :aria-describedby="errors.password ? 'auth-login-password-error' : undefined"
        class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
      />
      <p v-if="errors.password" id="auth-login-password-error" role="alert" class="mt-1 text-xs text-red-600">{{ errors.password }}</p>
    </div>

    <div v-else class="space-y-2">
      <div class="flex items-end gap-2">
        <div class="min-w-0 flex-1">
          <label for="auth-login-code" class="mb-1 block text-sm font-medium">验证码</label>
          <input
            v-model.trim="form.code"
            id="auth-login-code"
            type="text"
            inputmode="numeric"
            :placeholder="mode === 'email-code' ? '请输入邮箱验证码' : '请输入手机验证码'"
            :aria-invalid="Boolean(errors.code)"
            :aria-describedby="errors.code ? 'auth-login-code-error' : undefined"
            class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]"
          />
        </div>
        <button
          type="button"
          class="ui-button h-auto shrink-0 px-3 py-2 text-xs disabled:cursor-not-allowed"
          :disabled="sendingCode || submitting || codeCountdown > 0"
          @click="mode === 'email-code' ? onSendEmailCode() : onSendPhoneCode()"
        >
          {{ codeButtonLabel }}
        </button>
      </div>
      <p v-if="errors.code" id="auth-login-code-error" role="alert" class="text-xs text-red-600">{{ errors.code }}</p>
      <p v-if="codeSession.maskedAddress" class="text-[11px] text-gray-500 dark:text-gray-400">
        验证码已发送至 {{ codeSession.maskedAddress }}
      </p>
    </div>

    <div class="flex flex-wrap items-center justify-between gap-2 text-xs text-[var(--ui-accent)]">
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

    <div class="border border-gray-200 bg-[var(--ui-subtle)] px-3.5 py-2 text-xs ui-muted dark:border-gray-700">
      还没有账号？
      <button type="button" class="text-[var(--ui-accent)] hover:underline" @click="emit('switch-register')">立即注册</button>
    </div>

    <AuthVerifyBlock v-if="verifyEnabled && showVerify" ref="verifyBlockRef" scene="login" />

    <div class="flex items-center justify-end pt-0.5">
      <button
        type="submit"
        class="ui-button-primary disabled:opacity-60"
        :disabled="submitting || sendingCode"
      >
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </div>
  </form>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { isAuthOperationSuperseded, useAuthStore } from '@/stores/auth'
import AuthVerifyBlock from '@/components/auth/AuthVerifyBlock.vue'
import { isAuthVerifyEnabled, toVerifyPayload } from '@/services/authVerify'
import { startEmailCodeLogin, startPhoneCodeLogin } from '@/api/auth'
import type { ApiError } from '@/types'
import type { AuthVerifyResult } from '@/services/authVerify'
import { getStoredAccessToken } from '@/utils/authStorage'

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

type OperationKind = 'password' | 'send-code' | 'finish-code'

type LoginSnapshot = Readonly<{
  kind: OperationKind
  mode: LoginMode
  channel: 'email' | 'phone'
  address: string
  password?: string
  code?: string
  session?: string
  sessionBoundary?: AuthBoundary
}>

type LoginOperation = Readonly<{
  id: number
  snapshot: LoginSnapshot
}>

type VerifyBlockHandle = {
  ensureVerified: () => Promise<AuthVerifyResult | null>
  reset: () => void
  resetIfMatches: (expected: AuthVerifyResult) => boolean
}

type VerificationSubmission = Readonly<{
  block: VerifyBlockHandle | null
  result: AuthVerifyResult | null
}>

type AuthBoundary = Readonly<{
  generation: number
  memoryToken: string
  persistedToken: string
}>

type CodeSessionSnapshot = Readonly<{
  channel: 'email' | 'phone' | ''
  address: string
  session: string
  maskedAddress: string
  authBoundary: AuthBoundary | null
}>

const CODE_FINISH_UNKNOWN_RESULT_MESSAGE = '验证码验证结果未知，请重新获取验证码'
const CODE_SEND_UNKNOWN_RESULT_MESSAGE = '验证码发送结果未知，请重新获取验证码'
const AUTH_VERIFY_EXPIRED_MESSAGE = '验证已过期，请重新完成验证'
const OTP_PROVIDER_NOT_CALLED_CODE = 'OTP_PROVIDER_NOT_CALLED'

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
const verifyBlockRef = ref<VerifyBlockHandle | null>(null)
const form = reactive<LoginForm>({
  phone: props.initialPhone,
  email: props.initialEmail,
  password: '',
  code: '',
})
const errors = reactive<LoginErrors>({ phone: '', email: '', password: '', code: '' })
const codeSession = reactive<{
  channel: 'email' | 'phone' | ''
  address: string
  session: string
  maskedAddress: string
  authBoundary: AuthBoundary | null
}>({
  channel: '',
  address: '',
  session: '',
  maskedAddress: '',
  authBoundary: null,
})
let authStoreInstance: ReturnType<typeof useAuthStore> | null = null
let isMounted = true
let operationSequence = 0
let activeOperation: LoginOperation | null = null
let ownIssuedAuth: {
  operationId: number
  store: ReturnType<typeof useAuthStore>
  generation: number
} | null = null

function getAuthStore(): ReturnType<typeof useAuthStore> {
  return authStoreInstance || (authStoreInstance = useAuthStore())
}

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
  cancelForContextChange()
  form.phone = value
  if (value) mode.value = 'phone-password'
}, { flush: 'sync' })

watch(() => props.initialEmail, (value) => {
  cancelForContextChange()
  form.email = value
}, { flush: 'sync' })

watch(() => form.phone, (value, previous) => {
  if (value !== previous) cancelForContextChange()
}, { flush: 'sync' })

watch(() => form.email, (value, previous) => {
  if (value !== previous) cancelForContextChange()
}, { flush: 'sync' })

function resetErrors(): void {
  errors.phone = ''
  errors.email = ''
  errors.password = ''
  errors.code = ''
}

function validatePhone(phone = form.phone): boolean {
  const normalizedPhone = phone.trim()
  if (!normalizedPhone) {
    errors.phone = '请输入手机号'
    return false
  }
  if (!/^1\d{10}$/.test(normalizedPhone)) {
    errors.phone = '手机号格式不正确'
    return false
  }
  errors.phone = ''
  return true
}

function validateEmail(email = form.email): boolean {
  const normalizedEmail = email.trim().toLowerCase()
  if (!normalizedEmail) {
    errors.email = '请输入邮箱'
    return false
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail)) {
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

function validateCode(code = form.code): boolean {
  const normalizedCode = code.trim()
  if (!normalizedCode) {
    errors.code = mode.value === 'email-code' ? '请输入邮箱验证码' : '请输入手机验证码'
    return false
  }
  if (!/^\d{4,8}$/.test(normalizedCode)) {
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
    ''
  )
}

function getApiErrorStatus(error: unknown): number | undefined {
  const status = (error as ApiError | null | undefined)?.response?.status
  return typeof status === 'number' ? status : undefined
}

function isOtpProviderNotCalledFailure(error: unknown): boolean {
  const apiError = error as ApiError | null | undefined
  return getApiErrorStatus(error) === 503
    && apiError?.response?.data?.code === OTP_PROVIDER_NOT_CALLED_CODE
}

function isUnknownCodeSendFailure(error: unknown): boolean {
  const status = getApiErrorStatus(error)
  return !isOtpProviderNotCalledFailure(error)
    && (status === undefined || (status >= 500 && status <= 599))
}

function isRetryableCodeFinishFailure(error: unknown): boolean {
  const status = getApiErrorStatus(error)
  return isOtpProviderNotCalledFailure(error) || status === 401 || status === 429
}

function getCodeFinishFailureMessage(error: unknown): string {
  const status = getApiErrorStatus(error)
  if (isOtpProviderNotCalledFailure(error)) {
    return getLoginErrorMessage(error) || '登录失败，请稍后再试'
  }
  if (status === 401 || status === 429) {
    return getLoginErrorMessage(error) || '登录失败，请稍后再试'
  }
  if (status === 409) {
    return getLoginErrorMessage(error) || CODE_FINISH_UNKNOWN_RESULT_MESSAGE
  }
  return CODE_FINISH_UNKNOWN_RESULT_MESSAGE
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

function normalizePhone(phone: string): string {
  return phone.trim()
}

function normalizeAddress(channel: 'email' | 'phone', value: string): string {
  return channel === 'email' ? normalizeEmail(value) : normalizePhone(value)
}

function addressForMode(nextMode: LoginMode): { channel: 'email' | 'phone'; address: string } {
  const channel = nextMode === 'email-password' || nextMode === 'email-code' ? 'email' : 'phone'
  return { channel, address: normalizeAddress(channel, channel === 'email' ? form.email : form.phone) }
}

function captureAuthBoundary(store: ReturnType<typeof useAuthStore>): AuthBoundary {
  return {
    generation: store.getAuthGeneration(),
    memoryToken: store.token,
    persistedToken: getStoredAccessToken(),
  }
}

function isAuthBoundaryCurrent(store: ReturnType<typeof useAuthStore>, boundary: AuthBoundary): boolean {
  if (!store.isAuthGenerationCurrent(boundary.generation)) return false
  if (store.token !== boundary.memoryToken) return false
  return getStoredAccessToken() === boundary.persistedToken
}

function authBoundariesEqual(left: AuthBoundary | null | undefined, right: AuthBoundary | null | undefined): boolean {
  return !!left
    && !!right
    && left.generation === right.generation
    && left.memoryToken === right.memoryToken
    && left.persistedToken === right.persistedToken
}

function beginOperation(snapshot: LoginSnapshot): LoginOperation | null {
  if (!isMounted || activeOperation !== null) return null
  const operation = Object.freeze({
    id: ++operationSequence,
    snapshot,
  })
  activeOperation = operation
  if (snapshot.kind === 'send-code') sendingCode.value = true
  else submitting.value = true
  return operation
}

function isOperationActive(operation: LoginOperation): boolean {
  return isMounted && activeOperation?.id === operation.id
}

function isOperationContextCurrent(operation: LoginOperation): boolean {
  if (!isOperationActive(operation)) return false
  const current = addressForMode(operation.snapshot.mode)
  return mode.value === operation.snapshot.mode
    && current.channel === operation.snapshot.channel
    && current.address === operation.snapshot.address
}

function releaseOperation(operation: LoginOperation): void {
  if (activeOperation?.id !== operation.id) return
  activeOperation = null
  submitting.value = false
  sendingCode.value = false
}

function invalidateOwnIssuedAuth(operation: LoginOperation | null): void {
  const issued = ownIssuedAuth
  if (!issued || (operation && issued.operationId !== operation.id)) return
  if (issued.store.isAuthGenerationCurrent(issued.generation)) {
    issued.store.invalidateAuthOperations()
  }
  ownIssuedAuth = null
}

function cancelActiveOperation(): void {
  const operation = activeOperation
  invalidateOwnIssuedAuth(operation)
  if (!operation) return
  activeOperation = null
  submitting.value = false
  sendingCode.value = false
}

function clearCodeSession(): void {
  codeSession.channel = ''
  codeSession.address = ''
  codeSession.session = ''
  codeSession.maskedAddress = ''
  codeSession.authBoundary = null
  form.code = ''
  clearCountdown()
}

function captureCodeSession(): CodeSessionSnapshot {
  return Object.freeze({
    channel: codeSession.channel,
    address: codeSession.address,
    session: codeSession.session,
    maskedAddress: codeSession.maskedAddress,
    authBoundary: codeSession.authBoundary ? Object.freeze({ ...codeSession.authBoundary }) : null,
  })
}

function clearCodeSessionIfSendSnapshotOwnedBy(
  operation: LoginOperation,
  snapshot: CodeSessionSnapshot,
  boundary: AuthBoundary,
  authStore: ReturnType<typeof useAuthStore>,
): boolean {
  if (!snapshot.session || snapshot.channel !== operation.snapshot.channel || snapshot.address !== operation.snapshot.address) return false
  if (!isOperationContextCurrent(operation) || !isAuthBoundaryCurrent(authStore, boundary)) return false
  if (!authBoundariesEqual(snapshot.authBoundary, boundary)) return false
  if (codeSession.channel !== snapshot.channel
    || codeSession.address !== snapshot.address
    || codeSession.session !== snapshot.session
    || !authBoundariesEqual(codeSession.authBoundary, snapshot.authBoundary)) {
    return false
  }
  clearCodeSession()
  return true
}

function isCodeSessionOwnedBy(operation: LoginOperation): boolean {
  const snapshot = operation.snapshot
  return snapshot.kind === 'finish-code'
    && codeSession.channel === snapshot.channel
    && codeSession.address === snapshot.address
    && codeSession.session === snapshot.session
}

function clearCodeSessionIfOwnedBy(operation: LoginOperation): boolean {
  if (!isOperationContextCurrent(operation) || !isCodeSessionOwnedBy(operation)) return false
  clearCodeSession()
  return true
}

function cancelForContextChange(): void {
  if (!isMounted) return
  cancelActiveOperation()
  clearCodeSession()
  resetErrors()
  verifyBlockRef.value?.reset()
  showVerify.value = false
}

async function ensureVerification(
  operation: LoginOperation,
  store: ReturnType<typeof useAuthStore>,
  boundary: AuthBoundary,
): Promise<VerificationSubmission | null> {
  if (!verifyEnabled) return { block: null, result: null }
  if (!showVerify.value) {
    showVerify.value = true
    await nextTick()
    if (!isOperationContextCurrent(operation)) return null
  }

  const block = verifyBlockRef.value
  if (!block) return { block: null, result: null }
  let result: AuthVerifyResult | null = null
  try {
    result = await block.ensureVerified()
  } catch {
    result = null
  }
  if (!isOperationContextCurrent(operation) || !isAuthBoundaryCurrent(store, boundary)) return null
  return { block, result }
}

function settleConsumedVerification(operation: LoginOperation, submission: VerificationSubmission | null): boolean {
  if (!submission?.block || !submission.result || !isOperationContextCurrent(operation)) return false
  if (verifyBlockRef.value !== submission.block) return false
  let reset = false
  try {
    reset = submission.block.resetIfMatches(submission.result)
  } catch {
    reset = false
  }
  if (reset && isOperationContextCurrent(operation)) showVerify.value = false
  return reset
}

async function onSubmitLogin(): Promise<void> {
  if (!isMounted || activeOperation !== null) return
  resetErrors()
  const snapshotMode = mode.value
  const { channel, address } = addressForMode(snapshotMode)
  const okUser = channel === 'phone' ? validatePhone(address) : validateEmail(address)
  const okPwd = snapshotMode === 'email-code' || snapshotMode === 'phone-code' ? true : validatePassword()
  const okCode = snapshotMode === 'email-code' || snapshotMode === 'phone-code' ? validateCode(form.code) : true
  if (!okUser || !okPwd || !okCode) {
    emit('notify', '请完成必填项并修正错误')
    return
  }

  if (snapshotMode === 'email-code' || snapshotMode === 'phone-code') {
    if (!codeSession.session || codeSession.channel !== channel || codeSession.address !== address) {
      emit('notify', channel === 'email' ? '请先获取邮箱验证码' : '请先获取手机验证码')
      return
    }
    const authStore = getAuthStore()
    if (!codeSession.authBoundary || !isAuthBoundaryCurrent(authStore, codeSession.authBoundary)) {
      clearCodeSession()
      emit('notify', '验证码会话已失效，请重新获取验证码')
      return
    }
  }

  const snapshot = Object.freeze({
    kind: snapshotMode === 'email-code' || snapshotMode === 'phone-code' ? 'finish-code' : 'password',
    mode: snapshotMode,
    channel,
    address,
    ...(snapshotMode === 'email-code' || snapshotMode === 'phone-code'
      ? { code: form.code.trim(), session: codeSession.session, sessionBoundary: codeSession.authBoundary || undefined }
      : { password: form.password }),
  }) as LoginSnapshot
  const operation = beginOperation(snapshot)
  if (!operation) return
  await runOperation(operation)
}

async function runPasswordLogin(operation: LoginOperation): Promise<void> {
  const snapshot = operation.snapshot
  const authStore = getAuthStore()
  const boundary = captureAuthBoundary(authStore)
  let submission: VerificationSubmission | null = null
  let requestIssued = false
  let issuedGeneration: number | null = null
  try {
    submission = await ensureVerification(operation, authStore, boundary)
    if (!isOperationContextCurrent(operation)) return
    if (!submission) return
    if (verifyEnabled && !submission.result) {
      emit('notify', '请先完成人工验证')
      return
    }
    if (!isAuthBoundaryCurrent(authStore, boundary)) return

    const verifyPayload = toVerifyPayload(submission?.result)
    if (!isOperationContextCurrent(operation) || !isAuthBoundaryCurrent(authStore, boundary)) return
    if (verifyEnabled && !verifyPayload.verifyToken) {
      settleConsumedVerification(operation, submission)
      emit('notify', AUTH_VERIFY_EXPIRED_MESSAGE)
      return
    }

    requestIssued = true
    const request = snapshot.mode === 'phone-password'
      ? authStore.loginWithPhonePassword({ phone: snapshot.address, password: snapshot.password || '', ...verifyPayload })
      : authStore.loginWithEmailPassword({ email: snapshot.address, password: snapshot.password || '', ...verifyPayload })
    issuedGeneration = authStore.getAuthGeneration()
    ownIssuedAuth = { operationId: operation.id, store: authStore, generation: issuedGeneration }
    await request
    if (ownIssuedAuth?.operationId === operation.id) ownIssuedAuth = null

    if (!isOperationContextCurrent(operation)) return
    settleConsumedVerification(operation, submission)
    if (issuedGeneration === null || !authStore.isAuthGenerationCurrent(issuedGeneration)) return
    if (!isOperationContextCurrent(operation)) return
    emit('notify', '登录成功')
    if (!isOperationActive(operation)) return
    emit('success')
  } catch (error: unknown) {
    if (ownIssuedAuth?.operationId === operation.id) ownIssuedAuth = null
    if (!isOperationContextCurrent(operation)) return
    if (requestIssued) settleConsumedVerification(operation, submission)
    if (issuedGeneration !== null && !authStore.isAuthGenerationCurrent(issuedGeneration)) return
    if (!isAuthOperationSuperseded(error)) emit('notify', getLoginErrorMessage(error) || '登录失败，请稍后再试')
  } finally {
    if (ownIssuedAuth?.operationId === operation.id) ownIssuedAuth = null
  }
}

async function runCodeFinish(operation: LoginOperation): Promise<void> {
  const snapshot = operation.snapshot
  const authStore = getAuthStore()
  const boundary = snapshot.sessionBoundary
  let issuedGeneration: number | null = null
  let requestIssued = false
  try {
    if (!boundary || !isOperationContextCurrent(operation) || !isAuthBoundaryCurrent(authStore, boundary)) {
      if (isOperationContextCurrent(operation)) {
        clearCodeSession()
        emit('notify', '验证码会话已失效，请重新获取验证码')
      }
      return
    }
    const request = snapshot.channel === 'email'
      ? authStore.loginWithEmailCode({ channel: 'email', address: snapshot.address, code: snapshot.code || '', session: snapshot.session || '' })
      : authStore.loginWithPhoneCode({ channel: 'phone', address: snapshot.address, code: snapshot.code || '', session: snapshot.session || '' })
    issuedGeneration = authStore.getAuthGeneration()
    requestIssued = true
    ownIssuedAuth = { operationId: operation.id, store: authStore, generation: issuedGeneration }
    if (isOperationContextCurrent(operation)
      && codeSession.channel === snapshot.channel
      && codeSession.address === snapshot.address
      && codeSession.session === snapshot.session
      && authBoundariesEqual(codeSession.authBoundary, boundary)) {
      codeSession.authBoundary = Object.freeze({ ...boundary, generation: issuedGeneration })
    }
    await request
    if (ownIssuedAuth?.operationId === operation.id) ownIssuedAuth = null
    if (!isOperationContextCurrent(operation)) return
    if (issuedGeneration === null || !authStore.isAuthGenerationCurrent(issuedGeneration)) return
    emit('notify', '登录成功')
    if (!isOperationActive(operation)) return
    emit('success')
  } catch (error: unknown) {
    if (ownIssuedAuth?.operationId === operation.id) ownIssuedAuth = null
    if (!isOperationContextCurrent(operation)) return
    if (issuedGeneration !== null && !authStore.isAuthGenerationCurrent(issuedGeneration)) {
      clearCodeSessionIfOwnedBy(operation)
      return
    }
    if (requestIssued && !isRetryableCodeFinishFailure(error)) clearCodeSessionIfOwnedBy(operation)
    if (!isAuthOperationSuperseded(error)) emit('notify', getCodeFinishFailureMessage(error))
  } finally {
    if (ownIssuedAuth?.operationId === operation.id) ownIssuedAuth = null
  }
}

async function runCodeStart(operation: LoginOperation): Promise<void> {
  const snapshot = operation.snapshot
  const authStore = getAuthStore()
  const boundary = captureAuthBoundary(authStore)
  const previousCodeSession = captureCodeSession()
  let submission: VerificationSubmission | null = null
  let requestIssued = false
  try {
    submission = await ensureVerification(operation, authStore, boundary)
    if (!isOperationContextCurrent(operation)) return
    if (!submission) return
    if (verifyEnabled && !submission.result) {
      emit('notify', '请先完成人工验证')
      return
    }
    if (!isAuthBoundaryCurrent(authStore, boundary)) return

    const verifyPayload = toVerifyPayload(submission?.result)
    if (!isOperationContextCurrent(operation) || !isAuthBoundaryCurrent(authStore, boundary)) return
    if (verifyEnabled && !verifyPayload.verifyToken) {
      settleConsumedVerification(operation, submission)
      emit('notify', AUTH_VERIFY_EXPIRED_MESSAGE)
      return
    }

    requestIssued = true
    const response = snapshot.channel === 'email'
      ? await startEmailCodeLogin({ channel: 'email', address: snapshot.address, ...verifyPayload })
      : await startPhoneCodeLogin({ channel: 'phone', address: snapshot.address, ...verifyPayload })
    if (!isOperationContextCurrent(operation)) return
    settleConsumedVerification(operation, submission)
    if (response.channel !== snapshot.channel) {
      if (clearCodeSessionIfSendSnapshotOwnedBy(operation, previousCodeSession, boundary, authStore)) {
        emit('notify', CODE_SEND_UNKNOWN_RESULT_MESSAGE)
      }
      return
    }
    if (!isAuthBoundaryCurrent(authStore, boundary)) return
    if (!isOperationContextCurrent(operation)) return
    codeSession.channel = snapshot.channel
    codeSession.address = snapshot.address
    codeSession.session = response.session
    codeSession.maskedAddress = response.maskedAddress
    codeSession.authBoundary = boundary
    form.code = ''
    startCountdown(response.cooldownSeconds || 60)
    emit('notify', snapshot.channel === 'email' ? '验证码已发送，请查收邮件' : '验证码已发送，请查收短信')
  } catch (error: unknown) {
    if (!isOperationActive(operation)) return
    if (requestIssued && !isOtpProviderNotCalledFailure(error)) {
      settleConsumedVerification(operation, submission)
    }
    if (!isAuthBoundaryCurrent(authStore, boundary)) return
    if (requestIssued
      && isUnknownCodeSendFailure(error)
      && clearCodeSessionIfSendSnapshotOwnedBy(operation, previousCodeSession, boundary, authStore)) {
      emit('notify', CODE_SEND_UNKNOWN_RESULT_MESSAGE)
      return
    }
    emit('notify', getLoginErrorMessage(error) || '验证码发送失败')
  } finally {
    if (activeOperation?.id === operation.id) sendingCode.value = false
  }
}

async function runOperation(operation: LoginOperation): Promise<void> {
  try {
    if (operation.snapshot.kind === 'send-code') await runCodeStart(operation)
    else if (operation.snapshot.kind === 'finish-code') await runCodeFinish(operation)
    else await runPasswordLogin(operation)
  } finally {
    releaseOperation(operation)
  }
}

async function onSendEmailCode(): Promise<void> {
  if (!isMounted || activeOperation !== null || codeCountdown.value > 0) return
  resetErrors()
  const address = normalizeEmail(form.email)
  if (!validateEmail(address)) {
    emit('notify', '请先输入正确的邮箱')
    return
  }
  const operation = beginOperation(Object.freeze({ kind: 'send-code', mode: 'email-code', channel: 'email', address }))
  if (!operation) return
  await runOperation(operation)
}

async function onSendPhoneCode(): Promise<void> {
  if (!isMounted || activeOperation !== null || codeCountdown.value > 0) return
  resetErrors()
  const address = normalizePhone(form.phone)
  if (!validatePhone(address)) {
    emit('notify', '请先输入正确的手机号')
    return
  }
  const operation = beginOperation(Object.freeze({ kind: 'send-code', mode: 'phone-code', channel: 'phone', address }))
  if (!operation) return
  await runOperation(operation)
}

function switchMode(nextMode: LoginMode): void {
  if (!isMounted) return
  cancelActiveOperation()
  mode.value = nextMode
  resetErrors()
  clearCodeSession()
  verifyBlockRef.value?.reset()
  showVerify.value = false
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
  codeCountdown.value = 0
}

onBeforeUnmount(() => {
  isMounted = false
  cancelActiveOperation()
  clearCountdown()
})
</script>

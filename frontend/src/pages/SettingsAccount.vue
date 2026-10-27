<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getMyBasicInfo, updateMyBasicInfo, updateMyPassword } from '@/api/settings'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import { isValidUsername, isValidPhone, isValidEmail, isStrongPassword, getPasswordError } from '@/utils/validators'
import type { ApiError, BasicInfoPayload, Id } from '@/types'

interface PasswordForm {
  current: string
  next: string
  confirm: string
}

interface AccountErrors {
  username: string
  phone: string
  email: string
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

interface AccountForm {
  username: string
  phone: string
  email: string
}

interface SessionContext {
  token: string
  epoch: number
  ownerId: Id | null
}

const EMPTY_ACCOUNT: AccountForm = { username: '', phone: '', email: '' }
const authStore = useAuthStore()
const { token, user } = storeToRefs(authStore)
const profile = reactive<AccountForm>({ ...EMPTY_ACCOUNT })
const profileBaseline = ref(accountSnapshot(profile))
const errors = reactive<AccountErrors>({ username: '', phone: '', email: '', currentPassword: '', newPassword: '', confirmPassword: '' })
const savingProfile = ref(false)
const savingPassword = ref(false)
const accountLoading = ref(false)
const accountLoaded = ref(false)
const accountLoadError = ref('')
const profileMessage = ref('')
const profileMessageError = ref(false)
const passwordMessage = ref('')
const passwordMessageError = ref(false)
const pwd = reactive<PasswordForm>({ current: '', next: '', confirm: '' })

let disposed = false
let sessionEpoch = 0
let profileLoadSeq = 0
let profileSaveSeq = 0
let passwordSeq = 0
let reloadTimer: ReturnType<typeof setTimeout> | null = null
let profileMessageTimer: ReturnType<typeof setTimeout> | null = null
let passwordMessageTimer: ReturnType<typeof setTimeout> | null = null
let profileMessageVersion = 0
let passwordMessageVersion = 0
let observedToken = ''
let observedStoredToken = ''
let observedOwnerKey: string | null = null

const emit = defineEmits<{
  'dirty-change': [dirty: boolean]
}>()

function accountSnapshot(value: Partial<AccountForm>): string {
  return JSON.stringify({
    username: String(value.username || ''),
    phone: String(value.phone || ''),
    email: String(value.email || ''),
  })
}

function normalizeAccount(value: Partial<BasicInfoPayload> | null | undefined, fallback: Partial<AccountForm> = EMPTY_ACCOUNT): AccountForm {
  return {
    username: String(value?.username ?? fallback.username ?? ''),
    phone: String(value?.phone ?? fallback.phone ?? ''),
    email: String(value?.email ?? fallback.email ?? ''),
  }
}

function normalizeId(value: unknown): Id | null {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : null
  if (typeof value === 'string' && value.trim()) return value.trim()
  return null
}

function idKey(value: unknown): string | null {
  const normalized = normalizeId(value)
  return normalized === null ? null : String(normalized)
}

function currentToken(): string {
  return String(token.value || '').trim()
}

function storedToken(): string {
  return String(getStoredAccessToken() || '').trim()
}

function currentOwnerId(): Id | null {
  return normalizeId(user.value?.id ?? user.value?.userId)
}

function captureSession(): SessionContext | null {
  const accessToken = currentToken()
  if (!hasRealToken(accessToken) || accessToken !== storedToken()) return null
  return { token: accessToken, epoch: sessionEpoch, ownerId: currentOwnerId() }
}

function clearUnavailableSession(): void {
  resetAccountStateForSession()
  accountLoadError.value = hasRealToken(currentToken())
    ? '账号状态已变更，请刷新页面后重试'
    : '请登录后再加载账户信息'
}

function sameOwner(expected: Id | null, actual: Id | null): boolean {
  // Header hydration fills an identity after a token-only session starts.
  if (expected === null) return true
  return actual !== null && idKey(expected) === idKey(actual)
}

function isCurrentSession(context: SessionContext): boolean {
  return !disposed
    && context.epoch === sessionEpoch
    && currentToken() === context.token
    && storedToken() === context.token
    && sameOwner(context.ownerId, currentOwnerId())
}

function isCurrentOperation(context: SessionContext, sequence: number, currentSequence: number): boolean {
  return isCurrentSession(context) && sequence === currentSequence
}

function passwordHasValue(): boolean {
  return Boolean(pwd.current || pwd.next || pwd.confirm)
}

const accountDirty = computed(() => accountSnapshot(profile) !== profileBaseline.value || passwordHasValue())
const contactChanged = computed(() => {
  if (!accountLoaded.value) return false
  const baseline = JSON.parse(profileBaseline.value) as AccountForm
  return profile.phone.trim() !== baseline.phone.trim() || profile.email.trim() !== baseline.email.trim()
})
watch(accountDirty, (dirty) => {
  emit('dirty-change', dirty)
}, { immediate: true })

function getApiErrorMessage(error: unknown, fallback = ''): string {
  const e = error as ApiError | null | undefined
  return e?.response?.data?.message || e?.message || fallback
}

function clearErrors(): void {
  errors.username = ''
  errors.phone = ''
  errors.email = ''
  errors.currentPassword = ''
  errors.newPassword = ''
  errors.confirmPassword = ''
}

function clearProfileMessageTimer(): void {
  if (profileMessageTimer !== null) {
    clearTimeout(profileMessageTimer)
    profileMessageTimer = null
  }
  profileMessageVersion += 1
}

function clearPasswordMessageTimer(): void {
  if (passwordMessageTimer !== null) {
    clearTimeout(passwordMessageTimer)
    passwordMessageTimer = null
  }
  passwordMessageVersion += 1
}

function setProfileMessage(message: string, isError = false): void {
  profileMessage.value = message
  profileMessageError.value = isError
  clearProfileMessageTimer()
  const version = ++profileMessageVersion
  const messageEpoch = sessionEpoch
  profileMessageTimer = setTimeout(() => {
    profileMessageTimer = null
    if (disposed || messageEpoch !== sessionEpoch || version !== profileMessageVersion) return
    profileMessage.value = ''
    profileMessageError.value = false
  }, isError ? 4000 : 3000)
}

function setPasswordMessage(message: string, isError = false): void {
  passwordMessage.value = message
  passwordMessageError.value = isError
  clearPasswordMessageTimer()
  const version = ++passwordMessageVersion
  const messageEpoch = sessionEpoch
  passwordMessageTimer = setTimeout(() => {
    passwordMessageTimer = null
    if (disposed || messageEpoch !== sessionEpoch || version !== passwordMessageVersion) return
    passwordMessage.value = ''
    passwordMessageError.value = false
  }, isError ? 4000 : 3000)
}

function invalidateOperations(): void {
  profileLoadSeq += 1
  profileSaveSeq += 1
  passwordSeq += 1
  sessionEpoch += 1
}

function resetAccountStateForSession(): void {
  invalidateOperations()
  profile.username = ''
  profile.phone = ''
  profile.email = ''
  profileBaseline.value = accountSnapshot(profile)
  accountLoading.value = false
  accountLoaded.value = false
  accountLoadError.value = ''
  savingProfile.value = false
  savingPassword.value = false
  pwd.current = ''
  pwd.next = ''
  pwd.confirm = ''
  clearErrors()
  clearProfileMessageTimer()
  clearPasswordMessageTimer()
  profileMessage.value = ''
  profileMessageError.value = false
  passwordMessage.value = ''
  passwordMessageError.value = false
}

function scheduleProfileReload(): void {
  if (disposed || reloadTimer !== null) return
  reloadTimer = setTimeout(() => {
    reloadTimer = null
    if (!disposed) void reloadProfile()
  }, 0)
}

function observeSessionChange(): boolean {
  const nextToken = currentToken()
  const nextStoredToken = storedToken()
  const nextOwnerKey = idKey(currentOwnerId())
  const tokenChanged = nextToken !== observedToken || nextStoredToken !== observedStoredToken
  const sameTokenHydration = !tokenChanged && observedOwnerKey === null && nextOwnerKey !== null
  const ownerChanged = !sameTokenHydration && nextOwnerKey !== observedOwnerKey
  observedToken = nextToken
  observedStoredToken = nextStoredToken
  observedOwnerKey = nextOwnerKey
  return tokenChanged || ownerChanged
}

function handleSessionSignal(): void {
  if (disposed || !observeSessionChange()) return
  resetAccountStateForSession()
  scheduleProfileReload()
}

function forceAuthSessionSignal(): void {
  if (disposed) return
  if (reloadTimer !== null) {
    clearTimeout(reloadTimer)
    reloadTimer = null
  }
  resetAccountStateForSession()
  scheduleProfileReload()
}

function clearStateOnSessionDrift(context: SessionContext, sequence: number, currentSequence: number): void {
  if (disposed || sequence !== currentSequence) return
  if (currentToken() === context.token
    && storedToken() === context.token
    && sameOwner(context.ownerId, currentOwnerId())) return
  observedToken = currentToken()
  observedStoredToken = storedToken()
  observedOwnerKey = idKey(currentOwnerId())
  clearUnavailableSession()
}

async function reloadProfile(): Promise<void> {
  if (disposed) return
  if (savingProfile.value) {
    setProfileMessage('保存进行中，请稍候', true)
    return
  }
  const sequence = ++profileLoadSeq
  const context = captureSession()
  if (!context) {
    if (!disposed && sequence === profileLoadSeq) clearUnavailableSession()
    return
  }

  accountLoading.value = true
  accountLoadError.value = ''
  try {
    const data = await getMyBasicInfo()
    if (!isCurrentOperation(context, sequence, profileLoadSeq)) return
    if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).length === 0) throw new Error('账户信息为空，请稍后重试')
    const normalized = normalizeAccount(data)
    profile.username = normalized.username
    profile.phone = normalized.phone
    profile.email = normalized.email
    profileBaseline.value = accountSnapshot(normalized)
    accountLoaded.value = true
    clearErrors()
  } catch (error: unknown) {
    if (!isCurrentOperation(context, sequence, profileLoadSeq)) return
    accountLoaded.value = false
    accountLoadError.value = getApiErrorMessage(error, '账户信息加载失败，请重试')
  } finally {
    clearStateOnSessionDrift(context, sequence, profileLoadSeq)
    if (isCurrentOperation(context, sequence, profileLoadSeq)) accountLoading.value = false
  }
}

function validateProfile(): boolean {
  clearErrors()
  let ok = true
  if (!isValidUsername(profile.username)) { errors.username = '3-64 位字母、数字或下划线'; ok = false }
  const phone = String(profile.phone || '').trim()
  const email = String(profile.email || '').trim()
  if (!phone) { errors.phone = '请输入手机号'; ok = false }
  else if (!isValidPhone(phone)) { errors.phone = '请输入有效手机号（1 开头的 11 位）'; ok = false }
  if (!email) { errors.email = '请输入邮箱'; ok = false }
  else if (!isValidEmail(email)) { errors.email = '邮箱格式不正确'; ok = false }
  return ok
}

async function onSaveProfile(): Promise<void> {
  if (!accountLoaded.value) {
    setProfileMessage(accountLoadError.value || '账户信息加载失败，请重试后再保存', true)
    return
  }
  if (savingProfile.value || accountLoading.value) return
  if (!validateProfile()) return
  const context = captureSession()
  if (!context) {
    clearUnavailableSession()
    return
  }
  const sequence = ++profileSaveSeq
  const submitted = normalizeAccount(profile)
  savingProfile.value = true
  clearProfileMessageTimer()
  profileMessage.value = ''
  profileMessageError.value = false
  try {
    const data = await updateMyBasicInfo(submitted)
    if (!isCurrentOperation(context, sequence, profileSaveSeq)) return
    if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).length === 0) throw new Error('保存结果为空，请稍后重试')
    const server = normalizeAccount(data, submitted)
    const current = normalizeAccount(profile)
    profile.username = current.username !== submitted.username ? current.username : server.username
    profile.phone = current.phone !== submitted.phone ? current.phone : server.phone
    profile.email = current.email !== submitted.email ? current.email : server.email
    profileBaseline.value = accountSnapshot(server)
    const confirmedOwnerId = currentOwnerId()
    const ownerId = confirmedOwnerId !== null
      && (context.ownerId === null || sameOwner(context.ownerId, confirmedOwnerId))
      ? confirmedOwnerId
      : null
    if (ownerId !== null && isCurrentSession(context)) {
      authStore.updateCurrentUser({ username: server.username, phone: server.phone, email: server.email })
      try {
        window.dispatchEvent(new CustomEvent('profile-updated', { detail: { id: ownerId, username: server.username } }))
      } catch (_) {}
    }
    setProfileMessage('保存成功')
  } catch (error: unknown) {
    if (!isCurrentOperation(context, sequence, profileSaveSeq)) return
    const msg = getApiErrorMessage(error)
    if (/用户名/.test(msg)) errors.username = msg
    if (/邮箱/.test(msg)) errors.email = msg
    if (/手机号/.test(msg)) errors.phone = msg
    setProfileMessage(msg || '保存失败', true)
  } finally {
    clearStateOnSessionDrift(context, sequence, profileSaveSeq)
    if (isCurrentOperation(context, sequence, profileSaveSeq)) savingProfile.value = false
  }
}

function resetPwdForm(): void {
  pwd.current = ''
  pwd.next = ''
  pwd.confirm = ''
  clearErrors()
}

function validatePasswordForm(): boolean {
  clearErrors()
  if (!pwd.current) { errors.currentPassword = '请输入当前密码'; return false }
  if (!isStrongPassword(pwd.next)) { errors.newPassword = getPasswordError(pwd.next) || '至少 8 位且包含大小写字母与数字'; return false }
  if (pwd.confirm !== pwd.next) { errors.confirmPassword = '两次输入的新密码不一致'; return false }
  return true
}

async function onChangePassword(): Promise<void> {
  if (!accountLoaded.value) {
    setPasswordMessage(accountLoadError.value || '账户信息加载失败，请重试后再保存', true)
    return
  }
  if (savingPassword.value) return
  if (!validatePasswordForm()) return
  const context = captureSession()
  if (!context) {
    clearUnavailableSession()
    return
  }
  const sequence = ++passwordSeq
  const submitted = { current: pwd.current, next: pwd.next, confirm: pwd.confirm }
  savingPassword.value = true
  clearPasswordMessageTimer()
  passwordMessage.value = ''
  passwordMessageError.value = false
  try {
    await updateMyPassword({ currentPassword: submitted.current, newPassword: submitted.next })
    if (!isCurrentOperation(context, sequence, passwordSeq)) return
    if (pwd.current === submitted.current) pwd.current = ''
    if (pwd.next === submitted.next) pwd.next = ''
    if (pwd.confirm === submitted.confirm) pwd.confirm = ''
    setPasswordMessage('密码已修改')
  } catch (error: unknown) {
    if (!isCurrentOperation(context, sequence, passwordSeq)) return
    const msg = getApiErrorMessage(error)
    if (/当前密码/.test(msg) || /不正确/.test(msg)) { errors.currentPassword = '当前密码不正确' }
    setPasswordMessage(msg || '修改失败', true)
  } finally {
    clearStateOnSessionDrift(context, sequence, passwordSeq)
    if (isCurrentOperation(context, sequence, passwordSeq)) savingPassword.value = false
  }
}

watch(
  [() => currentToken(), () => storedToken(), () => idKey(currentOwnerId())],
  () => handleSessionSignal(),
  { flush: 'sync' },
)

function onAuthStateChanged(): void {
  if (disposed) return
  observeSessionChange()
  forceAuthSessionSignal()
}

onMounted(() => {
  observedToken = currentToken()
  observedStoredToken = storedToken()
  observedOwnerKey = idKey(currentOwnerId())
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  void reloadProfile()
})

onBeforeUnmount(() => {
  disposed = true
  if (reloadTimer !== null) {
    clearTimeout(reloadTimer)
    reloadTimer = null
  }
  clearProfileMessageTimer()
  clearPasswordMessageTimer()
  invalidateOperations()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

<template>
  <div class="space-y-5">
    <div v-if="accountLoadError" role="alert" aria-live="assertive" class="ui-error flex flex-col gap-3 text-sm sm:flex-row sm:items-center sm:justify-between">
      <span>{{ accountLoadError }}</span>
      <button type="button" class="ui-button self-start px-3 py-1.5 text-xs sm:self-auto" :disabled="accountLoading" @click="reloadProfile">
        {{ accountLoading ? '重试中...' : '重试' }}
      </button>
    </div>
    <div v-if="accountLoading && !accountLoaded" class="ui-muted text-sm" role="status" aria-live="polite">正在加载账户信息...</div>

    <section class="space-y-4 border-b border-gray-200 pb-6 dark:border-gray-700">
      <fieldset :disabled="!accountLoaded || accountLoading" class="space-y-3">
        <legend class="text-base font-semibold">账户信息</legend>
        <div>
          <label for="settings-account-username" class="mb-1 block text-sm font-medium">账户名</label>
          <input id="settings-account-username" v-model.trim="profile.username" type="text" maxlength="64" placeholder="3-64 位字母、数字或下划线"
                 :aria-invalid="errors.username ? 'true' : 'false'" :aria-describedby="errors.username ? 'settings-account-username-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.username" id="settings-account-username-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.username }}</p>
        </div>
        <div>
          <label for="settings-account-phone" class="mb-1 block text-sm font-medium">手机号</label>
          <input id="settings-account-phone" v-model.trim="profile.phone" type="tel" required placeholder="1 开头的 11 位手机号"
                 :aria-invalid="errors.phone ? 'true' : 'false'" :aria-describedby="errors.phone ? 'settings-account-phone-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.phone" id="settings-account-phone-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.phone }}</p>
        </div>
        <div>
          <label for="settings-account-email" class="mb-1 block text-sm font-medium">电子邮箱</label>
          <input id="settings-account-email" v-model.trim="profile.email" type="email" required placeholder="name@example.com"
                 :aria-invalid="errors.email ? 'true' : 'false'" :aria-describedby="errors.email ? 'settings-account-email-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.email" id="settings-account-email-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.email }}</p>
        </div>

        <p v-if="contactChanged" role="status" aria-live="polite" class="ui-muted text-sm">
          更换后，新的邮箱或手机号暂不能用于验证码登录。请先确认其他登录方式可用，以免无法登录原账号。
        </p>

        <div class="flex flex-col items-stretch gap-2 pt-2 sm:flex-row sm:items-center sm:justify-end">
          <span v-if="profileMessage" role="status" aria-live="polite" :class="['text-xs sm:mr-auto', profileMessageError ? 'text-red-600 dark:text-red-400' : 'text-emerald-700 dark:text-emerald-300']">{{ profileMessage }}</span>
          <button type="button" class="ui-button" @click="reloadProfile">重置</button>
          <button type="button" class="ui-button-primary"
                  :disabled="savingProfile || !accountLoaded || accountLoading" @click="onSaveProfile">{{ savingProfile ? '保存中…' : '保存修改' }}</button>
        </div>
      </fieldset>
    </section>

    <section class="space-y-4">
      <fieldset :disabled="!accountLoaded || accountLoading" class="space-y-3">
        <legend class="text-base font-semibold">修改密码</legend>
        <div>
          <label for="settings-account-current-password" class="mb-1 block text-sm font-medium">当前密码</label>
          <input id="settings-account-current-password" v-model="pwd.current" type="password" autocomplete="current-password" placeholder="请输入当前密码"
                 :aria-invalid="errors.currentPassword ? 'true' : 'false'" :aria-describedby="errors.currentPassword ? 'settings-account-current-password-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.currentPassword" id="settings-account-current-password-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.currentPassword }}</p>
        </div>
        <div>
          <label for="settings-account-new-password" class="mb-1 block text-sm font-medium">新密码</label>
          <input id="settings-account-new-password" v-model="pwd.next" type="password" autocomplete="new-password" placeholder="至少 8 位且含大小写字母和数字"
                 :aria-invalid="errors.newPassword ? 'true' : 'false'" :aria-describedby="errors.newPassword ? 'settings-account-new-password-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.newPassword" id="settings-account-new-password-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.newPassword }}</p>
        </div>
        <div>
          <label for="settings-account-confirm-password" class="mb-1 block text-sm font-medium">确认新密码</label>
          <input id="settings-account-confirm-password" v-model="pwd.confirm" type="password" autocomplete="new-password" placeholder="再次输入新密码"
                 :aria-invalid="errors.confirmPassword ? 'true' : 'false'" :aria-describedby="errors.confirmPassword ? 'settings-account-confirm-password-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.confirmPassword" id="settings-account-confirm-password-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.confirmPassword }}</p>
        </div>

        <div class="flex flex-col items-stretch gap-2 pt-2 sm:flex-row sm:items-center sm:justify-end">
          <span v-if="passwordMessage" role="status" aria-live="polite" :class="['text-xs sm:mr-auto', passwordMessageError ? 'text-red-600 dark:text-red-400' : 'text-emerald-700 dark:text-emerald-300']">{{ passwordMessage }}</span>
          <button type="button" class="ui-button" @click="resetPwdForm">清空</button>
          <button type="button" class="ui-button-primary"
                  :disabled="savingPassword || !accountLoaded || accountLoading" @click="onChangePassword">{{ savingPassword ? '修改中…' : '修改密码' }}</button>
        </div>
      </fieldset>
    </section>
  </div>
</template>

<style scoped>
</style>

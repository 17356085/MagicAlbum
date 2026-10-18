<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getMyBasicInfo, updateMyBasicInfo, updateMyPassword } from '@/api/settings'
import { isValidUsername, isValidPhone, isValidEmail, isStrongPassword, getPasswordError } from '@/utils/validators'
import type { ApiError, BasicInfoPayload } from '@/types'

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

const profile = reactive<BasicInfoPayload>({ username: '', phone: '', email: '' })
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

const emit = defineEmits<{
  'dirty-change': [dirty: boolean]
}>()

function accountSnapshot(value: BasicInfoPayload): string {
  return JSON.stringify({
    username: String(value.username || ''),
    phone: String(value.phone || ''),
    email: String(value.email || ''),
  })
}

function passwordHasValue(): boolean {
  return Boolean(pwd.current || pwd.next || pwd.confirm)
}

const accountDirty = computed(() => accountSnapshot(profile) !== profileBaseline.value || passwordHasValue())
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

function setProfileMessage(message: string, isError = false): void {
  profileMessage.value = message
  profileMessageError.value = isError
  setTimeout(() => {
    profileMessage.value = ''
    profileMessageError.value = false
  }, isError ? 4000 : 3000)
}

function setPasswordMessage(message: string, isError = false): void {
  passwordMessage.value = message
  passwordMessageError.value = isError
  setTimeout(() => {
    passwordMessage.value = ''
    passwordMessageError.value = false
  }, isError ? 4000 : 3000)
}

async function reloadProfile(): Promise<void> {
  accountLoading.value = true
  accountLoadError.value = ''
  try {
    const data = await getMyBasicInfo()
    if (!data || typeof data !== 'object' || Object.keys(data).length === 0) throw new Error('账户信息为空，请稍后重试')
    profile.username = data.username || ''
    profile.phone = data.phone || ''
    profile.email = data.email || ''
    profileBaseline.value = accountSnapshot(profile)
    accountLoaded.value = true
    clearErrors()
  } catch (error: unknown) {
    accountLoaded.value = false
    accountLoadError.value = getApiErrorMessage(error, '账户信息加载失败，请重试')
  } finally {
    accountLoading.value = false
  }
}

function validateProfile(): boolean {
  clearErrors()
  let ok = true
  if (!isValidUsername(profile.username)) { errors.username = '3-20 位字母、数字或下划线'; ok = false }
  const phone = String(profile.phone || '').trim()
  const email = String(profile.email || '').trim()
  if (!phone) { errors.phone = '请输入手机号'; ok = false }
  else if (!isValidPhone(phone)) { errors.phone = '请输入有效手机号（国内 11 位或含国家码）'; ok = false }
  if (!email) { errors.email = '请输入邮箱'; ok = false }
  else if (!isValidEmail(email)) { errors.email = '邮箱格式不正确'; ok = false }
  return ok
}

async function onSaveProfile(): Promise<void> {
  if (!accountLoaded.value) {
    setProfileMessage(accountLoadError.value || '账户信息加载失败，请重试后再保存', true)
    return
  }
  if (!validateProfile()) return
  savingProfile.value = true
  try {
    await updateMyBasicInfo({ username: profile.username, phone: profile.phone, email: profile.email })
    profileBaseline.value = accountSnapshot(profile)
    setProfileMessage('保存成功')
  } catch (error: unknown) {
    const msg = getApiErrorMessage(error)
    if (/用户名/.test(msg)) errors.username = '该用户名已被使用'
    if (/邮箱/.test(msg)) errors.email = '该邮箱已被使用'
    if (/手机号/.test(msg)) errors.phone = '该手机号已被使用'
    setProfileMessage(msg || '保存失败', true)
  } finally {
    savingProfile.value = false
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
  if (!validatePasswordForm()) return
  savingPassword.value = true
  try {
    await updateMyPassword({ currentPassword: pwd.current, newPassword: pwd.next })
    resetPwdForm()
    setPasswordMessage('密码已修改')
  } catch (error: unknown) {
    const msg = getApiErrorMessage(error)
    if (/当前密码/.test(msg) || /不正确/.test(msg)) { errors.currentPassword = '当前密码不正确' }
    setPasswordMessage(msg || '修改失败', true)
  } finally {
    savingPassword.value = false
  }
}

onMounted(() => { void reloadProfile() })
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
          <input id="settings-account-username" v-model.trim="profile.username" type="text" placeholder="3-20 位字母、数字或下划线"
                 :aria-invalid="errors.username ? 'true' : 'false'" :aria-describedby="errors.username ? 'settings-account-username-error' : undefined"
                 class="ui-field text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" />
          <p v-if="errors.username" id="settings-account-username-error" role="alert" aria-live="assertive" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.username }}</p>
        </div>
        <div>
          <label for="settings-account-phone" class="mb-1 block text-sm font-medium">手机号</label>
          <input id="settings-account-phone" v-model.trim="profile.phone" type="tel" required placeholder="国内 11 位或含国家码"
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

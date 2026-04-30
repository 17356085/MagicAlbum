<template>
  <div class="space-y-5">
    <section>
      <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">账户信息</h2>
      <div class="mt-3 space-y-3 text-sm">
        <div>
          <label class="block mb-1">账户名</label>
          <input v-model.trim="profile.username" type="text" placeholder="3-20 位字母、数字或下划线"
                 class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" />
          <p v-if="errors.username" class="mt-1 text-xs text-red-600">{{ errors.username }}</p>
        </div>
        <div>
          <label class="block mb-1">手机号</label>
          <input v-model.trim="profile.phone" type="tel" placeholder="国内 11 位或含国家码"
                 class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" />
          <p v-if="errors.phone" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
        </div>
        <div>
          <label class="block mb-1">电子邮箱</label>
          <input v-model.trim="profile.email" type="email" placeholder="name@example.com"
                 class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" />
          <p v-if="errors.email" class="mt-1 text-xs text-red-600">{{ errors.email }}</p>
        </div>

        <div class="pt-2 flex items-center justify-end gap-2">
          <span v-if="profileMessage" :class="['mr-auto text-xs', profileMessageError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ profileMessage }}</span>
          <button class="rounded-md px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700" @click="reloadProfile">重置</button>
          <button class="rounded-md bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
                  :disabled="savingProfile" @click="onSaveProfile">{{ savingProfile ? '保存中…' : '保存修改' }}</button>
        </div>
      </div>
    </section>

    <section>
      <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">修改密码</h2>
      <div class="mt-3 space-y-3 text-sm">
        <div>
          <label class="block mb-1">当前密码</label>
          <input v-model="pwd.current" type="password" autocomplete="current-password" placeholder="请输入当前密码"
                 class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" />
          <p v-if="errors.currentPassword" class="mt-1 text-xs text-red-600">{{ errors.currentPassword }}</p>
        </div>
        <div>
          <label class="block mb-1">新密码</label>
          <input v-model="pwd.next" type="password" autocomplete="new-password" placeholder="至少 8 位且含大小写字母和数字"
                 class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" />
          <p v-if="errors.newPassword" class="mt-1 text-xs text-red-600">{{ errors.newPassword }}</p>
        </div>
        <div>
          <label class="block mb-1">确认新密码</label>
          <input v-model="pwd.confirm" type="password" autocomplete="new-password" placeholder="再次输入新密码"
                 class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" />
          <p v-if="errors.confirmPassword" class="mt-1 text-xs text-red-600">{{ errors.confirmPassword }}</p>
        </div>

        <div class="pt-2 flex items-center justify-end gap-2">
          <span v-if="passwordMessage" :class="['mr-auto text-xs', passwordMessageError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ passwordMessage }}</span>
          <button class="rounded-md px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700" @click="resetPwdForm">清空</button>
          <button class="rounded-md bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
                  :disabled="savingPassword" @click="onChangePassword">{{ savingPassword ? '修改中…' : '修改密码' }}</button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
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
const origin = reactive<BasicInfoPayload>({ username: '', phone: '', email: '' })
const errors = reactive<AccountErrors>({ username: '', phone: '', email: '', currentPassword: '', newPassword: '', confirmPassword: '' })
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileMessage = ref('')
const profileMessageError = ref(false)
const passwordMessage = ref('')
const passwordMessageError = ref(false)
const pwd = reactive<PasswordForm>({ current: '', next: '', confirm: '' })

function getApiErrorMessage(error: unknown): string {
  const e = error as ApiError | null | undefined
  return e?.response?.data?.message || e?.message || ''
}

async function reloadProfile(): Promise<void> {
  try {
    const p = await getMyBasicInfo()
    profile.username = p?.username || ''
    profile.phone = p?.phone || ''
    profile.email = p?.email || ''
    origin.username = profile.username
    origin.phone = profile.phone
    origin.email = profile.email
    clearErrors()
  } catch (_) {}
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

function validateProfile(): boolean {
  clearErrors()
  let ok = true
  if (!isValidUsername(profile.username)) { errors.username = '3-20 位字母、数字或下划线'; ok = false }
  if (profile.phone && !isValidPhone(profile.phone)) { errors.phone = '请输入有效手机号（国内 11 位或含国家码）'; ok = false }
  if (profile.email && !isValidEmail(profile.email)) { errors.email = '邮箱格式不正确'; ok = false }
  return ok
}

async function onSaveProfile(): Promise<void> {
  if (!validateProfile()) return
  savingProfile.value = true
  try {
    await updateMyBasicInfo({ username: profile.username, phone: profile.phone, email: profile.email })
    origin.username = profile.username
    origin.phone = profile.phone
    origin.email = profile.email
    setProfileMessage('保存成功')
  } catch (e: unknown) {
    const msg = getApiErrorMessage(e)
    if (/用户名/.test(msg)) errors.username = '该用户名已被使用'
    if (/邮箱/.test(msg)) errors.email = '该邮箱已被使用'
    if (/手机号/.test(msg)) errors.phone = '该手机号已被使用'
    setProfileMessage(msg || '保存失败', true)
  } finally {
    savingProfile.value = false
  }
}

function resetPwdForm(): void { pwd.current = ''; pwd.next = ''; pwd.confirm = ''; clearErrors() }

function validatePasswordForm(): boolean {
  clearErrors()
  if (!pwd.current) { errors.currentPassword = '请输入当前密码'; return false }
  if (!isStrongPassword(pwd.next)) { errors.newPassword = getPasswordError(pwd.next) || '至少 8 位且包含大小写字母与数字'; return false }
  if (pwd.confirm !== pwd.next) { errors.confirmPassword = '两次输入的新密码不一致'; return false }
  return true
}

async function onChangePassword(): Promise<void> {
  if (!validatePasswordForm()) return
  savingPassword.value = true
  try {
    await updateMyPassword({ currentPassword: pwd.current, newPassword: pwd.next })
    resetPwdForm()
    setPasswordMessage('密码已修改')
  } catch (e: unknown) {
    const msg = getApiErrorMessage(e)
    if (/当前密码/.test(msg) || /不正确/.test(msg)) { errors.currentPassword = '当前密码不正确' }
    setPasswordMessage(msg || '修改失败', true)
  } finally {
    savingPassword.value = false
  }
}

onMounted(() => { reloadProfile() })
</script>

<style scoped>
</style>

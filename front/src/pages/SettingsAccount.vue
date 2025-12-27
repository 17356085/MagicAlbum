<template>
  <div class="max-w-2xl mx-auto space-y-6">
    <h2 class="text-lg font-semibold">账户与安全</h2>

    <!-- 账户信息 -->
    <section class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <header class="border-b px-4 py-3 text-sm font-medium dark:border-gray-700">账户信息</header>
      <div class="p-4 space-y-3 text-sm">
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
          <button class="rounded px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700" @click="reloadProfile">重置</button>
          <button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
                  :disabled="savingProfile" @click="onSaveProfile">{{ savingProfile ? '保存中…' : '保存修改' }}</button>
        </div>
      </div>
    </section>

    <!-- 修改密码 -->
    <section class="rounded-md border border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700">
      <header class="border-b px-4 py-3 text-sm font-medium dark:border-gray-700">修改密码</header>
      <div class="p-4 space-y-3 text-sm">
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
          <button class="rounded px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700" @click="resetPwdForm">清空</button>
          <button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:opacity-60 focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
                  :disabled="savingPassword" @click="onChangePassword">{{ savingPassword ? '修改中…' : '修改密码' }}</button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { getMyBasicInfo, updateMyBasicInfo, updateMyPassword } from '@/api/settings'
import { isValidUsername, isValidPhone, isValidEmail, isStrongPassword, getPasswordError } from '@/utils/validators'

const profile = reactive({ username: '', phone: '', email: '' })
const origin = reactive({ username: '', phone: '', email: '' })
const errors = reactive({ username: '', phone: '', email: '', currentPassword: '', newPassword: '', confirmPassword: '' })
const savingProfile = ref(false)
const savingPassword = ref(false)

async function reloadProfile() {
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

function clearErrors() {
  errors.username = ''
  errors.phone = ''
  errors.email = ''
  errors.currentPassword = ''
  errors.newPassword = ''
  errors.confirmPassword = ''
}

function validateProfile() {
  clearErrors()
  let ok = true
  if (!isValidUsername(profile.username)) { errors.username = '3-20 位字母、数字或下划线'; ok = false }
  if (profile.phone && !isValidPhone(profile.phone)) { errors.phone = '请输入有效手机号（国内 11 位或含国家码）'; ok = false }
  if (profile.email && !isValidEmail(profile.email)) { errors.email = '邮箱格式不正确'; ok = false }
  return ok
}

async function onSaveProfile() {
  if (!validateProfile()) return
  savingProfile.value = true
  try {
    await updateMyBasicInfo({ username: profile.username, phone: profile.phone, email: profile.email })
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || ''
    if (/用户名/.test(msg)) errors.username = '该用户名已被使用'
    if (/邮箱/.test(msg)) errors.email = '该邮箱已被使用'
    if (/手机号/.test(msg)) errors.phone = '该手机号已被使用'
  } finally {
    savingProfile.value = false
  }
}

function resetPwdForm() { pwd.current = ''; pwd.next = ''; pwd.confirm = ''; clearErrors() }
const pwd = reactive({ current: '', next: '', confirm: '' })

function validatePasswordForm() {
  clearErrors()
  if (!pwd.current) { errors.currentPassword = '请输入当前密码'; return false }
  if (!isStrongPassword(pwd.next)) { errors.newPassword = getPasswordError(pwd.next) || '至少 8 位且包含大小写字母与数字'; return false }
  if (pwd.confirm !== pwd.next) { errors.confirmPassword = '两次输入的新密码不一致'; return false }
  return true
}

async function onChangePassword() {
  if (!validatePasswordForm()) return
  savingPassword.value = true
  try {
    await updateMyPassword({ currentPassword: pwd.current, newPassword: pwd.next })
    resetPwdForm()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || ''
    if (/当前密码/.test(msg) || /不正确/.test(msg)) { errors.currentPassword = '当前密码不正确' }
  } finally {
    savingPassword.value = false
  }
}

onMounted(() => { reloadProfile() })
</script>

<style scoped>
</style>
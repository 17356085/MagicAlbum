<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/30 motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none" @click="onClose"></div>
    <div class="relative z-10 w-full max-w-md rounded-lg border border-gray-200 bg-white shadow-xl dark:bg-gray-800 dark:border-gray-700 motion-safe:transition-colors motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none">
      <div class="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h3 class="text-base font-semibold dark:text-gray-100">注册成为 MagicAlbum 会员</h3>
        <button class="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-700" @click="onClose" aria-label="关闭">✕</button>
      </div>

      <form class="px-4 py-4 space-y-3" @submit.prevent="onSubmit">
        <!-- 用户名 -->
        <div>
          <label class="block text-sm font-medium mb-1">用户名</label>
          <input
            v-model.trim="form.username"
            @blur="validateUsername"
            type="text"
            placeholder="请输入用户名"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 placeholder-gray-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:placeholder-gray-400 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.username" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.username }}</p>
        </div>

        <!-- 手机号 -->
        <div>
          <label class="block text-sm font-medium mb-1">手机号</label>
          <input
            v-model.trim="form.phone"
            type="tel"
            placeholder="请输入 11 位大陆手机号"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 placeholder-gray-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:placeholder-gray-400 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.phone" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.phone }}</p>
        </div>

        <!-- 电子邮箱 -->
        <div>
          <label class="block text-sm font-medium mb-1">电子邮箱</label>
          <input
            v-model.trim="form.email"
            type="email"
            placeholder="name@example.com"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 placeholder-gray-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:placeholder-gray-400 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.email" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.email }}</p>
        </div>

        <!-- 密码 -->
        <div>
          <label class="block text-sm font-medium mb-1">密码</label>
          <input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            placeholder="不少于 8 位，建议包含数字与字母"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 placeholder-gray-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:placeholder-gray-400 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.password" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.password }}</p>
        </div>

        <!-- 确认密码 -->
        <div>
          <label class="block text-sm font-medium mb-1">确认密码</label>
          <input
            v-model="form.confirmPassword"
            type="password"
            autocomplete="new-password"
            placeholder="再次输入密码"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 placeholder-gray-500 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:placeholder-gray-400 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.confirmPassword" class="mt-1 text-xs text-red-600 dark:text-red-400">{{ errors.confirmPassword }}</p>
        </div>

        <!-- 提交按钮 -->
        <div class="pt-2 flex items-center justify-end gap-2">
          <button type="button" class="rounded px-3 py-2 text-sm hover:bg-gray-100 dark:hover:bg-gray-700" @click="onClose">取消</button>
          <button
            type="submit"
            class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:opacity-60 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
            :disabled="submitting"
          >
            {{ submitting ? '提交中…' : '注册' }}
          </button>
        </div>
      </form>
    </div>
  </div>
  
  <!-- 轻量提示 -->
  <div v-if="toast" class="fixed bottom-4 left-1/2 -translate-x-1/2 z-50 rounded bg-black/80 px-3 py-2 text-xs text-white">
    {{ toast }}
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { registerUser, checkUsernameAvailable } from '@/api/users'
import { isValidUsername, isValidPhone, isValidEmail, isStrongPassword, getPasswordError } from '@/utils/validators'

const emit = defineEmits(['close', 'success'])

const form = reactive({
  username: '',
  phone: '',
  email: '',
  password: '',
  confirmPassword: '',
})
const errors = reactive({
  username: '',
  phone: '',
  email: '',
  password: '',
  confirmPassword: '',
})
const submitting = ref(false)
const toast = ref('')

function showToast(msg) {
  toast.value = msg
  setTimeout(() => (toast.value = ''), 2000)
}

function resetErrors() {
  errors.username = ''
  errors.phone = ''
  errors.email = ''
  errors.password = ''
  errors.confirmPassword = ''
}

async function validateUsername() {
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
  } catch (e) {
    errors.username = '无法验证用户名，请稍后再试'
    return false
  }
}

function validatePhone() {
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

function validateEmail() {
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

function validatePassword() {
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

function validateConfirmPassword() {
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

async function onSubmit() {
  resetErrors()
  const okUsername = await validateUsername()
  const okPhone = validatePhone()
  const okEmail = validateEmail()
  const okPwd = validatePassword()
  const okConfirm = validateConfirmPassword()
  if (!okUsername || !okPhone || !okEmail || !okPwd || !okConfirm) {
    showToast('请完成必填项并修正错误')
    return
  }

  submitting.value = true
  try {
    await registerUser({
      username: form.username,
      phone: form.phone,
      email: form.email,
      password: form.password,
    })
    showToast('注册成功')
    emit('success')
    onClose()
  } catch (e) {
    const rawMsg = e?.response?.data?.message || e?.message || ''
    let toastMsg = '注册失败，请稍后再试'
    // 将后端“用户名不可重复/邮箱已被使用/手机号已被使用”映射为更清晰的提示，并标注到对应字段
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
    showToast(toastMsg)
  } finally {
    submitting.value = false
  }
}

function onClose() {
  emit('close')
}
</script>

<style scoped>
</style>
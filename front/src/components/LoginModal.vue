<template>
  <div class="fixed inset-0 z-50 flex items-center justify-center">
    <div class="absolute inset-0 bg-black/30 motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none" @click="onClose"></div>
    <div class="relative z-10 w-full max-w-md rounded-lg border border-gray-200 bg-white shadow-xl dark:bg-gray-800 dark:border-gray-700 motion-safe:transition-colors motion-safe:transition-opacity motion-safe:duration-300 motion-reduce:transition-none">
      <div class="flex items-center justify-between border-b border-gray-200 px-4 py-3 dark:border-gray-700">
        <h3 class="text-base font-semibold">登录 MagicAlbum</h3>
        <button class="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="onClose" aria-label="关闭">✕</button>
      </div>

      <form class="px-4 py-4 space-y-3" @submit.prevent="onSubmit">
        <!-- 手机号登录 -->
        <div v-if="mode==='phone'">
          <label class="block text-sm font-medium mb-1">手机号</label>
          <input
            v-model.trim="form.phone"
            type="tel"
            placeholder="请输入手机号"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.phone" class="mt-1 text-xs text-red-600">{{ errors.phone }}</p>
        </div>

        <!-- 邮箱登录 -->
        <div v-else-if="mode==='email'">
          <label class="block text-sm font-medium mb-1">邮箱</label>
          <input
            v-model.trim="form.email"
            type="email"
            placeholder="name@example.com"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.email" class="mt-1 text-xs text-red-600">{{ errors.email }}</p>
        </div>

        <!-- 密码 -->
        <div>
          <label class="block text-sm font-medium mb-1">密码</label>
          <input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
            class="w-full rounded-md border border-gray-300 bg-white px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:border-brandDay-600 focus:ring-brandDay-600 dark:bg-gray-800 dark:border-gray-700 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400"
          />
          <p v-if="errors.password" class="mt-1 text-xs text-red-600">{{ errors.password }}</p>
        </div>

        <!-- 底部其他登录方式 -->
        <div class="flex items-center justify-between text-xs text-brandDay-600 dark:text-accentCyan-400">
          <button v-if="mode==='phone'" type="button" class="hover:underline" @click="onEmailLogin">邮箱登录</button>
          <button v-else type="button" class="hover:underline" @click="onPhoneLogin">手机号登录</button>
          <button type="button" class="hover:underline" @click="onPhoneCodeLogin">手机验证码登录</button>
        </div>

        <!-- 提交按钮 -->
        <div class="pt-2 flex items-center justify-end gap-2">
          <button type="button" class="rounded px-3 py-2 text-sm hover:bg-gray-100 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="onClose">取消</button>
          <button
            type="submit"
            class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-2 text-sm text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 disabled:opacity-60 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
            :disabled="submitting"
          >
            {{ submitting ? '登录中…' : '登录' }}
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
import { useAuth } from '@/composables/useAuth'

const emit = defineEmits(['close', 'success'])

const mode = ref('phone')
const form = reactive({
  phone: '',
  email: '',
  password: '',
})
const errors = reactive({ phone: '', email: '', password: '' })
const submitting = ref(false)
const toast = ref('')

function showToast(msg) {
  toast.value = msg
  setTimeout(() => (toast.value = ''), 2000)
}

function resetErrors() {
  errors.phone = ''
  errors.email = ''
  errors.password = ''
}

function validatePhone() {
  if (!form.phone) {
    errors.phone = '请输入手机号'
    return false
  }
  const ok = /^1\d{10}$/.test(form.phone)
  if (!ok) {
    errors.phone = '手机号格式不正确'
    return false
  }
  errors.phone = ''
  return true
}

function validatePassword() {
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

function validateEmail() {
  if (!form.email) {
    errors.email = '请输入邮箱'
    return false
  }
  const ok = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email)
  if (!ok) {
    errors.email = '邮箱格式不正确'
    return false
  }
  errors.email = ''
  return true
}

async function onSubmit() {
  resetErrors()
  let okUser = false
  if (mode.value === 'phone') {
    okUser = validatePhone()
  } else {
    okUser = validateEmail()
  }
  const okPwd = validatePassword()
  if (!okUser || !okPwd) {
    showToast('请完成必填项并修正错误')
    return
  }

  submitting.value = true
  try {
    const auth = useAuth()
    if (mode.value === 'phone') {
      await auth.loginWithPhonePassword({ phone: form.phone, password: form.password })
    } else {
      await auth.loginWithEmailPassword({ email: form.email, password: form.password })
    }
    showToast('登录成功')
    emit('success')
    onClose()
  } catch (e) {
    const msg = e?.response?.data?.message || e?.message || '登录失败，请稍后再试'
    showToast(msg)
  } finally {
    submitting.value = false
  }
}

function onEmailLogin() {
  mode.value = 'email'
}

function onPhoneLogin() {
  mode.value = 'phone'
}

function onPhoneCodeLogin() {
  showToast('手机验证码登录暂未实现')
}

function onClose() {
  emit('close')
}
</script>

<style scoped>
</style>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { DisconnectAccountProof } from '@/api/connected'
import { isValidEmail, isValidPhone } from '@/utils/validators'

const props = defineProps<{ pending: boolean; error: string }>()
const emit = defineEmits<{
  submit: [proof: Omit<DisconnectAccountProof, 'bindingTag'>]
  cancel: []
  refresh: []
  edit: []
}>()

const identifier = ref('')
const password = ref('')
const validationError = ref('')
const shownError = computed(() => validationError.value || props.error)

function onInput(): void {
  validationError.value = ''
  emit('edit')
}

function submit(): void {
  if (props.pending) return
  const raw = identifier.value.trim()
  const normalized = raw.includes('@') ? raw.toLowerCase() : raw
  if (!isValidEmail(normalized) && !isValidPhone(normalized)) {
    validationError.value = '请输入用于密码登录的邮箱或手机号。'
    return
  }
  if (password.value.length < 8) {
    validationError.value = '请输入用于登录的当前密码。'
    return
  }
  const currentPassword = password.value
  password.value = ''
  validationError.value = ''
  emit('submit', { identifier: normalized, currentPassword })
}

onBeforeUnmount(() => {
  identifier.value = ''
  password.value = ''
})
</script>

<template>
  <form id="connected-disconnect-form" class="mt-4 max-w-xl space-y-3" :aria-busy="pending" @submit.prevent="submit">
    <p class="text-sm leading-6">解绑后，使用该第三方账号登录将不再进入当前账号，可能创建新账号。</p>
    <div>
      <label for="connected-disconnect-identifier" class="mb-1 block font-medium">本地登录邮箱或手机号</label>
      <input id="connected-disconnect-identifier" v-model.trim="identifier" class="ui-field w-full"
             autocomplete="username" maxlength="255" :disabled="pending"
             :aria-describedby="shownError ? 'connected-disconnect-error' : undefined" @input="onInput" />
    </div>
    <div>
      <label for="connected-disconnect-password" class="mb-1 block font-medium">当前本地登录密码</label>
      <input id="connected-disconnect-password" v-model="password" class="ui-field w-full" type="password"
             autocomplete="current-password" maxlength="1024" :disabled="pending"
             :aria-describedby="shownError ? 'connected-disconnect-error' : undefined" @input="onInput" />
    </div>
    <p v-if="shownError" id="connected-disconnect-error" class="ui-error text-sm" role="alert" aria-live="assertive">{{ shownError }}</p>
    <div class="flex flex-wrap gap-2">
      <button type="submit" class="ui-button-primary min-h-11 px-3 disabled:opacity-60" :disabled="pending">
        {{ pending ? '正在验证…' : '确认解绑' }}
      </button>
      <button type="button" class="ui-button min-h-11 px-3" :disabled="pending" @click="emit('cancel')">取消</button>
      <button v-if="error" type="button" class="ui-button min-h-11 px-3" :disabled="pending" @click="emit('refresh')">刷新关联状态</button>
    </div>
  </form>
</template>

<template>
  <div class="rounded-lg border border-gray-200 bg-gray-50 px-3 py-3 dark:border-gray-700 dark:bg-gray-900/40">
    <div class="flex items-start justify-between gap-3">
      <div>
        <div class="text-sm font-medium">人工验证</div>
        <p class="mt-1 text-xs text-gray-500 dark:text-gray-400">
          {{ verifyHint }}
        </p>
      </div>
      <span
        class="shrink-0 rounded-full px-2 py-0.5 text-[11px]"
        :class="statusClass"
      >
        {{ statusLabel }}
      </span>
    </div>

    <div v-if="isTurnstileMode" class="mt-3 space-y-2">
      <div
        ref="turnstileContainerRef"
        class="min-h-[68px] rounded-xl border border-gray-200 bg-white px-2 py-2 dark:border-gray-700 dark:bg-gray-950"
      ></div>
      <div class="flex items-center gap-2">
        <button
          v-if="status === 'verified' || status === 'failed' || status === 'expired'"
          type="button"
          class="rounded px-3 py-1.5 text-sm hover:bg-gray-100 dark:hover:bg-gray-700"
          @click="resetState"
        >
          重新验证
        </button>
      </div>
    </div>

    <div v-else class="mt-3 flex items-center gap-2">
      <button
        type="button"
        class="rounded px-3 py-1.5 text-sm shadow-sm focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400"
        :class="verified ? 'bg-emerald-600 text-white hover:bg-emerald-700' : isMockVerifyEnabled() ? 'bg-brandDay-600 text-white hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-700' : 'bg-gray-200 text-gray-500 cursor-not-allowed dark:bg-gray-800 dark:text-gray-400'"
        :disabled="status === 'verifying' || !isMockVerifyEnabled()"
        @click="startManualVerify"
      >
        {{ verified ? '已验证' : status === 'verifying' ? '验证中…' : isMockVerifyEnabled() ? '完成人工验证' : '暂未开放' }}
      </button>
      <button
        v-if="verified"
        type="button"
        class="rounded px-3 py-1.5 text-sm hover:bg-gray-100 dark:hover:bg-gray-700"
        @click="resetState"
      >
        重新验证
      </button>
    </div>

    <p v-if="message" class="mt-2 text-xs" :class="messageClass">{{ message }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  createVerifyResult,
  getAuthVerifyMode,
  getTurnstileSiteKey,
  getVerifyToken,
  isMockVerifyEnabled,
  isTurnstileVerifyEnabled,
  isVerified,
  resetVerify,
  startVerify,
  toVerifyPayload,
  type AuthVerifyResult,
  type AuthVerifyStatus,
} from '@/services/authVerify'
import type { AuthVerifyPayload, AuthVerifyScene } from '@/types'

const TURNSTILE_SCRIPT_ID = 'cf-turnstile-script'
const TURNSTILE_SCRIPT_SRC = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

let turnstileScriptPromise: Promise<void> | null = null

const props = withDefaults(defineProps<{
  scene?: AuthVerifyScene
}>(), {
  scene: 'login',
})

const emit = defineEmits<{
  verified: [payload: AuthVerifyResult]
  reset: []
}>()

const mode = getAuthVerifyMode()
const isTurnstileMode = isTurnstileVerifyEnabled()
const turnstileSiteKey = getTurnstileSiteKey()

const status = ref<AuthVerifyStatus>('idle')
const result = ref<AuthVerifyResult | null>(null)
const message = ref('')
const turnstileContainerRef = ref<HTMLDivElement | null>(null)
const widgetId = ref<string | null>(null)

const verifyHint = computed(() => {
  if (isTurnstileMode) {
    return turnstileSiteKey
      ? '请完成 Cloudflare Turnstile 验证后再继续提交。'
      : '当前环境尚未配置 Turnstile site key。'
  }
  return isMockVerifyEnabled()
    ? '当前为阶段二占位接入。提交前需要先完成一次人工验证，后续会在这里替换成真实验证服务。'
    : '当前环境未启用人工验证。'
})

const verified = computed(() => isVerified(result.value))

const statusLabel = computed(() => {
  if (status.value === 'verified') return '已通过'
  if (status.value === 'verifying') return '验证中'
  if (status.value === 'expired') return '已过期'
  if (status.value === 'unavailable') return '不可用'
  if (status.value === 'failed') return '未通过'
  return '未验证'
})

const statusClass = computed(() => {
  if (status.value === 'verified') return 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
  if (status.value === 'verifying') return 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300'
  if (status.value === 'expired') return 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
  if (status.value === 'unavailable') return 'bg-gray-200 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
  if (status.value === 'failed') return 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300'
  return 'bg-gray-200 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
})

const messageClass = computed(() => {
  return status.value === 'failed' || status.value === 'expired' || status.value === 'unavailable'
    ? 'text-red-600'
    : 'text-gray-500 dark:text-gray-400'
})

function loadTurnstileScript(): Promise<void> {
  if (window.turnstile) return Promise.resolve()
  if (turnstileScriptPromise) return turnstileScriptPromise

  turnstileScriptPromise = new Promise((resolve, reject) => {
    const existing = document.getElementById(TURNSTILE_SCRIPT_ID) as HTMLScriptElement | null
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true })
      existing.addEventListener('error', () => reject(new Error('Turnstile script load failed')), { once: true })
      return
    }

    const script = document.createElement('script')
    script.id = TURNSTILE_SCRIPT_ID
    script.src = TURNSTILE_SCRIPT_SRC
    script.async = true
    script.defer = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Turnstile script load failed'))
    document.head.appendChild(script)
  })

  return turnstileScriptPromise
}

function resetTurnstileWidget(): void {
  if (widgetId.value && window.turnstile) {
    window.turnstile.reset(widgetId.value)
  }
}

function removeTurnstileWidget(): void {
  if (widgetId.value && window.turnstile) {
    window.turnstile.remove(widgetId.value)
    widgetId.value = null
  }
}

async function renderTurnstileWidget(): Promise<void> {
  if (!isTurnstileMode) return
  if (!turnstileSiteKey) {
    status.value = 'unavailable'
    message.value = '未配置 Turnstile site key，当前无法启用真实人工验证。'
    return
  }

  await nextTick()
  if (!turnstileContainerRef.value) return

  status.value = 'verifying'
  message.value = '请完成验证后再继续提交。'

  try {
    await loadTurnstileScript()
    removeTurnstileWidget()

    widgetId.value = window.turnstile?.render(turnstileContainerRef.value, {
      sitekey: turnstileSiteKey,
      action: props.scene,
      theme: 'auto',
      size: 'flexible',
      callback: (token: string) => {
        const verifyResult = createVerifyResult({
          token,
          provider: 'turnstile',
          scene: props.scene,
        })
        result.value = verifyResult
        status.value = 'verified'
        message.value = '验证已完成，可以继续提交。'
        emit('verified', verifyResult)
      },
      'error-callback': () => {
        result.value = null
        status.value = 'failed'
        message.value = 'Turnstile 验证失败，请重试。'
      },
      'expired-callback': () => {
        result.value = null
        status.value = 'expired'
        message.value = '验证已过期，请重新完成验证。'
      },
    }) || null
  } catch {
    status.value = 'unavailable'
    message.value = 'Turnstile 加载失败，请稍后重试。'
  }
}

async function startManualVerify(): Promise<AuthVerifyResult | null> {
  if (!isMockVerifyEnabled()) {
    status.value = 'idle'
    message.value = '当前验证模式不支持手动验证。'
    return null
  }
  if (verified.value && result.value) return result.value

  status.value = 'verifying'
  message.value = '正在完成人工验证占位流程…'
  try {
    const verifyResult = await startVerify({ scene: props.scene })
    result.value = verifyResult
    status.value = 'verified'
    message.value = `验证完成，票据已就绪：${getVerifyToken(verifyResult).slice(0, 18)}...`
    emit('verified', verifyResult)
    return verifyResult
  } catch {
    status.value = 'failed'
    message.value = '人工验证未完成，请重试'
    return null
  }
}

function resetState(): void {
  resetVerify()
  result.value = null
  status.value = 'idle'
  message.value = ''

  if (isTurnstileMode) {
    resetTurnstileWidget()
    status.value = 'verifying'
    message.value = '请完成验证后再继续提交。'
  }

  emit('reset')
}

async function ensureVerified(): Promise<AuthVerifyResult | null> {
  if (verified.value && result.value) return result.value

  if (isTurnstileMode) {
    if (!widgetId.value) {
      await renderTurnstileWidget()
    }
    if (!verified.value) {
      message.value = turnstileSiteKey ? '请先完成 Turnstile 验证。' : '当前未配置 Turnstile site key。'
      if (!turnstileSiteKey) status.value = 'unavailable'
      return null
    }
    return result.value
  }

  return startManualVerify()
}

onMounted(() => {
  if (mode === 'turnstile') {
    void renderTurnstileWidget()
  }
})

onBeforeUnmount(() => {
  removeTurnstileWidget()
})

defineExpose({
  ensureVerified,
  reset: resetState,
  isVerified: () => verified.value,
  getVerifyResult: () => result.value,
  getVerifyPayload: (): AuthVerifyPayload => toVerifyPayload(result.value),
})
</script>

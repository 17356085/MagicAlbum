<template>
  <div class="border border-gray-200 bg-[var(--ui-subtle)] p-4 dark:border-gray-700">
    <div class="flex items-start justify-between gap-3">
      <div>
        <div class="text-sm font-semibold">人工验证</div>
        <p class="ui-muted mt-1 text-xs leading-5">
          {{ verifyHint }}
        </p>
      </div>
      <span
        class="ui-tag shrink-0 text-[11px]"
        :class="statusClass"
      >
        {{ statusLabel }}
      </span>
    </div>

    <div v-if="isTurnstileMode" class="mt-3 space-y-2">
      <div
        ref="turnstileContainerRef"
        class="min-h-[68px] rounded-lg border border-gray-200 bg-[var(--ui-surface)] px-2 py-2 dark:border-gray-700"
      ></div>
      <div class="flex items-center gap-2">
        <button
          v-if="status === 'verified' || status === 'failed' || status === 'expired' || (status === 'unavailable' && turnstileSiteKey)"
          type="button"
          class="ui-button px-3 py-1.5 text-xs"
          @click="resetState"
        >
          重新验证
        </button>
      </div>
    </div>

    <div v-else class="mt-3 flex items-center gap-2">
      <button
        type="button"
        class="ui-button px-3 py-1.5 text-xs"
        :class="verified ? 'bg-emerald-600 text-white hover:bg-emerald-700' : isMockVerifyEnabled() ? 'bg-brandDay-600 text-white hover:bg-brandDay-700 dark:bg-brandNight-600 dark:hover:bg-brandNight-700' : 'bg-gray-200 text-gray-500 cursor-not-allowed dark:bg-gray-800 dark:text-gray-400'"
        :disabled="status === 'verifying' || !isMockVerifyEnabled()"
        @click="startManualVerify"
      >
        {{ verified ? '已验证' : status === 'verifying' ? '验证中…' : isMockVerifyEnabled() ? '完成人工验证' : '暂未开放' }}
      </button>
      <button
        v-if="verified"
        type="button"
        class="ui-button px-3 py-1.5 text-xs"
        @click="resetState"
      >
        重新验证
      </button>
    </div>

    <p v-if="message" class="mt-2 text-xs" :class="messageClass">{{ message }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRaw } from 'vue'
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
let disposed = false
let lifecycleGeneration = 0
let turnstileRenderPromise: Promise<void> | null = null
let turnstileRenderGeneration: number | null = null
let manualVerification: { generation: number; promise: Promise<AuthVerifyResult | null> } | null = null

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

  let existingScript: HTMLScriptElement | null = null
  const pendingScriptPromise = new Promise<void>((resolve, reject) => {
    existingScript = document.getElementById(TURNSTILE_SCRIPT_ID) as HTMLScriptElement | null
    if (existingScript) {
      existingScript.addEventListener('load', () => resolve(), { once: true })
      existingScript.addEventListener('error', () => reject(new Error('Turnstile script load failed')), { once: true })
      return
    }

    const script = document.createElement('script')
    script.id = TURNSTILE_SCRIPT_ID
    script.src = TURNSTILE_SCRIPT_SRC
    script.async = true
    script.defer = true
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Turnstile script load failed'))
    existingScript = script
    document.head.appendChild(script)
  })
  let trackedScriptPromise!: Promise<void>
  trackedScriptPromise = pendingScriptPromise.catch((error: unknown) => {
    if (turnstileScriptPromise === trackedScriptPromise) {
      turnstileScriptPromise = null
      if (existingScript && !window.turnstile) existingScript.remove()
    }
    throw error
  })
  turnstileScriptPromise = trackedScriptPromise

  return turnstileScriptPromise
}

function isLifecycleCurrent(generation: number): boolean {
  return !disposed && lifecycleGeneration === generation
}

function removeTurnstileWidget(): void {
  if (widgetId.value && window.turnstile) {
    window.turnstile.remove(widgetId.value)
  }
  widgetId.value = null
}

function renderTurnstileWidget(): Promise<void> {
  if (!isTurnstileMode) return Promise.resolve()
  const generation = lifecycleGeneration
  if (!isLifecycleCurrent(generation)) return Promise.resolve()
  if (!turnstileSiteKey) {
    status.value = 'unavailable'
    message.value = '未配置 Turnstile site key，当前无法启用真实人工验证。'
    return Promise.resolve()
  }

  if (widgetId.value) return Promise.resolve()
  if (turnstileRenderPromise && turnstileRenderGeneration === generation) {
    return turnstileRenderPromise
  }

  const renderPromise = (async () => {
    await nextTick()
    if (!isLifecycleCurrent(generation) || !turnstileContainerRef.value) return

    status.value = 'verifying'
    message.value = '请完成验证后再继续提交。'

    try {
      await loadTurnstileScript()
      if (!isLifecycleCurrent(generation) || !turnstileContainerRef.value) return

      removeTurnstileWidget()
      const turnstile = window.turnstile
      if (!turnstile) {
        status.value = 'unavailable'
        message.value = 'Turnstile 加载失败，请稍后重试。'
        return
      }

      widgetId.value = turnstile.render(turnstileContainerRef.value, {
        sitekey: turnstileSiteKey,
        action: props.scene,
        theme: 'auto',
        size: 'flexible',
        callback: (token: string) => {
          if (!isLifecycleCurrent(generation)) return
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
          if (!isLifecycleCurrent(generation)) return
          result.value = null
          status.value = 'failed'
          message.value = 'Turnstile 验证失败，请重试。'
        },
        'expired-callback': () => {
          if (!isLifecycleCurrent(generation)) return
          result.value = null
          status.value = 'expired'
          message.value = '验证已过期，请重新完成验证。'
        },
      }) || null
    } catch {
      if (!isLifecycleCurrent(generation)) return
      status.value = 'unavailable'
      message.value = 'Turnstile 加载失败，请稍后重试。'
    }
  })()

  turnstileRenderGeneration = generation
  turnstileRenderPromise = renderPromise
  renderPromise.then(
    () => {
      if (turnstileRenderPromise === renderPromise) {
        turnstileRenderPromise = null
        turnstileRenderGeneration = null
      }
    },
    () => {
      if (turnstileRenderPromise === renderPromise) {
        turnstileRenderPromise = null
        turnstileRenderGeneration = null
      }
    },
  )
  return renderPromise
}

async function startManualVerify(): Promise<AuthVerifyResult | null> {
  const generation = lifecycleGeneration
  if (!isLifecycleCurrent(generation)) return null
  if (!isMockVerifyEnabled()) {
    status.value = 'idle'
    message.value = '当前验证模式不支持手动验证。'
    return null
  }
  if (verified.value && result.value) return result.value
  if (manualVerification?.generation === generation) return manualVerification.promise

  status.value = 'verifying'
  message.value = '正在完成人工验证占位流程…'
  const pending = (async (): Promise<AuthVerifyResult | null> => {
    try {
      const verifyResult = await startVerify({ scene: props.scene })
      if (!isLifecycleCurrent(generation)) return null
      result.value = verifyResult
      status.value = 'verified'
      message.value = `验证完成，票据已就绪：${getVerifyToken(verifyResult).slice(0, 18)}...`
      emit('verified', verifyResult)
      return verifyResult
    } catch {
      if (!isLifecycleCurrent(generation)) return null
      status.value = 'failed'
      message.value = '人工验证未完成，请重试'
      return null
    }
  })()
  manualVerification = { generation, promise: pending }
  try {
    return await pending
  } finally {
    if (manualVerification?.promise === pending) manualVerification = null
  }
}

function resetState(): void {
  if (disposed) return
  lifecycleGeneration += 1
  resetVerify()
  result.value = null
  status.value = 'idle'
  message.value = ''

  if (isTurnstileMode) {
    removeTurnstileWidget()
    status.value = 'verifying'
    message.value = '请完成验证后再继续提交。'
    void renderTurnstileWidget()
  }

  emit('reset')
}

function resetIfMatches(expected: AuthVerifyResult): boolean {
  const current = result.value
  if (
    disposed ||
    !current ||
    toRaw(current) !== toRaw(expected) ||
    current.token !== expected.token ||
    current.provider !== expected.provider ||
    current.scene !== expected.scene
  ) {
    return false
  }
  resetState()
  return true
}

async function ensureVerified(): Promise<AuthVerifyResult | null> {
  const generation = lifecycleGeneration
  if (!isLifecycleCurrent(generation)) return null
  if (verified.value && result.value) return result.value

  if (isTurnstileMode) {
    if (!widgetId.value) await renderTurnstileWidget()
    if (!isLifecycleCurrent(generation)) return null
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
  disposed = true
  lifecycleGeneration += 1
  removeTurnstileWidget()
})

defineExpose({
  ensureVerified,
  reset: resetState,
  resetIfMatches,
  isVerified: () => verified.value,
  getVerifyResult: () => result.value,
  getVerifyPayload: (): AuthVerifyPayload => toVerifyPayload(result.value),
})
</script>

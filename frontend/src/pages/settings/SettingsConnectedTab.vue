<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listConnectedAccounts } from '@/api/connected'
import type { ConnectedAccount } from '@/types'

interface ConnectedAccountsState {
  items: ConnectedAccount[]
}

const connected = ref<ConnectedAccountsState>({ items: [] })
const connectedLoading = ref(false)
const connectedError = ref('')

async function loadConnected(): Promise<void> {
  connectedLoading.value = true
  connectedError.value = ''
  try {
    const data = await listConnectedAccounts()
    connected.value = { items: Array.isArray(data?.items) ? data.items : [] }
  } catch (_) {
    connectedError.value = '关联状态加载失败，暂时无法确认账号状态。'
  } finally {
    connectedLoading.value = false
  }
}

function providerLabel(provider: string): string {
  const labels: Record<string, string> = {
    google: 'Google',
    github: 'GitHub',
    apple: 'Apple',
  }
  return labels[provider.toLowerCase()] || provider
}

function statusLabel(account: ConnectedAccount): string {
  return account.connected === true ? '已关联（后端已确认）' : '未关联（本地模式不可用）'
}

onMounted(() => {
  void loadConnected()
})
</script>

<template>
  <section aria-labelledby="connected-settings-title">
    <h2 id="connected-settings-title" class="text-sm font-semibold text-gray-900 dark:text-gray-50">第三方关联</h2>
    <p class="mt-2 rounded-md border border-brandDay-100 bg-brandDay-50/60 p-3 text-xs leading-5 text-gray-600 dark:border-brandNight-700 dark:bg-brandNight-900/20 dark:text-gray-300">
      当前为本地模式，第三方账号的绑定与解绑流程尚未接入，因此这里不会提供操作按钮。下方状态仅展示后端已返回的关联结果。
    </p>
    <div class="mt-3">
      <div v-if="connectedLoading" class="text-xs text-gray-500" role="status" aria-live="polite">正在加载关联状态...</div>
      <div v-else-if="connectedError" class="flex items-center justify-between gap-3 rounded-md border border-red-200 bg-red-50 p-3 text-xs text-red-700 dark:border-red-900/70 dark:bg-red-950/30 dark:text-red-200" role="alert">
        <span>{{ connectedError }}</span>
        <button type="button" class="shrink-0 rounded-md border border-red-300 px-3 py-1.5 font-medium hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-red-400 dark:border-red-800 dark:hover:bg-red-900/40" @click="loadConnected">
          重试
        </button>
      </div>
      <div v-else-if="!(connected.items || []).length" class="rounded-md border border-dashed border-gray-200 p-4 text-center text-xs text-gray-500 dark:border-gray-700">
        暂无已关联账号。当前模式不支持发起绑定。
      </div>
      <ul v-else class="space-y-2 text-xs" aria-label="已关联账号状态">
        <li v-for="account in connected.items || []" :key="account.provider" class="flex flex-col gap-3 rounded-md border border-gray-100 p-3 dark:border-gray-700 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div class="text-sm font-medium text-gray-900 dark:text-gray-50">{{ providerLabel(account.provider) }}</div>
            <div class="mt-1 text-xs text-gray-500 dark:text-gray-400">{{ statusLabel(account) }}</div>
          </div>
          <span :class="account.connected === true ? 'bg-brandDay-50 text-brandDay-700 dark:bg-brandNight-900/40 dark:text-brandNight-300' : 'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-300'" class="w-fit rounded-full px-2 py-0.5 text-[11px]">
            {{ account.connected === true ? '已关联' : '未关联' }}
          </span>
        </li>
      </ul>
    </div>
  </section>
</template>

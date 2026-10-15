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
  <section aria-labelledby="connected-settings-title" class="space-y-4">
    <h2 id="connected-settings-title" class="text-base font-semibold">第三方关联</h2>
    <p class="ui-muted border border-gray-200 bg-[var(--ui-subtle)] p-4 text-sm leading-6 dark:border-gray-700">
      当前为本地模式，第三方账号的绑定与解绑流程尚未接入，因此这里不会提供操作按钮。下方状态仅展示后端已返回的关联结果。
    </p>
    <div>
      <div v-if="connectedLoading" class="ui-muted text-sm" role="status" aria-live="polite">正在加载关联状态...</div>
      <div v-else-if="connectedError" class="ui-error flex items-center justify-between gap-3 text-sm" role="alert">
        <span>{{ connectedError }}</span>
        <button type="button" class="ui-button shrink-0 px-3 py-1.5 text-xs" @click="loadConnected">
          重试
        </button>
      </div>
      <div v-else-if="!(connected.items || []).length" class="ui-empty border border-dashed border-gray-200 dark:border-gray-700">
        暂无已关联账号。当前模式不支持发起绑定。
      </div>
      <ul v-else class="divide-y divide-gray-200 text-sm dark:divide-gray-700" aria-label="已关联账号状态">
        <li v-for="account in connected.items || []" :key="account.provider" class="ui-list-row flex flex-col gap-3 px-0 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div class="font-medium">{{ providerLabel(account.provider) }}</div>
            <div class="ui-muted mt-1 text-xs">{{ statusLabel(account) }}</div>
          </div>
          <span :class="account.connected === true ? 'text-[var(--ui-accent)]' : 'ui-muted'" class="ui-tag w-fit text-[11px]">
            {{ account.connected === true ? '已关联' : '未关联' }}
          </span>
        </li>
      </ul>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { connectAccount, disconnectAccount, listConnectedAccounts } from '@/api/connected'
import type { ConnectedAccount } from '@/types'

interface ConnectedAccountsState {
  items: ConnectedAccount[]
}

const connected = ref<ConnectedAccountsState>({ items: [] })
const connectedLoading = ref(false)
const connectedMessage = ref('')
const connectedMessageError = ref(false)

function setConnectedMessage(message: string, isError = false): void {
  connectedMessage.value = message
  connectedMessageError.value = isError
  setTimeout(() => {
    connectedMessage.value = ''
    connectedMessageError.value = false
  }, isError ? 4000 : 3000)
}

async function loadConnected(): Promise<void> {
  connectedLoading.value = true
  try {
    const data = await listConnectedAccounts()
    connected.value = data || { items: [] }
  } catch (_) {
    connected.value = { items: [] }
  } finally {
    connectedLoading.value = false
  }
}

async function onConnect(provider: string): Promise<void> {
  try {
    await connectAccount(provider)
    await loadConnected()
    setConnectedMessage('绑定状态已更新')
  } catch (_) {
    setConnectedMessage('绑定失败', true)
  }
}

async function onDisconnect(provider: string): Promise<void> {
  try {
    await disconnectAccount(provider)
    await loadConnected()
    setConnectedMessage('绑定状态已更新')
  } catch (_) {
    setConnectedMessage('解绑失败', true)
  }
}

onMounted(() => {
  void loadConnected()
})
</script>

<template>
  <section>
    <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">第三方关联</h2>
      <span v-if="connectedMessage" :class="['text-xs', connectedMessageError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ connectedMessage }}</span>
    </div>
    <div class="mt-3">
      <div v-if="connectedLoading" class="text-xs text-gray-500">正在加载...</div>
      <ul v-else class="space-y-2 text-xs">
        <li v-for="account in connected.items || []" :key="account.provider" class="flex flex-col gap-3 rounded-md border border-gray-100 p-3 dark:border-gray-700 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div class="text-sm font-medium text-gray-900 dark:text-gray-50">{{ account.provider }}</div>
            <div class="mt-1 text-xs text-gray-500">{{ account.connected ? '已绑定' : '未绑定' }}</div>
          </div>
          <div class="flex items-center gap-2">
            <button v-if="!account.connected" class="rounded-md border border-gray-200 px-3 py-1.5 text-xs font-medium hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700" @click="onConnect(account.provider)">绑定</button>
            <button v-else class="rounded-md border border-gray-200 px-3 py-1.5 text-xs font-medium hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700" @click="onDisconnect(account.provider)">解绑</button>
          </div>
        </li>
        <li v-if="!(connected.items || []).length" class="rounded-md border border-dashed border-gray-200 p-4 text-center text-xs text-gray-500 dark:border-gray-700">
          暂无可绑定账号
        </li>
      </ul>
    </div>
  </section>
</template>

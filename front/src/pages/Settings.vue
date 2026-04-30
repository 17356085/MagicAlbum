<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import SettingsAccount from '@/pages/SettingsAccount.vue'
import SettingsConnectedTab from '@/pages/settings/SettingsConnectedTab.vue'
import SettingsNotificationsTab from '@/pages/settings/SettingsNotificationsTab.vue'
import SettingsProfileTab from '@/pages/settings/SettingsProfileTab.vue'

type SettingsTab = 'profile' | 'notifications' | 'connected' | 'account'

const selectedTab = ref<SettingsTab>('profile')
const route = useRoute()

const tabs: Array<{ key: SettingsTab; label: string }> = [
  { key: 'profile', label: '资料设置' },
  { key: 'notifications', label: '通知管理' },
  { key: 'connected', label: '第三方关联' },
  { key: 'account', label: '账号信息' },
]

function normalizeTab(value: unknown): SettingsTab {
  const raw = Array.isArray(value) ? value[0] : value
  return tabs.some((tab) => tab.key === raw) ? raw as SettingsTab : 'profile'
}

watch(
  () => route.query.tab,
  (tab) => {
    selectedTab.value = normalizeTab(tab)
  },
  { immediate: true },
)
</script>

<template>
  <div class="rounded-md border border-gray-200 bg-white p-2 shadow-sm dark:border-gray-700 dark:bg-gray-800">
    <div>
      <div class="overflow-x-auto">
        <div class="flex min-w-max gap-1 rounded-md bg-gray-50 p-1 dark:bg-gray-900/40">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            class="rounded px-4 py-2 text-sm font-medium transition-colors"
            :class="selectedTab === tab.key ? 'bg-white text-brandDay-700 shadow-sm dark:bg-gray-800 dark:text-brandNight-300' : 'text-gray-600 hover:bg-white/70 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-800/70 dark:hover:text-gray-50'"
            @click="selectedTab = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-2 pb-2 pt-4">
      <SettingsProfileTab v-if="selectedTab === 'profile'" />
      <SettingsNotificationsTab v-else-if="selectedTab === 'notifications'" />
      <SettingsConnectedTab v-else-if="selectedTab === 'connected'" />

      <SettingsAccount v-else />
    </div>
  </div>
</template>

<style scoped>
</style>

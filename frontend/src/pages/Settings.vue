<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import SettingsAccount from '@/pages/SettingsAccount.vue'
import SettingsConnectedTab from '@/pages/settings/SettingsConnectedTab.vue'
import SettingsNotificationsTab from '@/pages/settings/SettingsNotificationsTab.vue'
import SettingsProfileTab from '@/pages/settings/SettingsProfileTab.vue'

type SettingsTab = 'profile' | 'notifications' | 'connected' | 'account'

const route = useRoute()
const router = useRouter()

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

const selectedTab = computed<SettingsTab>(() => normalizeTab(route.query.tab))
const dirtyTabs = reactive<Record<SettingsTab, boolean>>({
  profile: false,
  notifications: false,
  connected: false,
  account: false,
})
const hasDirtyDraft = computed(() => tabs.some((tab) => dirtyTabs[tab.key]))

function setTabDirty(tab: SettingsTab, dirty: boolean): void {
  dirtyTabs[tab] = dirty
}

function getDirtyTabs(): SettingsTab[] {
  return tabs.filter((tab) => dirtyTabs[tab.key]).map((tab) => tab.key)
}

function confirmDiscardIfNeeded(tabKeys = getDirtyTabs()): boolean {
  const dirtyKeys = tabKeys.filter((key) => dirtyTabs[key])
  if (!dirtyKeys.length) return true

  const shouldDiscard = window.confirm('当前设置页有未保存的更改，确定放弃这些更改吗？')
  if (shouldDiscard) {
    dirtyKeys.forEach((key) => { dirtyTabs[key] = false })
  }
  return shouldDiscard
}

function selectTab(tab: SettingsTab): void {
  if (tab === selectedTab.value) return
  if (!confirmDiscardIfNeeded([selectedTab.value])) return

  void router.push({
    path: route.path,
    query: { ...route.query, tab },
  })
}

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!hasDirtyDraft.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteUpdate((to, from) => {
  const fromTab = normalizeTab(from.query.tab)
  const toTab = normalizeTab(to.query.tab)
  if (to.path === from.path && toTab === fromTab) return true
  return confirmDiscardIfNeeded(to.path === from.path ? [fromTab] : undefined)
})

onBeforeRouteLeave(() => confirmDiscardIfNeeded())

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
})

onUnmounted(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
})
</script>

<template>
  <div class="rounded-md border border-gray-200 bg-white p-2 shadow-sm dark:border-gray-700 dark:bg-gray-800">
    <div>
      <div class="overflow-x-auto">
        <div class="grid grid-cols-2 gap-1 rounded-md bg-gray-50 p-1 dark:bg-gray-900/40 md:flex md:min-w-max">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            class="min-w-0 rounded px-3 py-2 text-sm font-medium transition-colors md:px-4"
            :class="selectedTab === tab.key ? 'bg-white text-brandDay-700 shadow-sm dark:bg-gray-800 dark:text-brandNight-300' : 'text-gray-600 hover:bg-white/70 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-800/70 dark:hover:text-gray-50'"
            :aria-current="selectedTab === tab.key ? 'page' : undefined"
            @click="selectTab(tab.key)"
          >
            {{ tab.label }}
          </button>
        </div>
      </div>
    </div>

    <div class="px-2 pb-2 pt-4">
      <SettingsProfileTab v-if="selectedTab === 'profile'" @dirty-change="setTabDirty('profile', $event)" />
      <SettingsNotificationsTab v-else-if="selectedTab === 'notifications'" @dirty-change="setTabDirty('notifications', $event)" />
      <SettingsConnectedTab v-else-if="selectedTab === 'connected'" />

      <SettingsAccount v-else @dirty-change="setTabDirty('account', $event)" />
    </div>
  </div>
</template>

<style scoped>
</style>

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
  <div class="ui-page">
    <header class="ui-page-header">
      <div class="min-w-0">
        <h1 class="ui-title">设置</h1>
        <p class="ui-description">管理公开资料、通知偏好、关联状态与账号安全。</p>
      </div>
    </header>

    <div class="ui-panel overflow-hidden">
      <nav class="ui-toolbar" aria-label="设置导航">
        <div class="grid w-full grid-cols-2 gap-2 sm:flex sm:flex-wrap">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            type="button"
            class="settings-tab ui-button min-w-0 flex-1 whitespace-normal px-3 py-2 text-sm sm:flex-none sm:px-4"
            :aria-current="selectedTab === tab.key ? 'page' : undefined"
            @click="selectTab(tab.key)"
          >
            {{ tab.label }}
          </button>
        </div>

      </nav>

      <div class="p-4 sm:p-6">
        <SettingsProfileTab v-if="selectedTab === 'profile'" @dirty-change="setTabDirty('profile', $event)" />
        <SettingsNotificationsTab v-else-if="selectedTab === 'notifications'" @dirty-change="setTabDirty('notifications', $event)" />
        <SettingsConnectedTab v-else-if="selectedTab === 'connected'" />

        <SettingsAccount v-else @dirty-change="setTabDirty('account', $event)" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-tab[aria-current='page'] {
  border-color: var(--ui-accent);
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
}

.settings-tab:not([aria-current='page']):hover {
  border-color: var(--ui-border);
  background: var(--ui-subtle);
}
</style>

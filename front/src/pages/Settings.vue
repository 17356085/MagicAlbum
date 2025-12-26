<script setup>
import { ref, onMounted } from 'vue'
import { getMyProfile, updateMyProfile, getMySettings, updateMySettings } from '@/api/settings'
import { listNotifications, markNotificationRead, getNotificationSettings, updateNotificationSettings } from '@/api/notifications'
import { listConnectedAccounts, connectAccount, disconnectAccount } from '@/api/connected'
// 回溯到头像上传前：不再引入图片上传 API

const selectedTab = ref('profile') // 'profile' | 'notifications' | 'connected'

// Profile
const profile = ref({ nickname: '', bio: '', homepageUrl: '', location: '', links: [] })
const profileSaving = ref(false)
async function loadProfile() {
  try {
    profile.value = await getMyProfile()
  } catch (e) {}
}
async function saveProfile() {
  profileSaving.value = true
  try {
    const payload = {
      nickname: profile.value?.nickname || '',
      bio: profile.value?.bio || '',
      homepageUrl: profile.value?.homepageUrl || '',
      location: profile.value?.location || '',
      links: Array.isArray(profile.value?.links) ? profile.value.links : [],
    }
    const data = await updateMyProfile(payload)
    profile.value = data
    // 广播资料更新事件，供头部头像实时刷新
    try { window.dispatchEvent(new CustomEvent('profile-updated', { detail: data })) } catch {}
  } catch (e) {} finally { profileSaving.value = false }
}

// Settings (for future use)
const settings = ref(null)
async function loadSettings() {
  try { settings.value = await getMySettings() } catch (e) {}
}

// Notifications list and settings
const notifQuery = ref({ type: '', unread: false, page: 1, size: 20 })
const notifList = ref({ items: [], page: 1, size: 20, total: 0 })
const notifLoading = ref(false)
const notifError = ref('')
const notifSettings = ref({ inApp: { reply: true, mention: true, like: true, system: true }, email: { enabled: false, frequency: 'instant' } })

async function loadNotifications() {
  notifLoading.value = true
  notifError.value = ''
  try {
    const data = await listNotifications({ type: notifQuery.value.type || undefined, unread: notifQuery.value.unread, page: notifQuery.value.page, size: notifQuery.value.size })
    notifList.value = data
  } catch (e) {
    notifError.value = '加载通知失败'
  } finally { notifLoading.value = false }
}
async function loadNotificationSettings() {
  try { notifSettings.value = await getNotificationSettings() } catch (e) {}
}
async function saveNotificationSettings() {
  try {
    const data = await updateNotificationSettings(notifSettings.value)
    notifSettings.value = data
  } catch (e) {}
}
async function setNotificationRead(id) {
  try {
    await markNotificationRead(id)
    await loadNotifications()
  } catch (e) {}
}

// Connected accounts
const connected = ref({ items: [] })
const connectedLoading = ref(false)
async function loadConnected() {
  connectedLoading.value = true
  try {
    const data = await listConnectedAccounts()
    connected.value = data || { items: [] }
  } catch (e) {
    connected.value = { items: [] }
  } finally { connectedLoading.value = false }
}
async function onConnect(provider) {
  try { await connectAccount(provider); await loadConnected() } catch (e) {}
}
async function onDisconnect(provider) {
  try { await disconnectAccount(provider); await loadConnected() } catch (e) {}
}

onMounted(async () => {
  await loadProfile()
  await loadSettings()
  await loadNotifications()
  await loadNotificationSettings()
  await loadConnected()
})
</script>

<template>
  <div class="rounded-md border border-gray-200 bg-white p-4 dark:bg-gray-800 dark:border-gray-700">
    <div class="flex items-center gap-2 mb-3">
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='profile' ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='profile'">资料设置</button>
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='notifications' ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='notifications'">通知管理</button>
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='connected' ? 'bg-blue-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='connected'">第三方关联</button>
    </div>

    <div v-if="selectedTab==='profile'" class="space-y-3">
      <div class="text-sm">基本资料</div>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <label class="text-xs">昵称
          <input v-model="profile.nickname" class="mt-1 w-full rounded border px-2 py-1 text-sm dark:bg-gray-800 dark:border-gray-700" placeholder="昵称" />
        </label>
        <label class="text-xs">主页链接
          <input v-model="profile.homepageUrl" class="mt-1 w-full rounded border px-2 py-1 text-sm dark:bg-gray-800 dark:border-gray-700" placeholder="https://..." />
        </label>
        <label class="text-xs">所在地
          <input v-model="profile.location" class="mt-1 w-full rounded border px-2 py-1 text-sm dark:bg-gray-800 dark:border-gray-700" placeholder="城市" />
        </label>
        <label class="text-xs md:col-span-2">个人简介
          <textarea v-model="profile.bio" rows="4" class="mt-1 w-full rounded border px-2 py-1 text-sm dark:bg-gray-800 dark:border-gray-700" placeholder="简介"></textarea>
        </label>
      </div>
      <div>
        <button class="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700" :disabled="profileSaving" @click="saveProfile">保存资料</button>
      </div>
    </div>

    <div v-else-if="selectedTab==='notifications'" class="space-y-3">
      <div class="text-sm">通知设置</div>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        <label class="inline-flex items-center gap-2 text-xs">
          <input type="checkbox" v-model="notifSettings.inApp.reply" /> 回复通知
        </label>
        <label class="inline-flex items-center gap-2 text-xs">
          <input type="checkbox" v-model="notifSettings.inApp.mention" /> 提及通知
        </label>
        <label class="inline-flex items-center gap-2 text-xs">
          <input type="checkbox" v-model="notifSettings.inApp.like" /> 点赞通知
        </label>
        <label class="inline-flex items-center gap-2 text-xs">
          <input type="checkbox" v-model="notifSettings.inApp.system" /> 系统通知
        </label>
        <div class="text-xs md:col-span-2 flex items-center gap-2">
          <label class="inline-flex items-center gap-2">
            <input type="checkbox" v-model="notifSettings.email.enabled" /> 邮件通知
          </label>
          <select v-model="notifSettings.email.frequency" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
            <option value="instant">即时</option>
            <option value="daily">每日汇总</option>
            <option value="weekly">每周汇总</option>
          </select>
        </div>
      </div>
      <div>
        <button class="rounded bg-blue-600 px-3 py-1 text-xs text-white hover:bg-blue-700" @click="saveNotificationSettings">保存通知设置</button>
      </div>

      <div class="mt-4 text-sm">通知列表</div>
      <div class="flex items-center gap-2 text-xs mb-2">
        <select v-model="notifQuery.type" class="rounded border px-2 py-1 text-xs dark:bg-gray-800 dark:border-gray-700">
          <option value="">全部</option>
          <option value="reply">回复</option>
          <option value="mention">提及</option>
          <option value="like">点赞</option>
          <option value="system">系统</option>
        </select>
        <label class="inline-flex items-center gap-2">
          <input type="checkbox" v-model="notifQuery.unread" /> 仅未读
        </label>
        <button class="rounded px-2 py-1 border text-xs dark:border-gray-700" @click="loadNotifications">刷新</button>
      </div>
      <div v-if="notifLoading" class="text-xs text-gray-500">正在加载...</div>
      <div v-else-if="notifError" class="text-xs text-red-600">{{ notifError }}</div>
      <ul class="space-y-2">
        <li v-for="n in (notifList.items||[])" :key="n.id" class="rounded border p-2 text-xs dark:border-gray-700">
          <div class="flex items-center justify-between">
            <div>
              <span class="mr-2">[{{ n.type }}]</span>
              <span class="font-medium">{{ n.title }}</span>
            </div>
            <button v-if="!n.read" class="rounded px-2 py-1 border dark:border-gray-700" @click="setNotificationRead(n.id)">标记已读</button>
          </div>
          <div class="mt-1 text-gray-600 dark:text-gray-300">{{ n.content }}</div>
        </li>
      </ul>
    </div>

    <div v-else class="space-y-3">
      <div class="text-sm">第三方关联</div>
      <div v-if="connectedLoading" class="text-xs text-gray-500">正在加载...</div>
      <ul v-else class="space-y-2 text-xs">
        <li v-for="acc in (connected.items||[])" :key="acc.provider" class="rounded border p-2 dark:border-gray-700 flex items-center justify-between">
          <div>
            <span class="font-medium">{{ acc.provider }}</span>
            <span class="ml-2 text-gray-500">{{ acc.connected ? '已绑定' : '未绑定' }}</span>
          </div>
          <div class="flex items-center gap-2">
            <button v-if="!acc.connected" class="rounded px-2 py-1 border dark:border-gray-700" @click="onConnect(acc.provider)">绑定</button>
            <button v-else class="rounded px-2 py-1 border dark:border-gray-700" @click="onDisconnect(acc.provider)">解绑</button>
          </div>
        </li>
      </ul>
      <div class="text-xs text-gray-500">如需新增 `github/google/weixin` 等 provider，可在后端开放后启用。</div>
    </div>
  </div>
</template>

<style scoped>
</style>
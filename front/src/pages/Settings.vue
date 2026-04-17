<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useUIStore } from '@/stores/ui'
import { getMyProfile, updateMyProfile, getMySettings } from '@/api/settings'
import SettingsAccount from '@/pages/SettingsAccount.vue'
import { listNotifications, markNotificationRead, getNotificationSettings, updateNotificationSettings } from '@/api/notifications'
import { listConnectedAccounts, connectAccount, disconnectAccount } from '@/api/connected'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { normalizeImageUrl } from '@/utils/image'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type {
  ConnectedAccount,
  NotificationItem,
  NotificationSettings,
  PageResult,
  UserProfile,
  UserSettings,
} from '@/types'
import type { UploadImageResponse } from '@/api/uploads'

type SettingsTab = 'profile' | 'notifications' | 'connected' | 'account'

interface ProfileForm extends UserProfile {
  username?: string
}

interface NotificationQuery {
  type: string
  unread: boolean
  page: number
  size: number
}

interface ConnectedAccountsState {
  items: ConnectedAccount[]
}

const selectedTab = ref<SettingsTab>('profile')
// UI 设置开关
const uiStore = useUIStore()
const { dynamicBackgroundEnabled } = storeToRefs(uiStore)
const { setDynamicBackgroundEnabled } = uiStore

// Profile
const profile = ref<ProfileForm>({ nickname: '', bio: '', homepageUrl: '', location: '', links: [], avatarUrl: '' })
const profileSaving = ref(false)
const avatarUploading = ref(false)
const avatarProgress = ref(0)
const avatarPreviewUrl = ref('')
const profileSaveMessage = ref('')
const profileSaveError = ref(false)
// 简介字数限制
const bioMax = 1000
watch(() => profile.value.bio, (val) => {
  const s = String(val || '')
  if (s.length > bioMax) {
    profile.value.bio = s.slice(0, bioMax)
  }
})
const authStore = useAuthStore()
const { token } = storeToRefs(authStore)
function onDynamicBackgroundChange(event: Event): void {
  const target = event.target as HTMLInputElement | null
  setDynamicBackgroundEnabled(!!target?.checked)
}
// 简介图片上传（与发帖同款）
const bioUploading = ref(false)
const bioUploadProgress = ref(0)
async function uploadBioImage(file: File): Promise<string> {
  try {
    bioUploading.value = true
    bioUploadProgress.value = 0
    const { url, path } = await uploadImage(file, token.value, (p) => { bioUploadProgress.value = p })
    return normalizeImageUrl(url || path || '')
  } catch (e: any) {
    alert(e?.response?.data?.message || e?.message || '图片上传失败')
    return ''
  } finally {
    bioUploading.value = false
    bioUploadProgress.value = 0
  }
}
async function loadProfile(): Promise<void> {
  try {
    profile.value = await getMyProfile()
    avatarPreviewUrl.value = normalizeImageUrl(profile.value?.avatarUrl || '')
  } catch (_) {}
}
async function saveProfile(): Promise<void> {
  profileSaving.value = true
  try {
    const payload = {
      nickname: profile.value?.nickname || '',
      bio: profile.value?.bio || '',
      homepageUrl: profile.value?.homepageUrl || '',
      location: profile.value?.location || '',
      links: Array.isArray(profile.value?.links) ? profile.value.links : [],
      avatarUrl: profile.value?.avatarUrl || '',
    }
    const data = await updateMyProfile(payload)
    profile.value = data
    avatarPreviewUrl.value = normalizeImageUrl(profile.value?.avatarUrl || '')
    // 广播资料更新事件，供头部头像实时刷新
    try { window.dispatchEvent(new CustomEvent('profile-updated', { detail: data })) } catch {}
    // 显示保存成功提示（绿色）
    profileSaveError.value = false
    profileSaveMessage.value = '保存成功'
    setTimeout(() => { profileSaveMessage.value = '' }, 3000)
  } catch (e: any) {
    // 显示保存失败提示（红色）
    const msg = e?.response?.data?.message || e?.message || '保存失败'
    profileSaveError.value = true
    profileSaveMessage.value = msg
    setTimeout(() => { profileSaveMessage.value = ''; profileSaveError.value = false }, 4000)
  } finally { profileSaving.value = false }
}

async function onAvatarSelected(evt: Event): Promise<void> {
  try {
    const target = evt.target as HTMLInputElement | null
    const file = target?.files?.[0]
    if (!file) return
    // 基本校验：类型与大小（<= 2MB）
    if (!file.type.startsWith('image/')) {
      alert('请上传图片文件')
      return
    }
    if (file.size > 2 * 1024 * 1024) {
      alert('图片过大（>2MB），请压缩后再试')
      return
    }
    // 令牌校验
    const t = token.value || getStoredAccessToken()
    if (!hasRealToken(t)) {
      alert('请登录后再上传头像')
      return
    }
    avatarUploading.value = true
    avatarProgress.value = 0
    const resp: UploadImageResponse = await uploadImage(file, t, (p) => { avatarProgress.value = p })
    const url = resp?.url || resp?.path || ''
    // 更新本地并保存到后端资料
    profile.value.avatarUrl = url
    avatarPreviewUrl.value = normalizeImageUrl(url)
    await saveProfile()
  } catch (e: any) {
    alert(e?.response?.data?.message || e?.message || '头像上传失败')
  } finally {
    avatarUploading.value = false
    avatarProgress.value = 0
    try {
      const target = evt.target as HTMLInputElement | null
      if (target) target.value = ''
    } catch {}
  }
}

// Settings (for future use)
const settings = ref<UserSettings | null>(null)
async function loadSettings(): Promise<void> {
  try { settings.value = await getMySettings() } catch (_) {}
}

// Notifications list and settings
const notifQuery = ref<NotificationQuery>({ type: '', unread: false, page: 1, size: 20 })
const notifList = ref<PageResult<NotificationItem>>({ items: [], page: 1, size: 20, total: 0 })
const notifLoading = ref(false)
const notifError = ref('')
const notifSettings = ref<NotificationSettings>({
  inApp: { reply: true, mention: true, like: true, system: true },
  email: { enabled: false, frequency: 'instant' },
})

async function loadNotifications(): Promise<void> {
  notifLoading.value = true
  notifError.value = ''
  try {
    const data = await listNotifications({ type: notifQuery.value.type || undefined, unread: notifQuery.value.unread, page: notifQuery.value.page, size: notifQuery.value.size })
    notifList.value = Array.isArray(data)
      ? { items: data, page: notifQuery.value.page, size: notifQuery.value.size, total: data.length }
      : data
  } catch (_) {
    notifError.value = '加载通知失败'
  } finally { notifLoading.value = false }
}
async function loadNotificationSettings(): Promise<void> {
  try { notifSettings.value = await getNotificationSettings() } catch (_) {}
}
async function saveNotificationSettings(): Promise<void> {
  try {
    const data = await updateNotificationSettings(notifSettings.value)
    notifSettings.value = data
  } catch (_) {}
}
async function setNotificationRead(id: NotificationItem['id']): Promise<void> {
  try {
    await markNotificationRead(id)
    await loadNotifications()
  } catch (_) {}
}

// Connected accounts
const connected = ref<ConnectedAccountsState>({ items: [] })
const connectedLoading = ref(false)
async function loadConnected(): Promise<void> {
  connectedLoading.value = true
  try {
    const data = await listConnectedAccounts()
    connected.value = data || { items: [] }
  } catch (_) {
    connected.value = { items: [] }
  } finally { connectedLoading.value = false }
}
async function onConnect(provider: string): Promise<void> {
  try { await connectAccount(provider); await loadConnected() } catch (_) {}
}
async function onDisconnect(provider: string): Promise<void> {
  try { await disconnectAccount(provider); await loadConnected() } catch (_) {}
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
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='profile' ? 'bg-brandDay-600 dark:bg-brandNight-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='profile'">资料设置</button>
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='notifications' ? 'bg-brandDay-600 dark:bg-brandNight-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='notifications'">通知管理</button>
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='connected' ? 'bg-brandDay-600 dark:bg-brandNight-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='connected'">第三方关联</button>
      <button class="rounded px-2 py-1 text-sm" :class="selectedTab==='account' ? 'bg-brandDay-600 dark:bg-brandNight-600 text-white' : 'hover:bg-gray-100 dark:hover:bg-gray-700'" @click="selectedTab='account'">账号信息</button>
    </div>

    <div v-if="selectedTab==='profile'" class="space-y-3">
      <div class="text-sm">头像设置</div>
      <div class="flex items-center gap-3">
        <template v-if="avatarPreviewUrl">
          <img :src="avatarPreviewUrl" alt="头像预览" class="w-16 h-16 rounded-full object-cover border border-gray-300 dark:border-gray-700" />
        </template>
        <template v-else>
          <div class="w-16 h-16 rounded-full bg-gray-200 dark:bg-gray-700 flex items-center justify-center text-sm font-medium">
            {{ String(profile.nickname || 'U').slice(0,1).toUpperCase() }}
          </div>
        </template>
        <div class="text-xs">
          <input type="file" accept="image/*" @change="onAvatarSelected" />
          <div v-if="avatarUploading" class="mt-1 text-gray-600 dark:text-gray-300">上传中… {{ avatarProgress }}%</div>
        </div>
      </div>

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
        <div class="text-xs md:col-span-2">
          <div>个人简介</div>
          <div class="mt-1 flex items-center justify-between gap-2">
            <div class="text-[11px] text-gray-500 dark:text-gray-400">使用轻量 Markdown 编辑器，保留预览和图片插入能力</div>
            <span class="text-xs text-gray-500">字数：{{ String(profile.bio||'').length }}/{{ bioMax }}</span>
          </div>
          <MarkdownTextareaEditor
            v-model="profile.bio"
            :rows="10"
            :max-length="bioMax"
            :uploading="bioUploading"
            :upload-progress="bioUploadProgress"
            :upload-image="uploadBioImage"
            placeholder="介绍一下自己吧，支持 Markdown"
            preview-label="简介预览"
          />
        </div>
      </div>
      <div>
        <button class="rounded bg-brandDay-600 dark:bg-brandNight-600 px-3 py-1 text-xs text-white hover:bg-brandDay-700 dark:hover:bg-brandNight-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" :disabled="profileSaving" @click="saveProfile">保存资料</button>
        <span v-if="profileSaveMessage" :class="['ml-2 text-xs', profileSaveError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ profileSaveMessage }}</span>
      </div>

      <!-- 界面偏好：动态背景效果开关（本地持久化） -->
      <div class="pt-2">
        <div class="text-sm">界面偏好</div>
        <label class="mt-1 inline-flex items-center gap-2 text-xs">
          <input type="checkbox" :checked="dynamicBackgroundEnabled" @change="onDynamicBackgroundChange" />
          启用动态背景效果（Day 下雪 / Night 星光闪烁）
        </label>
        <div class="text-[11px] text-gray-500 mt-1">尊重系统“减少动效”偏好；默认仅在 Day 下雪、Night 闪烁。</div>
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
        <button class="rounded bg-brand-600 px-3 py-1 text-xs text-white hover:bg-brand-700 motion-safe:transition-shadow motion-safe:duration-200 shadow-sm hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 dark:focus:ring-accentCyan-400" @click="saveNotificationSettings">保存通知设置</button>
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

    <div v-else-if="selectedTab==='connected'" class="space-y-3">
      <div class="text-sm">第三方关联</div>
      <div v-if="connectedLoading" class="text-xs text-gray-500">正在加载...</div>
      <ul v-else class="space-y-2 text-xs">
        <li v-for="acc in (connected.items||[])" :key="acc.provider" class="rounded-lg border border-gray-100 p-2 dark:border-gray-700 flex items-center justify-between">
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

    <div v-else class="space-y-3">
      <SettingsAccount />
    </div>
  </div>
</template>

<style scoped>
</style>

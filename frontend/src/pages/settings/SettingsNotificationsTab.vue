<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings, updateMyNotificationSettings } from '@/api/settings'
import { formatAbsoluteTime, formatRelativeTime } from '@/composables/time'
import type { NotificationItem, NotificationSettings, PageResult } from '@/types'
import { getStoredAccessToken } from '@/utils/authStorage'
import { subscribeNotificationChanges } from '@/composables/notificationSignals'

interface NotificationQuery {
  type: string
  unread: boolean
  page: number
  size: number
}

interface NotificationSettingsResponse {
  inApp?: Partial<NotificationSettings['inApp']>
  email?: Partial<NotificationSettings['email']>
}

const notifQuery = ref<NotificationQuery>({ type: '', unread: false, page: 1, size: 20 })
const notifList = ref<PageResult<NotificationItem>>({ items: [], page: 1, size: 20, total: 0 })
const notifLoading = ref(false)
const notifError = ref('')
const notifActionError = ref('')
const settingsLoading = ref(false)
const settingsLoaded = ref(false)
const settingsError = ref('')
const settingsSaving = ref(false)
const settingsMessage = ref('')
const settingsMessageError = ref(false)
const savedPreferenceSnapshot = ref('')
const router = useRouter()
let notificationRequestSeq = 0
let settingsRequestSeq = 0
let notificationActionSeq = 0
let disposed = false
const stopLiveUpdates = subscribeNotificationChanges(signal => {
  if (signal === 'session') {
    notificationRequestSeq += 1; settingsRequestSeq += 1; notificationActionSeq += 1
    notifList.value = { items: [], page: 1, size: 20, total: 0 }
  }
  if (!disposed) void loadNotifications()
})
const emit = defineEmits<{
  (event: 'dirty-change', dirty: boolean): void
}>()

const notificationPreferences = [
  { key: 'reply', label: '回复通知', description: '有人回复你的主题或回复时提醒' },
  { key: 'mention', label: '提及通知', description: '有人在主题或回复中提及你时提醒' },
  { key: 'like', label: '点赞通知', description: '有人点赞你的主题或回复时提醒' },
  { key: 'follow', label: '关注通知', description: '有人关注你时提醒' },
] as const

function defaultNotificationSettings(): NotificationSettings {
  return {
    inApp: { reply: true, mention: true, like: true, follow: true, system: true },
    email: { enabled: false, frequency: 'instant' },
  }
}

function normalizeNotificationSettings(value: NotificationSettingsResponse | null | undefined): NotificationSettings {
  return {
    inApp: {
      reply: value?.inApp?.reply ?? true,
      mention: value?.inApp?.mention ?? true,
      like: value?.inApp?.like ?? true,
      follow: value?.inApp?.follow ?? true,
      system: true,
    },
    email: {
      enabled: false,
      frequency: value?.email?.frequency || 'instant',
    },
  }
}

const notifSettings = ref<NotificationSettings>(defaultNotificationSettings())

function preferenceSnapshot(settings: NotificationSettings): string {
  return notificationPreferences.map(({ key }) => String(settings.inApp[key])).join('|')
}

const settingsDirty = computed(() => (
  settingsLoaded.value && preferenceSnapshot(notifSettings.value) !== savedPreferenceSnapshot.value
))
const canSaveSettings = computed(() => (
  settingsLoaded.value && !settingsLoading.value && !settingsError.value && !settingsSaving.value
))

watch(settingsDirty, (dirty) => {
  emit('dirty-change', dirty)
}, { immediate: true })

async function loadNotifications(): Promise<void> {
  const token = getStoredAccessToken()
  const requestSeq = ++notificationRequestSeq
  notifLoading.value = true
  notifError.value = ''
  notifActionError.value = ''
  try {
    const data = await listNotifications({
      type: notifQuery.value.type || undefined,
      unread: notifQuery.value.unread,
      page: notifQuery.value.page,
      size: notifQuery.value.size,
    })
    if (token !== getStoredAccessToken() || disposed || requestSeq !== notificationRequestSeq) return
    notifList.value = data
  } catch (_) {
    if (token === getStoredAccessToken() && !disposed && requestSeq === notificationRequestSeq) notifError.value = '加载通知失败，请重试。'
  } finally {
    if (token === getStoredAccessToken() && !disposed && requestSeq === notificationRequestSeq) notifLoading.value = false
  }
}

async function loadNotificationSettings(): Promise<void> {
  const token = getStoredAccessToken()
  const requestSeq = ++settingsRequestSeq
  settingsLoading.value = true
  settingsLoaded.value = false
  settingsError.value = ''
  settingsMessage.value = ''
  try {
    const data = await getMyNotificationSettings()
    if (token !== getStoredAccessToken() || disposed || requestSeq !== settingsRequestSeq) return
    const normalized = normalizeNotificationSettings(data)
    notifSettings.value = normalized
    savedPreferenceSnapshot.value = preferenceSnapshot(normalized)
    settingsLoaded.value = true
  } catch (_) {
    if (token === getStoredAccessToken() && !disposed && requestSeq === settingsRequestSeq) settingsError.value = '通知偏好加载失败，暂时无法编辑或保存。'
  } finally {
    if (token === getStoredAccessToken() && !disposed && requestSeq === settingsRequestSeq) settingsLoading.value = false
  }
}

async function saveNotificationSettings(): Promise<void> {
  if (!canSaveSettings.value) return

  const token = getStoredAccessToken()
  const requestSeq = ++settingsRequestSeq
  settingsSaving.value = true
  settingsMessage.value = ''
  settingsMessageError.value = false
  const payload: NotificationSettings = {
    inApp: {
      reply: notifSettings.value.inApp.reply,
      mention: notifSettings.value.inApp.mention,
      like: notifSettings.value.inApp.like,
      follow: notifSettings.value.inApp.follow,
      system: true,
    },
    email: {
      enabled: false,
      frequency: notifSettings.value.email.frequency || 'instant',
    },
  }
  try {
    const data = await updateMyNotificationSettings(payload)
    if (token !== getStoredAccessToken() || disposed || requestSeq !== settingsRequestSeq) return
    const normalized = normalizeNotificationSettings(data)
    notifSettings.value = normalized
    savedPreferenceSnapshot.value = preferenceSnapshot(normalized)
    settingsMessage.value = '保存成功'
  } catch (_) {
    if (token === getStoredAccessToken() && !disposed && requestSeq === settingsRequestSeq) {
      settingsMessageError.value = true
      settingsMessage.value = '保存失败，请稍后重试。'
    }
  } finally {
    if (token === getStoredAccessToken() && !disposed && requestSeq === settingsRequestSeq) settingsSaving.value = false
  }
}

async function setNotificationRead(id: NotificationItem['id']): Promise<void> {
  const token = getStoredAccessToken()
  const actionSeq = ++notificationActionSeq
  notifActionError.value = ''
  try {
    await markNotificationRead(id)
    if (token !== getStoredAccessToken() || disposed || actionSeq !== notificationActionSeq) return
    await loadNotifications()
  } catch (_) {
    if (token === getStoredAccessToken() && !disposed && actionSeq === notificationActionSeq) notifActionError.value = '标记已读失败，请稍后重试。'
  }
}

function notificationTypeLabel(type: NotificationItem['type']): string {
  if (type === 'reply') return '回复通知'
  if (type === 'mention') return '提及通知'
  if (type === 'like') return '点赞通知'
  if (type === 'follow') return '关注通知'
  if (type === 'system') return '系统通知'
  return '通知'
}

function notificationStatusLabel(read: NotificationItem['read']): string {
  return read ? '已读' : '未读'
}

function notificationTitle(item: NotificationItem): string {
  return item.title?.trim() || notificationTypeLabel(item.type)
}

function notificationTimeLabel(value: NotificationItem['createdAt']): string {
  if (!value || !Number.isFinite(new Date(value).getTime())) return '时间未知'
  return formatRelativeTime(value) || formatAbsoluteTime(value)
}

function notificationAbsoluteTime(value: NotificationItem['createdAt']): string {
  if (!value || !Number.isFinite(new Date(value).getTime())) return ''
  return formatAbsoluteTime(value)
}

function notificationLink(item: NotificationItem): string {
  const directLink = String(item?.link || '').trim()
  if (directLink) {
    return directLink
  }
  const threadId = item?.threadId || (item?.targetType === 'thread' ? item?.targetId : null)
  if (!threadId) {
    return ''
  }
  const targetId = item?.targetType === 'post' ? item?.targetId : null
  return `/threads/${threadId}${targetId ? `#post-${targetId}` : ''}`
}

function notificationCanNavigate(item: NotificationItem): boolean {
  return Boolean(notificationLink(item))
}

async function openNotification(item: NotificationItem): Promise<void> {
  const link = notificationLink(item)
  if (!link) {
    return
  }
  const token = getStoredAccessToken()
  const actionSeq = ++notificationActionSeq
  if (!item.read) {
    try {
      await markNotificationRead(item.id)
      if (token !== getStoredAccessToken() || disposed || actionSeq !== notificationActionSeq) return
      item.read = true
    } catch (_) {
      if (token === getStoredAccessToken() && !disposed && actionSeq === notificationActionSeq) notifActionError.value = '标记已读失败，请稍后重试。'
    }
  }
  if (token !== getStoredAccessToken() || disposed || actionSeq !== notificationActionSeq) return
  await router.push(link)
}

onMounted(() => {
  void loadNotifications()
  void loadNotificationSettings()
})
onUnmounted(() => {
  stopLiveUpdates()
  disposed = true
  notificationRequestSeq += 1
  settingsRequestSeq += 1
  notificationActionSeq += 1
})
</script>

<template>
  <div class="space-y-5">
    <section aria-labelledby="notification-preferences-title" class="space-y-4 border-b border-gray-200 pb-6 dark:border-gray-700">
      <h2 id="notification-preferences-title" class="text-base font-semibold">通知偏好</h2>
      <div v-if="settingsLoading" class="ui-muted text-sm" role="status" aria-live="polite">
        正在加载通知偏好...
      </div>
      <div v-else-if="settingsError" class="ui-error flex flex-col gap-3 text-sm" role="alert">
        <span>{{ settingsError }}</span>
        <button type="button" class="ui-button w-fit px-3 py-1.5 text-xs" @click="loadNotificationSettings">
          重试加载
        </button>
      </div>
      <div v-else-if="settingsLoaded" class="divide-y divide-gray-200 dark:divide-gray-700">
        <label v-for="preference in notificationPreferences" :key="preference.key" :for="`notification-preference-${preference.key}`" class="flex min-h-20 cursor-pointer items-center justify-between gap-4 py-4 text-sm transition-colors hover:bg-[var(--ui-subtle)]">
          <span>
            <span class="block font-medium">{{ preference.label }}</span>
            <span class="ui-muted mt-1 block text-xs">{{ preference.description }}</span>
          </span>
          <input :id="`notification-preference-${preference.key}`" v-model="notifSettings.inApp[preference.key]" type="checkbox" class="h-4 w-4 shrink-0 rounded border-gray-300 text-brandDay-600 focus:ring-brandDay-500 dark:border-gray-600 dark:bg-gray-700 dark:text-brandNight-500" />
        </label>
        <div class="flex items-start gap-4 py-4 text-sm">
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="font-medium">系统通知</p>
              <p class="ui-muted mt-1 text-xs">系统通知由平台统一发送，当前不可关闭。</p>
            </div>
            <span class="ui-tag shrink-0 text-[11px]">固定开启</span>
          </div>
        </div>
        <div class="flex items-start gap-4 py-4 text-sm">
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="font-medium">邮件通知</p>
              <p class="ui-muted mt-1 text-xs">邮件投递尚未实现，当前仅支持站内通知。</p>
            </div>
            <span class="ui-tag shrink-0 text-[11px]">暂不可用</span>
          </div>
        </div>
      </div>
      <div class="flex flex-wrap items-center justify-end gap-3 pt-1">
        <span v-if="settingsDirty" class="text-xs text-amber-700 dark:text-amber-300">有未保存的修改</span>
        <span v-if="settingsMessage" :class="['text-xs', settingsMessageError ? 'text-red-600 dark:text-red-400' : 'text-emerald-700 dark:text-emerald-300']" role="status" aria-live="polite">{{ settingsMessage }}</span>
        <button
          type="button"
          class="ui-button-primary"
          :disabled="!canSaveSettings"
          @click="saveNotificationSettings"
        >
          {{ settingsSaving ? '保存中...' : '保存通知设置' }}
        </button>
      </div>
    </section>

    <section aria-labelledby="notification-list-title" class="space-y-4">
      <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <h2 id="notification-list-title" class="text-base font-semibold">通知列表</h2>
        <div class="flex flex-wrap items-center gap-2 text-xs">
          <label for="notification-type-filter" class="sr-only">通知类型</label>
          <select id="notification-type-filter" v-model="notifQuery.type" aria-label="通知类型" class="ui-field min-h-9 w-auto px-2 py-1.5 text-xs">
            <option value="">全部</option>
            <option value="reply">回复</option>
            <option value="mention">提及</option>
            <option value="like">点赞</option>
            <option value="follow">关注</option>
            <option value="system">系统</option>
          </select>
          <label class="ui-button inline-flex min-h-9 cursor-pointer gap-2 px-2 py-1.5 text-xs">
            <input v-model="notifQuery.unread" type="checkbox" /> 仅未读
          </label>
          <button type="button" class="ui-button min-h-9 px-3 py-1.5 text-xs" @click="loadNotifications">刷新</button>
        </div>
      </div>
      <div class="mt-3">
        <div v-if="notifLoading" class="ui-muted text-sm" role="status" aria-live="polite">正在加载通知...</div>
        <div v-else-if="notifError" class="ui-error flex items-center justify-between gap-3 text-sm" role="alert">
          <span>{{ notifError }}</span>
          <button type="button" class="ui-button shrink-0 px-3 py-1.5 text-xs" @click="loadNotifications">
            重试
          </button>
        </div>
        <div v-else-if="!(notifList.items || []).length" class="ui-empty border border-dashed border-gray-200 dark:border-gray-700">
          暂无通知
        </div>
        <ul v-else class="divide-y divide-gray-200 dark:divide-gray-700" aria-label="通知列表">
          <li v-for="item in notifList.items || []" :key="item.id" class="ui-list-row px-0 py-4 text-sm">
            <article>
              <div class="flex flex-wrap items-center justify-between gap-2">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="ui-tag">{{ notificationTypeLabel(item.type) }}</span>
                  <span :class="item.read ? 'text-gray-400 dark:text-gray-500' : 'font-medium text-brandDay-700 dark:text-brandNight-300'">{{ notificationStatusLabel(item.read) }}</span>
                </div>
                <time class="text-[11px] text-gray-400 dark:text-gray-500" :datetime="item.createdAt || undefined" :title="notificationAbsoluteTime(item.createdAt)">
                  {{ notificationTimeLabel(item.createdAt) }}
                </time>
              </div>
              <div class="mt-2 flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div class="min-w-0 flex-1">
                   <span class="block font-medium">{{ notificationTitle(item) }}</span>
                   <span class="ui-muted mt-1 block">{{ item.content || '暂无详细内容' }}</span>
                </div>
                <div class="flex shrink-0 items-center gap-2 sm:pt-0.5">
                   <button v-if="notificationCanNavigate(item)" type="button" class="ui-button px-2 py-1 text-xs" :aria-label="`查看${notificationTitle(item)}`" @click="openNotification(item)">
                    查看
                  </button>
                   <button v-if="!item.read" type="button" class="ui-button px-2 py-1 text-xs" @click="setNotificationRead(item.id)">
                    标记为已读
                  </button>
                </div>
              </div>
            </article>
          </li>
        </ul>
        <p v-if="notifActionError" class="mt-2 text-xs text-red-600 dark:text-red-400" role="alert">{{ notifActionError }}</p>
      </div>
    </section>
  </div>
</template>

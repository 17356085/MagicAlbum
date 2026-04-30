<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listNotifications, markNotificationRead } from '@/api/notifications'
import { getMyNotificationSettings, updateMyNotificationSettings } from '@/api/settings'
import type { NotificationItem, NotificationSettings, PageResult } from '@/types'

interface NotificationQuery {
  type: string
  unread: boolean
  page: number
  size: number
}

const notifQuery = ref<NotificationQuery>({ type: '', unread: false, page: 1, size: 20 })
const notifList = ref<PageResult<NotificationItem>>({ items: [], page: 1, size: 20, total: 0 })
const notifLoading = ref(false)
const notifError = ref('')
const settingsSaving = ref(false)
const settingsMessage = ref('')
const settingsMessageError = ref(false)
const router = useRouter()
const notifSettings = ref<NotificationSettings>({
  inApp: { reply: true, mention: true, like: true, system: true },
  email: { enabled: false, frequency: 'instant' },
})

async function loadNotifications(): Promise<void> {
  notifLoading.value = true
  notifError.value = ''
  try {
    const data = await listNotifications({
      type: notifQuery.value.type || undefined,
      unread: notifQuery.value.unread,
      page: notifQuery.value.page,
      size: notifQuery.value.size,
    })
    notifList.value = Array.isArray(data)
      ? { items: data, page: notifQuery.value.page, size: notifQuery.value.size, total: data.length }
      : data
  } catch (_) {
    notifError.value = '加载通知失败'
  } finally {
    notifLoading.value = false
  }
}

async function loadNotificationSettings(): Promise<void> {
  try {
    notifSettings.value = await getMyNotificationSettings()
  } catch (_) {}
}

async function saveNotificationSettings(): Promise<void> {
  settingsSaving.value = true
  try {
    notifSettings.value = await updateMyNotificationSettings(notifSettings.value)
    settingsMessageError.value = false
    settingsMessage.value = '保存成功'
  } catch (_) {
    settingsMessageError.value = true
    settingsMessage.value = '保存失败'
  }
  finally {
    settingsSaving.value = false
    setTimeout(() => {
      settingsMessage.value = ''
      settingsMessageError.value = false
    }, 3000)
  }
}

async function setNotificationRead(id: NotificationItem['id']): Promise<void> {
  try {
    await markNotificationRead(id)
    await loadNotifications()
  } catch (_) {}
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
  if (!item.read) {
    try {
      await markNotificationRead(item.id)
      item.read = true
    } catch (_) {}
  }
  await router.push(link)
}

onMounted(() => {
  void loadNotifications()
  void loadNotificationSettings()
})
</script>

<template>
  <div class="space-y-5">
    <section>
      <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">通知偏好</h2>
      <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2">
        <label class="flex items-center justify-between rounded-md border border-gray-100 p-3 text-sm dark:border-gray-700">
          <span>回复通知</span>
          <input v-model="notifSettings.inApp.reply" type="checkbox" />
        </label>
        <label class="flex items-center justify-between rounded-md border border-gray-100 p-3 text-sm dark:border-gray-700">
          <span>提及通知</span>
          <input v-model="notifSettings.inApp.mention" type="checkbox" />
        </label>
        <label class="flex items-center justify-between rounded-md border border-gray-100 p-3 text-sm dark:border-gray-700">
          <span>点赞通知</span>
          <input v-model="notifSettings.inApp.like" type="checkbox" />
        </label>
        <label class="flex items-center justify-between rounded-md border border-gray-100 p-3 text-sm dark:border-gray-700">
          <span>系统通知</span>
          <input v-model="notifSettings.inApp.system" type="checkbox" />
        </label>
        <div class="rounded-md border border-gray-100 p-3 text-sm dark:border-gray-700 md:col-span-2">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <label class="inline-flex items-center gap-2">
              <input v-model="notifSettings.email.enabled" type="checkbox" /> 邮件通知
            </label>
            <select v-model="notifSettings.email.frequency" class="rounded-md border border-gray-300 px-3 py-2 text-xs dark:border-gray-700 dark:bg-gray-800">
              <option value="instant">即时</option>
              <option value="daily">每日汇总</option>
              <option value="weekly">每周汇总</option>
            </select>
          </div>
        </div>
      </div>
      <div class="mt-4 flex items-center justify-end gap-3">
        <span v-if="settingsMessage" :class="['text-xs', settingsMessageError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ settingsMessage }}</span>
        <button
          class="rounded-md bg-brandDay-600 px-4 py-2 text-xs font-medium text-white shadow-sm transition-shadow duration-200 hover:bg-brandDay-700 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 disabled:opacity-60 dark:bg-brandNight-600 dark:hover:bg-brandNight-700 dark:focus:ring-accentCyan-400"
          :disabled="settingsSaving"
          @click="saveNotificationSettings"
        >
          {{ settingsSaving ? '保存中...' : '保存通知设置' }}
        </button>
      </div>
    </section>

    <section>
      <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">通知列表</h2>
        <div class="flex flex-wrap items-center gap-2 text-xs">
          <select v-model="notifQuery.type" class="rounded-md border border-gray-300 px-2 py-1.5 text-xs dark:border-gray-700 dark:bg-gray-800">
            <option value="">全部</option>
            <option value="reply">回复</option>
            <option value="mention">提及</option>
            <option value="like">点赞</option>
            <option value="follow">关注</option>
            <option value="system">系统</option>
          </select>
          <label class="inline-flex items-center gap-2 rounded-md border border-gray-200 px-2 py-1.5 dark:border-gray-700">
            <input v-model="notifQuery.unread" type="checkbox" /> 仅未读
          </label>
          <button class="rounded-md border border-gray-200 px-3 py-1.5 text-xs font-medium hover:bg-gray-50 dark:border-gray-700 dark:hover:bg-gray-700" @click="loadNotifications">刷新</button>
        </div>
      </div>
      <div class="mt-3">
        <div v-if="notifLoading" class="text-xs text-gray-500">正在加载...</div>
        <div v-else-if="notifError" class="text-xs text-red-600">{{ notifError }}</div>
        <ul v-else class="space-y-2">
          <li
            v-for="item in notifList.items || []"
            :key="item.id"
            class="rounded-md border border-gray-100 p-3 text-xs dark:border-gray-700"
            :class="notificationCanNavigate(item) ? 'cursor-pointer transition-colors hover:border-brandDay-200 hover:bg-brandDay-50/50 dark:hover:border-brandNight-700 dark:hover:bg-brandNight-900/20' : ''"
            @click="openNotification(item)"
          >
            <div class="flex items-center justify-between gap-3">
              <div>
                <span class="mr-2 rounded bg-gray-100 px-2 py-0.5 text-gray-600 dark:bg-gray-700 dark:text-gray-200">{{ item.type }}</span>
                <span class="font-medium text-gray-900 dark:text-gray-50">{{ item.title }}</span>
              </div>
              <div class="flex items-center gap-2">
                <button v-if="notificationCanNavigate(item)" class="rounded-md border border-brandDay-200 px-2 py-1 text-brandDay-700 hover:bg-brandDay-50 dark:border-brandNight-700 dark:text-brandNight-200 dark:hover:bg-brandNight-900/30" @click.stop="openNotification(item)">查看</button>
                <button v-if="!item.read" class="rounded-md border border-gray-200 px-2 py-1 dark:border-gray-700" @click.stop="setNotificationRead(item.id)">标记已读</button>
              </div>
            </div>
            <div class="mt-2 text-gray-600 dark:text-gray-300">{{ item.content }}</div>
          </li>
          <li v-if="!(notifList.items || []).length" class="rounded-md border border-dashed border-gray-200 p-4 text-center text-xs text-gray-500 dark:border-gray-700">
            暂无通知
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

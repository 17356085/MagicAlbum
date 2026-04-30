<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useUIStore } from '@/stores/ui'
import { getMyProfile, updateMyProfile } from '@/api/settings'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { normalizeImageUrl } from '@/utils/image'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { UserProfile } from '@/types'
import type { UploadImageResponse } from '@/api/uploads'

interface ProfileForm extends UserProfile {
  username?: string
}

const uiStore = useUIStore()
const { dynamicBackgroundEnabled } = storeToRefs(uiStore)
const { setDynamicBackgroundEnabled } = uiStore

const profile = ref<ProfileForm>({ nickname: '', bio: '', homepageUrl: '', location: '', links: [], avatarUrl: '' })
const profileSaving = ref(false)
const avatarUploading = ref(false)
const avatarProgress = ref(0)
const avatarPreviewUrl = ref('')
const profileSaveMessage = ref('')
const profileSaveError = ref(false)
const bioUploading = ref(false)
const bioUploadProgress = ref(0)
const bioMax = 1000

const authStore = useAuthStore()
const { token } = storeToRefs(authStore)

watch(() => profile.value.bio, (val) => {
  const s = String(val || '')
  if (s.length > bioMax) {
    profile.value.bio = s.slice(0, bioMax)
  }
})

function onDynamicBackgroundChange(event: Event): void {
  const target = event.target as HTMLInputElement | null
  setDynamicBackgroundEnabled(!!target?.checked)
}

async function uploadBioImage(file: File): Promise<string> {
  try {
    bioUploading.value = true
    bioUploadProgress.value = 0
    const { url, path } = await uploadImage(file, token.value, (progress) => {
      bioUploadProgress.value = progress
    })
    return normalizeImageUrl(url || path || '')
  } catch (error: any) {
    alert(error?.response?.data?.message || error?.message || '图片上传失败')
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
    try {
      window.dispatchEvent(new CustomEvent('profile-updated', { detail: data }))
    } catch {}
    profileSaveError.value = false
    profileSaveMessage.value = '保存成功'
    setTimeout(() => {
      profileSaveMessage.value = ''
    }, 3000)
  } catch (error: any) {
    const message = error?.response?.data?.message || error?.message || '保存失败'
    profileSaveError.value = true
    profileSaveMessage.value = message
    setTimeout(() => {
      profileSaveMessage.value = ''
      profileSaveError.value = false
    }, 4000)
  } finally {
    profileSaving.value = false
  }
}

async function onAvatarSelected(event: Event): Promise<void> {
  try {
    const target = event.target as HTMLInputElement | null
    const file = target?.files?.[0]
    if (!file) return
    if (!file.type.startsWith('image/')) {
      alert('请上传图片文件')
      return
    }
    if (file.size > 2 * 1024 * 1024) {
      alert('图片过大（>2MB），请压缩后再试')
      return
    }

    const accessToken = token.value || getStoredAccessToken()
    if (!hasRealToken(accessToken)) {
      alert('请登录后再上传头像')
      return
    }

    avatarUploading.value = true
    avatarProgress.value = 0
    const response: UploadImageResponse = await uploadImage(file, accessToken, (progress) => {
      avatarProgress.value = progress
    })
    const url = response?.url || response?.path || ''
    profile.value.avatarUrl = url
    avatarPreviewUrl.value = normalizeImageUrl(url)
    await saveProfile()
  } catch (error: any) {
    alert(error?.response?.data?.message || error?.message || '头像上传失败')
  } finally {
    avatarUploading.value = false
    avatarProgress.value = 0
    try {
      const target = event.target as HTMLInputElement | null
      if (target) target.value = ''
    } catch {}
  }
}

onMounted(() => {
  void loadProfile()
})
</script>

<template>
  <div class="space-y-5">
    <section>
      <div class="space-y-4">
        <h2 class="text-sm font-semibold text-gray-900 dark:text-gray-50">基本资料</h2>
        <div class="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
          <div class="flex items-center gap-4">
            <template v-if="avatarPreviewUrl">
              <img :src="avatarPreviewUrl" alt="头像预览" class="h-20 w-20 rounded-full border border-gray-200 object-cover dark:border-gray-700" />
            </template>
            <template v-else>
              <div class="flex h-20 w-20 items-center justify-center rounded-full bg-gray-200 text-lg font-semibold dark:bg-gray-700">
                {{ String(profile.nickname || 'U').slice(0, 1).toUpperCase() }}
              </div>
            </template>
            <div>
              <div class="text-sm font-medium text-gray-900 dark:text-gray-50">{{ profile.nickname || profile.username || '未设置昵称' }}</div>
              <div v-if="avatarUploading" class="mt-2 text-xs text-gray-600 dark:text-gray-300">上传中 {{ avatarProgress }}%</div>
            </div>
          </div>
          <label class="inline-flex cursor-pointer items-center justify-center rounded-md border border-gray-200 px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700">
            更换头像
            <input type="file" accept="image/*" class="hidden" @change="onAvatarSelected" />
          </label>
        </div>
        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <label class="text-xs font-medium text-gray-700 dark:text-gray-200">昵称
            <input v-model="profile.nickname" class="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="昵称" />
          </label>
          <label class="text-xs font-medium text-gray-700 dark:text-gray-200">主页链接
            <input v-model="profile.homepageUrl" class="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="https://..." />
          </label>
          <label class="text-xs font-medium text-gray-700 dark:text-gray-200">所在地
            <input v-model="profile.location" class="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="城市" />
          </label>
        </div>
        <div class="text-xs">
          <div class="font-medium text-gray-700 dark:text-gray-200">个人简介</div>
          <div class="mt-1 flex items-center justify-between gap-2">
            <span class="text-xs text-gray-500 dark:text-gray-400">字数：{{ String(profile.bio || '').length }}/{{ bioMax }}</span>
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
      <div class="mt-4 flex items-center justify-end gap-3">
        <span v-if="profileSaveMessage" :class="['text-xs', profileSaveError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ profileSaveMessage }}</span>
        <button
          class="rounded-md bg-brandDay-600 px-4 py-2 text-xs font-medium text-white shadow-sm transition-shadow duration-200 hover:bg-brandDay-700 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 disabled:opacity-60 dark:bg-brandNight-600 dark:hover:bg-brandNight-700 dark:focus:ring-accentCyan-400"
          :disabled="profileSaving"
          @click="saveProfile"
        >
          {{ profileSaving ? '保存中...' : '保存资料' }}
        </button>
      </div>
    </section>

    <section>
      <label class="flex items-center justify-between gap-4">
        <span class="text-sm font-semibold text-gray-900 dark:text-gray-50">动态背景效果</span>
        <input type="checkbox" :checked="dynamicBackgroundEnabled" @change="onDynamicBackgroundChange" />
      </label>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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

const EMPTY_PROFILE: ProfileForm = {
  nickname: '',
  bio: '',
  homepageUrl: '',
  location: '',
  avatarUrl: '',
}

const uiStore = useUIStore()
const { dynamicBackgroundEnabled } = storeToRefs(uiStore)
const { setDynamicBackgroundEnabled } = uiStore

const profile = ref<ProfileForm>({ ...EMPTY_PROFILE })
const profileBaseline = ref(profileSnapshot(profile.value))
const profileLoading = ref(false)
const profileLoaded = ref(false)
const profileLoadError = ref('')
const profileSaving = ref(false)
const avatarUploading = ref(false)
const avatarProgress = ref(0)
const avatarPreviewUrl = ref('')
const avatarUploadError = ref('')
const profileSaveMessage = ref('')
const profileSaveError = ref(false)
const bioUploading = ref(false)
const bioUploadProgress = ref(0)
const bioUploadError = ref('')
const bioMax = 1000

const authStore = useAuthStore()
const { token } = storeToRefs(authStore)

const emit = defineEmits<{
  'dirty-change': [dirty: boolean]
}>()

function profileSnapshot(value: Partial<ProfileForm>): string {
  return JSON.stringify({
    nickname: String(value.nickname || ''),
    bio: String(value.bio || ''),
    homepageUrl: String(value.homepageUrl || ''),
    location: String(value.location || ''),
    avatarUrl: String(value.avatarUrl || ''),
  })
}

function normalizeProfile(value: Partial<ProfileForm> | null | undefined): ProfileForm {
  return {
    ...EMPTY_PROFILE,
    nickname: String(value?.nickname || ''),
    bio: String(value?.bio || ''),
    homepageUrl: String(value?.homepageUrl || ''),
    location: String(value?.location || ''),
    avatarUrl: String(value?.avatarUrl || ''),
    ...(value?.username ? { username: String(value.username) } : {}),
  }
}

const profileDirty = computed(() => profileSnapshot(profile.value) !== profileBaseline.value)
watch(profileDirty, (dirty) => {
  emit('dirty-change', dirty)
}, { immediate: true })

watch(() => profile.value.bio, (val) => {
  const s = String(val || '')
  if (s.length > bioMax) {
    profile.value.bio = s.slice(0, bioMax)
  }
})

function getErrorMessage(error: unknown, fallback: string): string {
  const value = error as { response?: { data?: { message?: string } }; message?: string } | null | undefined
  return value?.response?.data?.message || value?.message || fallback
}

function onDynamicBackgroundChange(event: Event): void {
  const target = event.target as HTMLInputElement | null
  setDynamicBackgroundEnabled(!!target?.checked)
}

async function uploadBioImage(file: File): Promise<string> {
  bioUploadError.value = ''
  if (!profileLoaded.value) {
    bioUploadError.value = profileLoadError.value || '资料尚未加载成功，请重试后再上传图片'
    return ''
  }

  const accessToken = token.value || getStoredAccessToken()
  if (!hasRealToken(accessToken)) {
    bioUploadError.value = '请登录后再上传图片'
    return ''
  }

  try {
    bioUploading.value = true
    bioUploadProgress.value = 0
    const { url, path } = await uploadImage(file, accessToken, (progress) => {
      bioUploadProgress.value = progress
    })
    const normalizedUrl = normalizeImageUrl(url || path || '')
    if (!normalizedUrl) {
      bioUploadError.value = '图片上传未返回有效地址'
    }
    return normalizedUrl
  } catch (error: unknown) {
    bioUploadError.value = getErrorMessage(error, '图片上传失败')
    return ''
  } finally {
    bioUploading.value = false
    bioUploadProgress.value = 0
  }
}

async function loadProfile(): Promise<void> {
  profileLoading.value = true
  profileLoadError.value = ''
  try {
    const data = await getMyProfile()
    if (!data || typeof data !== 'object' || Object.keys(data).length === 0) throw new Error('资料数据为空，请稍后重试')
    profile.value = normalizeProfile(data)
    profileBaseline.value = profileSnapshot(profile.value)
    avatarPreviewUrl.value = normalizeImageUrl(profile.value.avatarUrl || '')
    profileLoaded.value = true
  } catch (error: unknown) {
    profileLoaded.value = false
    profileLoadError.value = getErrorMessage(error, '资料加载失败，请重试')
  } finally {
    profileLoading.value = false
  }
}

function setProfileSaveMessage(message: string, isError = false): void {
  profileSaveError.value = isError
  profileSaveMessage.value = message
  setTimeout(() => {
    profileSaveMessage.value = ''
    profileSaveError.value = false
  }, isError ? 4000 : 3000)
}

async function saveProfile(): Promise<boolean> {
  if (!profileLoaded.value) {
    setProfileSaveMessage(profileLoadError.value || '资料加载失败，请重试后再保存', true)
    return false
  }
  if (profileSaving.value) return false

  profileSaving.value = true
  try {
    const payload = {
      nickname: profile.value.nickname || '',
      bio: profile.value.bio || '',
      homepageUrl: profile.value.homepageUrl || '',
      location: profile.value.location || '',
      avatarUrl: profile.value.avatarUrl || '',
    }
    const data = await updateMyProfile(payload)
    if (!data) throw new Error('保存结果为空，请稍后重试')
    profile.value = normalizeProfile({ ...profile.value, ...data })
    profileBaseline.value = profileSnapshot(profile.value)
    avatarPreviewUrl.value = normalizeImageUrl(profile.value.avatarUrl || '')
    try {
      window.dispatchEvent(new CustomEvent('profile-updated', { detail: data }))
    } catch {}
    setProfileSaveMessage('保存成功')
    return true
  } catch (error: unknown) {
    setProfileSaveMessage(getErrorMessage(error, '保存失败'), true)
    return false
  } finally {
    profileSaving.value = false
  }
}

async function onAvatarSelected(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement | null
  avatarUploadError.value = ''
  try {
    const file = target?.files?.[0]
    if (!file) return
    if (!profileLoaded.value) {
      avatarUploadError.value = profileLoadError.value || '资料加载失败，请重试后再上传头像'
      return
    }
    if (!file.type.startsWith('image/')) {
      avatarUploadError.value = '请上传图片文件'
      return
    }
    if (file.size > 2 * 1024 * 1024) {
      avatarUploadError.value = '图片过大（>2MB），请压缩后再试'
      return
    }

    const accessToken = token.value || getStoredAccessToken()
    if (!hasRealToken(accessToken)) {
      avatarUploadError.value = '请登录后再上传头像'
      return
    }

    avatarUploading.value = true
    avatarProgress.value = 0
    const response: UploadImageResponse = await uploadImage(file, accessToken, (progress) => {
      avatarProgress.value = progress
    })
    const url = response?.url || response?.path || ''
    if (!url) {
      avatarUploadError.value = '头像上传未返回有效地址'
      return
    }
    profile.value.avatarUrl = String(url)
    avatarPreviewUrl.value = normalizeImageUrl(String(url))
    await saveProfile()
  } catch (error: unknown) {
    avatarUploadError.value = getErrorMessage(error, '头像上传失败')
  } finally {
    avatarUploading.value = false
    avatarProgress.value = 0
    if (target) target.value = ''
  }
}

onMounted(() => {
  void loadProfile()
})
</script>

<template>
  <div class="space-y-5">
    <div v-if="profileLoadError" role="alert" aria-live="assertive" class="flex flex-col gap-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300 sm:flex-row sm:items-center sm:justify-between">
      <span>{{ profileLoadError }}</span>
      <button type="button" class="self-start rounded border border-red-300 px-2.5 py-1 font-medium hover:bg-red-100 disabled:opacity-60 dark:border-red-800 dark:hover:bg-red-900/40 sm:self-auto" :disabled="profileLoading" @click="loadProfile">
        {{ profileLoading ? '重试中...' : '重试' }}
      </button>
    </div>

    <div v-if="profileLoading && !profileLoaded" class="text-xs text-gray-500 dark:text-gray-400">正在加载资料...</div>

    <section>
      <fieldset :disabled="!profileLoaded || profileLoading" class="space-y-4">
        <legend class="text-sm font-semibold text-gray-900 dark:text-gray-50">基本资料</legend>
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
              <div v-if="avatarUploading" role="status" aria-live="polite" class="mt-2 text-xs text-gray-600 dark:text-gray-300">上传中 {{ avatarProgress }}%</div>
            </div>
          </div>
          <label for="settings-profile-avatar" class="inline-flex cursor-pointer items-center justify-center rounded-md border border-gray-200 px-3 py-2 text-xs font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200 dark:hover:bg-gray-700">
            更换头像
            <input id="settings-profile-avatar" type="file" accept="image/*" class="hidden" @change="onAvatarSelected" />
          </label>
        </div>
        <p v-if="avatarUploadError" id="settings-profile-avatar-error" role="alert" aria-live="assertive" class="text-xs text-red-600 dark:text-red-400">{{ avatarUploadError }}</p>

        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <label for="settings-profile-nickname" class="text-xs font-medium text-gray-700 dark:text-gray-200">昵称
            <input id="settings-profile-nickname" v-model="profile.nickname" class="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="昵称" />
          </label>
          <label for="settings-profile-homepage" class="text-xs font-medium text-gray-700 dark:text-gray-200">主页链接
            <input id="settings-profile-homepage" v-model="profile.homepageUrl" class="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="https://..." />
          </label>
          <label for="settings-profile-location" class="text-xs font-medium text-gray-700 dark:text-gray-200">所在地
            <input id="settings-profile-location" v-model="profile.location" class="mt-1 w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-brandDay-600 focus:outline-none focus:ring-1 focus:ring-brandDay-600 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-100 dark:focus:border-accentCyan-400 dark:focus:ring-accentCyan-400" placeholder="城市" />
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
            :preview-default-collapsed="true"
            :mobile-preview-exclusive="true"
            placeholder="介绍一下自己吧，支持 Markdown"
            preview-label="简介预览"
          />
          <p v-if="bioUploadError" id="settings-profile-bio-upload-error" role="alert" aria-live="assertive" class="mt-2 text-xs text-red-600 dark:text-red-400">{{ bioUploadError }}</p>
        </div>
      </fieldset>
      <div class="mt-4 flex flex-col items-stretch gap-3 sm:flex-row sm:items-center sm:justify-end">
        <span v-if="profileSaveMessage" role="status" aria-live="polite" :class="['text-xs', profileSaveError ? 'text-red-600 dark:text-red-400' : 'text-green-600 dark:text-green-400']">{{ profileSaveMessage }}</span>
        <button
          type="button"
          class="rounded-md bg-brandDay-600 px-4 py-2 text-xs font-medium text-white shadow-sm transition-shadow duration-200 hover:bg-brandDay-700 hover:shadow-md focus:outline-none focus:ring-2 focus:ring-brandDay-600 disabled:opacity-60 dark:bg-brandNight-600 dark:hover:bg-brandNight-700 dark:focus:ring-accentCyan-400"
          :disabled="profileSaving || !profileLoaded || profileLoading"
          @click="saveProfile"
        >
          {{ profileSaving ? '保存中...' : '保存资料' }}
        </button>
      </div>
    </section>

    <section>
      <label for="settings-profile-dynamic-background" class="flex items-center justify-between gap-4">
        <span class="text-sm font-semibold text-gray-900 dark:text-gray-50">动态背景效果</span>
        <input id="settings-profile-dynamic-background" type="checkbox" :checked="dynamicBackgroundEnabled" @change="onDynamicBackgroundChange" />
      </label>
    </section>
  </div>
</template>

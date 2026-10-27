<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useUIStore } from '@/stores/ui'
import { getMyProfile, updateMyProfile } from '@/api/settings'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { normalizeImageUrl } from '@/utils/image'
import MarkdownTextareaEditor from '@/components/MarkdownTextareaEditor.vue'
import { getStoredAccessToken, hasRealToken } from '@/utils/authStorage'
import type { Id, ProfileUpdatedDetail, UserProfile } from '@/types'
import type { UploadImageResponse } from '@/api/uploads'

interface ProfileForm extends UserProfile {
  username?: string
}

interface SessionContext {
  token: string
  epoch: number
  ownerId: Id | null
}

interface SaveProfileOptions {
  context?: SessionContext
}

const PROFILE_FIELDS = ['nickname', 'bio', 'homepageUrl', 'location', 'avatarUrl'] as const
type ProfileField = typeof PROFILE_FIELDS[number]

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
const { token, user } = storeToRefs(authStore)

let disposed = false
let sessionEpoch = 0
let profileLoadSeq = 0
let profileSaveSeq = 0
let avatarUploadSeq = 0
let bioUploadSeq = 0
let reloadTimer: ReturnType<typeof setTimeout> | null = null
let profileSaveMessageTimer: ReturnType<typeof setTimeout> | null = null
let observedToken = ''
let observedStoredToken = ''
let observedOwnerKey: string | null = null

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

function normalizeId(value: unknown): Id | null {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0 ? value : null
  if (typeof value === 'string' && value.trim()) return value.trim()
  return null
}

function idKey(value: unknown): string | null {
  const normalized = normalizeId(value)
  return normalized === null ? null : String(normalized)
}

function currentOwnerId(): Id | null {
  return normalizeId(user.value?.id ?? user.value?.userId)
}

function currentToken(): string {
  return String(token.value || '').trim()
}

function storedToken(): string {
  return String(getStoredAccessToken() || '').trim()
}

function captureSession(): SessionContext | null {
  const accessToken = currentToken()
  if (!hasRealToken(accessToken) || accessToken !== storedToken()) return null
  return { token: accessToken, epoch: sessionEpoch, ownerId: currentOwnerId() }
}

function sameOwner(expected: Id | null, actual: Id | null): boolean {
  // Header hydration fills in an identity after a token-only session starts.
  // Keeping an unknown owner current lets that same-token hydration complete.
  if (expected === null) return true
  return actual !== null && idKey(expected) === idKey(actual)
}

function isCurrentSession(context: SessionContext): boolean {
  return !disposed
    && context.epoch === sessionEpoch
    && currentToken() === context.token
    && storedToken() === context.token
    && sameOwner(context.ownerId, currentOwnerId())
}

function isCurrentOperation(context: SessionContext, sequence: number, currentSequence: number): boolean {
  return isCurrentSession(context) && sequence === currentSequence
}

function clearProfileSaveMessageTimer(): void {
  if (profileSaveMessageTimer !== null) {
    clearTimeout(profileSaveMessageTimer)
    profileSaveMessageTimer = null
  }
}

function setProfileSaveMessage(message: string, isError = false): void {
  if (disposed) return
  clearProfileSaveMessageTimer()
  profileSaveError.value = isError
  profileSaveMessage.value = message
  const messageEpoch = sessionEpoch
  profileSaveMessageTimer = setTimeout(() => {
    profileSaveMessageTimer = null
    if (disposed || messageEpoch !== sessionEpoch) return
    profileSaveMessage.value = ''
    profileSaveError.value = false
  }, isError ? 4000 : 3000)
}

function invalidateOperations(): void {
  profileLoadSeq += 1
  profileSaveSeq += 1
  avatarUploadSeq += 1
  bioUploadSeq += 1
  sessionEpoch += 1
}

function resetProfileStateForSession(): void {
  invalidateOperations()
  profile.value = { ...EMPTY_PROFILE }
  profileBaseline.value = profileSnapshot(profile.value)
  profileLoading.value = false
  profileLoaded.value = false
  profileLoadError.value = ''
  profileSaving.value = false
  avatarUploading.value = false
  avatarProgress.value = 0
  avatarPreviewUrl.value = ''
  avatarUploadError.value = ''
  bioUploading.value = false
  bioUploadProgress.value = 0
  bioUploadError.value = ''
  clearProfileSaveMessageTimer()
  profileSaveMessage.value = ''
  profileSaveError.value = false
}

function scheduleProfileReload(): void {
  if (disposed || reloadTimer !== null) return
  reloadTimer = setTimeout(() => {
    reloadTimer = null
    if (!disposed && captureSession()) void loadProfile()
  }, 0)
}

function observeSessionChange(): boolean {
  const nextToken = currentToken()
  const nextStoredToken = storedToken()
  const nextOwnerKey = idKey(currentOwnerId())
  const tokenChanged = nextToken !== observedToken || nextStoredToken !== observedStoredToken
  const sameTokenHydration = !tokenChanged && observedOwnerKey === null && nextOwnerKey !== null
  const ownerChanged = !sameTokenHydration && nextOwnerKey !== observedOwnerKey
  observedToken = nextToken
  observedStoredToken = nextStoredToken
  observedOwnerKey = nextOwnerKey
  return tokenChanged || ownerChanged
}

function handleSessionSignal(): void {
  if (disposed || !observeSessionChange()) return
  resetProfileStateForSession()
  scheduleProfileReload()
}

function forceAuthSessionSignal(): void {
  if (disposed) return
  if (reloadTimer !== null) {
    clearTimeout(reloadTimer)
    reloadTimer = null
  }
  resetProfileStateForSession()
  scheduleProfileReload()
}

async function uploadBioImage(file: File): Promise<string> {
  bioUploadError.value = ''
  if (!profileLoaded.value) {
    bioUploadError.value = profileLoadError.value || '资料尚未加载成功，请重试后再上传图片'
    return ''
  }
  if (profileSaving.value || avatarUploading.value || bioUploading.value) return ''

  const context = captureSession()
  if (!context) {
    bioUploadError.value = '请登录后再上传图片'
    return ''
  }

  const sequence = ++bioUploadSeq
  bioUploading.value = true
  bioUploadProgress.value = 0
  try {
    const { url, path } = await uploadImage(file, context.token, (progress) => {
      if (isCurrentOperation(context, sequence, bioUploadSeq)) {
        bioUploadProgress.value = Number.isFinite(progress) ? Math.max(0, Math.min(100, progress)) : 0
      }
    })
    if (!isCurrentOperation(context, sequence, bioUploadSeq)) return ''
    const normalizedUrl = normalizeImageUrl(url || path || '')
    if (!normalizedUrl) {
      bioUploadError.value = '图片上传未返回有效地址'
    }
    return normalizedUrl
  } catch (error: unknown) {
    if (!isCurrentOperation(context, sequence, bioUploadSeq)) return ''
    bioUploadError.value = getErrorMessage(error, '图片上传失败')
    return ''
  } finally {
    if (isCurrentOperation(context, sequence, bioUploadSeq)) {
      bioUploading.value = false
      bioUploadProgress.value = 0
    }
  }
}

async function loadProfile(): Promise<void> {
  const sequence = ++profileLoadSeq
  const context = captureSession()
  if (!context) {
    if (!disposed && sequence === profileLoadSeq) {
      profileLoading.value = false
      profileLoaded.value = false
      profileLoadError.value = '请登录后再加载资料'
    }
    return
  }

  profileLoading.value = true
  profileLoadError.value = ''
  try {
    const data = await getMyProfile()
    if (!isCurrentOperation(context, sequence, profileLoadSeq)) return
    if (!data || typeof data !== 'object' || Object.keys(data).length === 0) throw new Error('资料数据为空，请稍后重试')
    const responseOwner = normalizeId(data.id ?? data.userId)
    if (context.ownerId !== null && responseOwner !== null && idKey(context.ownerId) !== idKey(responseOwner)) {
      throw new Error('资料响应与当前账号不匹配，请重试')
    }
    profile.value = normalizeProfile(data)
    profileBaseline.value = profileSnapshot(profile.value)
    avatarPreviewUrl.value = normalizeImageUrl(profile.value.avatarUrl || '')
    profileLoaded.value = true
  } catch (error: unknown) {
    if (!isCurrentOperation(context, sequence, profileLoadSeq)) return
    profileLoaded.value = false
    profileLoadError.value = getErrorMessage(error, '资料加载失败，请重试')
  } finally {
    if (isCurrentOperation(context, sequence, profileLoadSeq)) profileLoading.value = false
  }
}

function profilePayload(value: Partial<ProfileForm>): Record<ProfileField, string> {
  return {
    nickname: String(value.nickname || ''),
    bio: String(value.bio || ''),
    homepageUrl: String(value.homepageUrl || ''),
    location: String(value.location || ''),
    avatarUrl: String(value.avatarUrl || ''),
  }
}

function mergeProfileAfterSave(
  server: ProfileForm,
  current: ProfileForm,
  submitted: Record<ProfileField, string>,
): ProfileForm {
  const merged: Partial<ProfileForm> = { ...server }
  PROFILE_FIELDS.forEach((field) => {
    if (String(current[field] || '') !== submitted[field]) merged[field] = String(current[field] || '')
  })
  return normalizeProfile(merged)
}

function profileUpdatedDetail(data: Partial<ProfileForm> | null | undefined, server: ProfileForm, context: SessionContext): ProfileUpdatedDetail | null {
  const id = normalizeId(data?.id ?? data?.userId ?? context.ownerId)
  if (id === null) return null
  const detail: ProfileUpdatedDetail = {
    id,
    nickname: server.nickname,
    avatarUrl: server.avatarUrl,
  }
  if (typeof server.username === 'string') detail.username = server.username
  const userId = normalizeId(data?.userId)
  if (userId !== null) detail.userId = userId
  return detail
}

async function saveProfile(options: SaveProfileOptions = {}): Promise<boolean> {
  if (!profileLoaded.value) {
    setProfileSaveMessage(profileLoadError.value || '资料加载失败，请重试后再保存', true)
    return false
  }
  if (profileSaving.value) return false
  if (bioUploading.value || avatarUploading.value) return false

  const context = options.context || captureSession()
  if (!context || !isCurrentSession(context)) {
    setProfileSaveMessage('请登录后再保存资料', true)
    return false
  }

  const sequence = ++profileSaveSeq
  const submitted = profilePayload(profile.value)
  profileSaving.value = true
  clearProfileSaveMessageTimer()
  profileSaveMessage.value = ''
  profileSaveError.value = false
  try {
    const data = await updateMyProfile(submitted)
    if (!isCurrentOperation(context, sequence, profileSaveSeq)) return false
    if (!data || typeof data !== 'object') throw new Error('保存结果为空，请稍后重试')
    const server = normalizeProfile({ ...profile.value, ...submitted, ...data })
    const current = normalizeProfile(profile.value)
    profile.value = mergeProfileAfterSave(server, current, submitted)
    profileBaseline.value = profileSnapshot(server)
    avatarPreviewUrl.value = normalizeImageUrl(profile.value.avatarUrl || '')
    const serverId = normalizeId(data.id ?? data.userId)
    const serverUsername = typeof data.username === 'string' ? data.username.trim() : ''
    if (context.ownerId === null && serverId !== null && serverUsername) {
      authStore.hydrateCurrentUser({ ...data, id: serverId, username: serverUsername }, context.token)
    }
    const detail = profileUpdatedDetail(data, server, context)
    if (detail) {
      try {
        window.dispatchEvent(new CustomEvent('profile-updated', { detail }))
      } catch {}
    }
    setProfileSaveMessage('保存成功')
    return true
  } catch (error: unknown) {
    if (!isCurrentOperation(context, sequence, profileSaveSeq)) return false
    setProfileSaveMessage(getErrorMessage(error, '保存失败'), true)
    return false
  } finally {
    if (isCurrentOperation(context, sequence, profileSaveSeq)) profileSaving.value = false
  }
}

async function onAvatarSelected(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement | null
  if (profileSaving.value || avatarUploading.value || bioUploading.value) return
  avatarUploadError.value = ''
  let context: SessionContext | null = null
  let uploadSequence = 0
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

    context = captureSession()
    if (!context) {
      avatarUploadError.value = '请登录后再上传头像'
      return
    }

    uploadSequence = ++avatarUploadSeq
    const profileAtUploadStart = normalizeProfile(profile.value)
    avatarUploading.value = true
    avatarProgress.value = 0
    const uploadContext = context
    const response: UploadImageResponse = await uploadImage(file, uploadContext.token, (progress) => {
      if (isCurrentOperation(uploadContext, uploadSequence, avatarUploadSeq)) {
        avatarProgress.value = Number.isFinite(progress) ? Math.max(0, Math.min(100, progress)) : 0
      }
    })
    if (!isCurrentOperation(uploadContext, uploadSequence, avatarUploadSeq)) return
    const url = response?.url || response?.path || ''
    if (!url) {
      avatarUploadError.value = '头像上传未返回有效地址'
      return
    }
    const current = normalizeProfile(profile.value)
    profile.value = normalizeProfile({ ...profileAtUploadStart, ...current, avatarUrl: String(url) })
    avatarPreviewUrl.value = normalizeImageUrl(String(url))
    avatarUploading.value = false
    await saveProfile({ context: uploadContext })
  } catch (error: unknown) {
    if (context && isCurrentOperation(context, uploadSequence, avatarUploadSeq)) {
      avatarUploadError.value = getErrorMessage(error, '头像上传失败')
    }
  } finally {
    // A stale upload must not clear the next account's progress or flags.
    if (context && isCurrentOperation(context, uploadSequence, avatarUploadSeq)) {
      avatarUploading.value = false
      avatarProgress.value = 0
      if (target) target.value = ''
    }
  }
}

function onAuthStateChanged(): void {
  if (disposed) return
  // Identity hydration changes the user without emitting an auth event.
  // An explicit auth event starts a new context even if its token is reused.
  observeSessionChange()
  forceAuthSessionSignal()
}

watch(
  [() => currentToken(), () => storedToken(), () => idKey(currentOwnerId())],
  () => handleSessionSignal(),
  { flush: 'sync' },
)

onMounted(() => {
  observedToken = currentToken()
  observedStoredToken = storedToken()
  observedOwnerKey = idKey(currentOwnerId())
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  void loadProfile()
})

onBeforeUnmount(() => {
  disposed = true
  if (reloadTimer !== null) {
    clearTimeout(reloadTimer)
    reloadTimer = null
  }
  clearProfileSaveMessageTimer()
  invalidateOperations()
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>

<template>
  <div class="space-y-5">
    <div v-if="profileLoadError" role="alert" aria-live="assertive" class="ui-error flex flex-col gap-3 text-sm sm:flex-row sm:items-center sm:justify-between">
      <span>{{ profileLoadError }}</span>
      <button type="button" class="ui-button self-start px-3 py-1.5 text-xs sm:self-auto" :disabled="profileLoading" @click="loadProfile">
        {{ profileLoading ? '重试中...' : '重试' }}
      </button>
    </div>

    <div v-if="profileLoading && !profileLoaded" class="ui-muted text-sm" role="status" aria-live="polite">正在加载资料...</div>

    <section class="space-y-4 border-b border-gray-200 pb-6 dark:border-gray-700">
      <fieldset :disabled="!profileLoaded || profileLoading" class="space-y-4">
        <legend class="text-base font-semibold">基本资料</legend>
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
          <label for="settings-profile-avatar" class="ui-button inline-flex cursor-pointer px-3 py-2 text-xs">
            更换头像
            <input id="settings-profile-avatar" type="file" accept="image/*" class="hidden" :disabled="profileSaving || avatarUploading || bioUploading || !profileLoaded || profileLoading" @change="onAvatarSelected" />
          </label>
        </div>
        <p v-if="avatarUploadError" id="settings-profile-avatar-error" role="alert" aria-live="assertive" class="text-xs text-red-600 dark:text-red-400">{{ avatarUploadError }}</p>

        <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
          <label for="settings-profile-nickname" class="text-sm font-medium">昵称
            <input id="settings-profile-nickname" v-model="profile.nickname" class="ui-field mt-1 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="昵称" />
          </label>
          <label for="settings-profile-homepage" class="text-sm font-medium">主页链接
            <input id="settings-profile-homepage" v-model="profile.homepageUrl" class="ui-field mt-1 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="https://..." />
          </label>
          <label for="settings-profile-location" class="text-sm font-medium">所在地
            <input id="settings-profile-location" v-model="profile.location" class="ui-field mt-1 text-sm focus:outline-none focus:ring-2 focus:ring-[var(--ui-accent-soft)]" placeholder="城市" />
          </label>
        </div>
        <div class="text-sm">
          <div class="font-medium">个人简介</div>
          <div class="mt-1 flex items-center justify-between gap-2">
              <span class="ui-muted text-xs">字数：{{ String(profile.bio || '').length }}/{{ bioMax }}</span>
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
        <span v-if="profileSaveMessage" role="status" aria-live="polite" :class="['text-xs sm:mr-auto', profileSaveError ? 'text-red-600 dark:text-red-400' : 'text-emerald-700 dark:text-emerald-300']">{{ profileSaveMessage }}</span>
        <button
          type="button"
          class="ui-button-primary"
          :disabled="profileSaving || avatarUploading || bioUploading || !profileLoaded || profileLoading"
          @click="saveProfile()"
        >
          {{ profileSaving ? '保存中...' : '保存资料' }}
        </button>
      </div>
    </section>

    <section class="pt-1">
      <label for="settings-profile-dynamic-background" class="flex items-center justify-between gap-4 text-sm">
        <span class="font-semibold">动态背景效果</span>
        <input id="settings-profile-dynamic-background" type="checkbox" :checked="dynamicBackgroundEnabled" @change="onDynamicBackgroundChange" />
      </label>
    </section>
  </div>
</template>

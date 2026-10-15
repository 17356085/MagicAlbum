import { onBeforeUnmount, ref, type Ref, watch } from 'vue'
import { getMyProfile } from '@/api/settings'
import type { ProfileUpdatedDetail, User } from '@/types'

interface HeaderIdentityOptions {
  isLoggedIn: Readonly<Ref<boolean>>
  user: Readonly<Ref<User | null>>
  updateCurrentUser: (patch: Partial<User>) => void
}

export function useHeaderIdentity({ isLoggedIn, user, updateCurrentUser }: HeaderIdentityOptions) {
  const avatarUrl = ref('')
  const displayName = ref('')
  const profileHydrated = ref(false)
  let profileRequestGeneration = 0
  let disposed = false

  function accountIdentity(): string {
    if (!isLoggedIn.value) return 'logged-out'
    const currentUser = user.value
    const id = currentUser?.id ?? currentUser?.userId
    if (id !== undefined && id !== null) return `id:${String(id)}`
    return `username:${String(currentUser?.username || '').trim().toLowerCase()}`
  }

  function isCurrentProfileRequest(generation: number, identity: string): boolean {
    return !disposed && isLoggedIn.value && profileRequestGeneration === generation && accountIdentity() === identity
  }

  watch(
    accountIdentity,
    () => {
      profileRequestGeneration += 1
    },
    { flush: 'sync' },
  )

  onBeforeUnmount(() => {
    disposed = true
    profileRequestGeneration += 1
  })

  function applyUserIdentity(): void {
    const currentUser = user.value
    avatarUrl.value = currentUser?.avatarUrl || ''
    displayName.value =
      (currentUser?.nickname && String(currentUser.nickname).trim()) ||
      currentUser?.username ||
      ''
  }

  function needsProfileHydration(): boolean {
    if (!isLoggedIn.value) return false
    if (!user.value?.id && !user.value?.userId) return true
    const currentAvatar = String(user.value.avatarUrl || '').trim()
    const currentNickname = String(user.value.nickname || '').trim()
    return !currentAvatar || !currentNickname
  }

  async function refreshMyProfile(): Promise<void> {
    const requestGeneration = ++profileRequestGeneration
    if (disposed) return
    if (!isLoggedIn.value) {
      resetIdentity()
      return
    }

    const requestIdentity = accountIdentity()
    applyUserIdentity()
    if (!needsProfileHydration()) {
      profileHydrated.value = true
      return
    }

    try {
      const profile = await getMyProfile()
      if (!isCurrentProfileRequest(requestGeneration, requestIdentity)) return
      avatarUrl.value = profile?.avatarUrl || avatarUrl.value || ''
      displayName.value =
        (profile?.nickname && String(profile.nickname).trim()) ||
        displayName.value ||
        (user.value?.username || '')
      if (profile?.id || profile?.userId || profile?.username || profile?.avatarUrl || profile?.nickname) {
        updateCurrentUser({
          id: profile?.id || profile?.userId || user.value?.id,
          userId: profile?.userId || profile?.id || user.value?.userId,
          username: profile?.username || user.value?.username || '',
          avatarUrl: profile?.avatarUrl || user.value?.avatarUrl || '',
          nickname: profile?.nickname || user.value?.nickname || '',
        })
      }
      profileHydrated.value = true
    } catch (_) {}
  }

  function resetIdentity(): void {
    profileRequestGeneration += 1
    avatarUrl.value = ''
    displayName.value = ''
    profileHydrated.value = false
  }

  function onProfileUpdated(evt: Event): void {
    if (disposed || !isLoggedIn.value) return
    const detail = (evt as CustomEvent<ProfileUpdatedDetail>).detail || {}
    const nextAvatar = detail.avatarUrl
    const nextNickname = detail.nickname
    if (typeof nextAvatar === 'string') avatarUrl.value = nextAvatar
    if (typeof nextNickname === 'string') displayName.value = nextNickname || (user.value?.username || '')
    if (typeof nextAvatar === 'string' || typeof nextNickname === 'string') {
      updateCurrentUser({
        avatarUrl: typeof nextAvatar === 'string' ? nextAvatar : (user.value?.avatarUrl || ''),
        nickname: typeof nextNickname === 'string' ? nextNickname : (user.value?.nickname || ''),
      })
    }
  }

  return {
    avatarUrl,
    displayName,
    profileHydrated,
    applyUserIdentity,
    needsProfileHydration,
    refreshMyProfile,
    resetIdentity,
    onProfileUpdated,
  }
}

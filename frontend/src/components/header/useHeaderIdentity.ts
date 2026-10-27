import { onBeforeUnmount, ref, type Ref, watch } from 'vue'
import { getMyProfile } from '@/api/settings'
import type { ProfileUpdatedDetail, User } from '@/types'

interface HeaderIdentityOptions {
  isLoggedIn: Readonly<Ref<boolean>>
  token: Readonly<Ref<string>>
  user: Readonly<Ref<User | null>>
  updateCurrentUser: (patch: Partial<User>) => void
  hydrateCurrentUser: (patch: Partial<User>, expectedToken: string) => boolean
}

interface ProfileRequest {
  token: string
  identity: string
  expectedHydratedIdentity: string
  generation: number
  promise: Promise<void>
}

export function useHeaderIdentity({ isLoggedIn, token, user, updateCurrentUser, hydrateCurrentUser }: HeaderIdentityOptions) {
  const avatarUrl = ref('')
  const displayName = ref('')
  const profileHydrated = ref(false)
  let profileRequestGeneration = 0
  let disposed = false
  let profileRequest: ProfileRequest | null = null
  let hydratingRequest: ProfileRequest | null = null

  function hasKnownId(value: unknown): boolean {
    return value !== undefined && value !== null && String(value).trim() !== ''
  }

  function identityFor(id: unknown, userId: unknown, username: unknown): string {
    const knownId = hasKnownId(id) ? id : userId
    if (hasKnownId(knownId)) return `id:${String(knownId)}`
    return `username:${String(username || '').trim().toLowerCase()}`
  }

  function accountIdentity(): string {
    if (!isLoggedIn.value) return 'logged-out'
    const currentUser = user.value
    return identityFor(currentUser?.id, currentUser?.userId, currentUser?.username)
  }

  function isCurrentProfileRequest(request: ProfileRequest): boolean {
    return !disposed &&
      isLoggedIn.value &&
      String(token.value || '') === request.token &&
      profileRequestGeneration === request.generation &&
      accountIdentity() === request.identity
  }

  watch(
    [() => String(token.value || ''), accountIdentity],
    () => {
      profileRequestGeneration += 1

      // Hydrating a token-only session creates the user object synchronously.
      // Keep that request current after accepting its new, server-confirmed
      // identity so the session watcher can coalesce instead of refetching.
      if (hydratingRequest
        && String(token.value || '') === hydratingRequest.token
        && accountIdentity() === hydratingRequest.expectedHydratedIdentity) {
        hydratingRequest.identity = accountIdentity()
        hydratingRequest.generation = profileRequestGeneration
      }
    },
    { flush: 'sync' },
  )

  onBeforeUnmount(() => {
    disposed = true
    profileRequestGeneration += 1
    profileRequest = null
    hydratingRequest = null
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
    if (!hasKnownId(user.value?.id) && !hasKnownId(user.value?.userId)) return true
    const currentAvatar = String(user.value?.avatarUrl || '').trim()
    const currentNickname = String(user.value?.nickname || '').trim()
    return !currentAvatar || !currentNickname
  }

  function profilePatch(profile: Awaited<ReturnType<typeof getMyProfile>>): Partial<User> {
    const currentUser = user.value
    return {
      id: profile?.id ?? profile?.userId ?? currentUser?.id ?? currentUser?.userId,
      userId: profile?.userId ?? profile?.id ?? currentUser?.userId ?? currentUser?.id,
      username: profile?.username ?? currentUser?.username ?? '',
      avatarUrl: profile?.avatarUrl ?? currentUser?.avatarUrl ?? '',
      nickname: profile?.nickname ?? currentUser?.nickname ?? '',
    }
  }

  function applyHydratedIdentity(profile: Awaited<ReturnType<typeof getMyProfile>>): void {
    const currentUser = user.value
    avatarUrl.value = profile?.avatarUrl ?? currentUser?.avatarUrl ?? ''
    displayName.value =
      (String(profile?.nickname || '').trim()) ||
      (String(currentUser?.nickname || '').trim()) ||
      currentUser?.username ||
      profile?.username ||
      ''
  }

  async function runProfileRequest(request: ProfileRequest): Promise<void> {
    try {
      const profile = await getMyProfile()
      if (!isCurrentProfileRequest(request)) return

      const patch = profilePatch(profile)
      request.expectedHydratedIdentity = identityFor(patch.id, patch.userId, patch.username)
      hydratingRequest = request
      let accepted = false
      try {
        accepted = hydrateCurrentUser(patch, request.token)
      } finally {
        if (hydratingRequest === request) hydratingRequest = null
      }
      if (!accepted || !isCurrentProfileRequest(request)) return

      applyHydratedIdentity(profile)
      profileHydrated.value = true
    } catch (_) {
      // A failed current-session refresh leaves hydration pending. Stale
      // failures have no state to clear and cannot affect a newer request.
    } finally {
      if (profileRequest === request) profileRequest = null
    }
  }

  function refreshMyProfile(force = false): Promise<void> {
    if (disposed) return Promise.resolve()
    if (!isLoggedIn.value) {
      resetIdentity()
      return Promise.resolve()
    }

    const requestToken = String(token.value || '')
    const requestIdentity = accountIdentity()
    const currentGeneration = profileRequestGeneration
    if (profileRequest
      && profileRequest.token === requestToken
      && profileRequest.identity === requestIdentity
      && profileRequest.generation === currentGeneration) {
      return profileRequest.promise
    }

    applyUserIdentity()
    if (!force && !needsProfileHydration()) {
      profileHydrated.value = true
      return Promise.resolve()
    }

    profileHydrated.value = false
    const requestGeneration = ++profileRequestGeneration
    const request: ProfileRequest = {
      token: requestToken,
      identity: requestIdentity,
      expectedHydratedIdentity: requestIdentity,
      generation: requestGeneration,
      promise: Promise.resolve(),
    }
    request.promise = runProfileRequest(request)
    profileRequest = request
    return request.promise
  }

  function resetIdentity(): void {
    profileRequestGeneration += 1
    profileRequest = null
    hydratingRequest = null
    avatarUrl.value = ''
    displayName.value = ''
    profileHydrated.value = false
  }

  function onProfileUpdated(evt: Event): void {
    if (disposed || !isLoggedIn.value) return
    const detail = (evt as CustomEvent<ProfileUpdatedDetail>).detail
    const ownerId = user.value?.id ?? user.value?.userId
    if (ownerId == null || detail?.id == null || String(detail.id) !== String(ownerId)) return
    const nextAvatar = detail.avatarUrl
    const nextNickname = detail.nickname
    const nextUsername = typeof detail.username === 'string' ? detail.username.trim() : ''
    const includesProfile = typeof nextAvatar === 'string' || typeof nextNickname === 'string'
    if (includesProfile || nextUsername) {
      // A save is newer than any profile GET already in flight.
      profileRequestGeneration += 1
      profileRequest = null
      hydratingRequest = null
      updateCurrentUser({
        ...(nextUsername ? { username: nextUsername } : {}),
        avatarUrl: typeof nextAvatar === 'string' ? nextAvatar : (user.value?.avatarUrl || ''),
        nickname: typeof nextNickname === 'string' ? nextNickname : (user.value?.nickname || ''),
      })
      applyUserIdentity()
      if (includesProfile) profileHydrated.value = true
      // Basic account saves only confirm the username. Finish any outstanding
      // profile hydration with a new read instead of accepting its older GET.
      else if (!profileHydrated.value) void refreshMyProfile(true)
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

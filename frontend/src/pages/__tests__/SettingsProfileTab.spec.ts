import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SettingsProfileTab from '../settings/SettingsProfileTab.vue'
import { getMyProfile, updateMyProfile } from '@/api/settings'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'

const mocks = vi.hoisted(() => ({
  getMyProfile: vi.fn(),
  updateMyProfile: vi.fn(),
  uploadImage: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getMyProfile: mocks.getMyProfile,
  updateMyProfile: mocks.updateMyProfile,
}))
vi.mock('@/api/uploads', () => ({ uploadImage: mocks.uploadImage }))

const getMyProfileMock = vi.mocked(getMyProfile)
const updateMyProfileMock = vi.mocked(updateMyProfile)
const uploadImageMock = vi.mocked(uploadImage)

const profile = {
  id: 1,
  userId: 1,
  username: 'alice',
  nickname: '旧昵称',
  bio: '旧简介',
  homepageUrl: 'https://example.com',
  location: '香港',
  avatarUrl: '',
}

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

const wrappers: Array<{ unmount: () => void }> = []

function createWrapper() {
  const wrapper = mount(SettingsProfileTab, {
    global: {
      stubs: {
        MarkdownTextareaEditor: {
          props: ['modelValue', 'uploadImage', 'uploading'],
          template: '<div><textarea data-test="bio-editor" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /><button type="button" data-test="bio-upload" @click="uploadImage(null)">上传简介图片</button></div>',
        },
      },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}

describe('SettingsProfileTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    getMyProfileMock.mockResolvedValue({ ...profile })
    updateMyProfileMock.mockResolvedValue({ ...profile })
    uploadImageMock.mockResolvedValue({ url: 'https://cdn.example/avatar.png' })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => {
      wrapper.unmount()
    })
    vi.useRealTimers()
  })

  it('shows a retryable load error and blocks profile saves until loading succeeds', async () => {
    getMyProfileMock.mockRejectedValueOnce(new Error('资料服务不可用'))
    const wrapper = createWrapper()
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('资料服务不可用')
    expect(wrapper.get('button').text()).toContain('重试')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!
    expect(saveButton.attributes('disabled')).toBeDefined()
    expect(updateMyProfileMock).not.toHaveBeenCalled()

    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('资料服务不可用')
    expect(saveButton.attributes('disabled')).toBeUndefined()
  })

  it('emits dirty changes, saves the edited profile, and reports upload errors inline', async () => {
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-profile-nickname').setValue('新昵称')
    expect(wrapper.emitted('dirty-change')).toContainEqual([true])

    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')
    await flushPromises()
    expect(updateMyProfileMock).toHaveBeenCalledWith({
      nickname: '新昵称',
      bio: '旧简介',
      homepageUrl: 'https://example.com',
      location: '香港',
      avatarUrl: '',
    })
    expect(wrapper.emitted('dirty-change')).toContainEqual([false])

    uploadImageMock.mockRejectedValueOnce(new Error('上传服务不可用'))
    const fileInput = wrapper.get('#settings-profile-avatar')
    Object.defineProperty(fileInput.element, 'files', {
      configurable: true,
      value: [new File(['avatar'], 'avatar.png', { type: 'image/png' })],
    })
    await fileInput.trigger('change')
    await flushPromises()

    expect(wrapper.get('#settings-profile-avatar-error').text()).toContain('上传服务不可用')
    expect(wrapper.find('[role="alert"]').text()).toContain('上传服务不可用')
  })

  it('does not use a stored token when the in-memory session has no real token', async () => {
    const auth = useAuthStore()
    auth.token = ''
    const wrapper = createWrapper()
    await flushPromises()

    expect(getMyProfileMock).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('请登录后再加载资料')
  })

  it('ignores a late save response after the tab unmounts', async () => {
    const response = deferred<typeof profile>()
    updateMyProfileMock.mockReturnValueOnce(response.promise)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-profile-nickname').setValue('提交中的昵称')
    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')

    wrapper.unmount()
    response.resolve({ ...profile, nickname: '提交中的昵称' })
    await flushPromises()

    expect(eventSpy).not.toHaveBeenCalled()
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('ignores a late save response after switching accounts', async () => {
    const response = deferred<typeof profile>()
    updateMyProfileMock.mockReturnValueOnce(response.promise)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-profile-nickname').setValue('账号A昵称')
    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')

    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    response.resolve({ ...profile, nickname: '账号A昵称' })
    await flushPromises()

    expect(eventSpy).not.toHaveBeenCalled()
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('')
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('invalidates a pending save when auth state is reapplied for the same token and owner', async () => {
    const response = deferred<typeof profile>()
    updateMyProfileMock.mockReturnValueOnce(response.promise)
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-profile-nickname').setValue('旧会话昵称')
    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')

    useAuthStore().applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    response.resolve({ ...profile, nickname: '旧会话昵称' })
    await flushPromises()

    expect(eventSpy).not.toHaveBeenCalled()
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('ignores an older profile GET after a token switch and applies the newer response', async () => {
    vi.useFakeTimers()
    const oldResponse = deferred<typeof profile>()
    const newResponse = deferred<typeof profile>()
    getMyProfileMock.mockReset()
    getMyProfileMock.mockReturnValueOnce(oldResponse.promise).mockReturnValueOnce(newResponse.promise)
    const wrapper = createWrapper()
    await flushPromises()

    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await vi.runOnlyPendingTimersAsync()
    expect(getMyProfileMock).toHaveBeenCalledTimes(2)

    oldResponse.resolve({ ...profile, nickname: '账号A旧资料' })
    await flushPromises()
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('')

    newResponse.resolve({ ...profile, id: 2, userId: 2, username: 'bob', nickname: '账号B资料' })
    await flushPromises()
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('账号B资料')
  })

  it('invalidates logout and ABA session requests while retaining the final account response', async () => {
    vi.useFakeTimers()
    const firstResponse = deferred<typeof profile>()
    const finalResponse = deferred<typeof profile>()
    getMyProfileMock.mockReset()
    getMyProfileMock.mockReturnValueOnce(firstResponse.promise).mockReturnValueOnce(finalResponse.promise)
    const wrapper = createWrapper()
    await flushPromises()

    const auth = useAuthStore()
    auth.logout()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'alice' } })
    await vi.runOnlyPendingTimersAsync()
    expect(getMyProfileMock).toHaveBeenCalledTimes(2)

    firstResponse.resolve({ ...profile, nickname: '注销前旧资料' })
    await flushPromises()
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('')

    finalResponse.resolve({ ...profile, nickname: 'ABA最终资料' })
    await flushPromises()
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('ABA最终资料')
  })

  it('does not leave loading stuck when an auth attempt only advances authGeneration', async () => {
    const response = deferred<typeof profile>()
    getMyProfileMock.mockReturnValueOnce(response.promise)
    const wrapper = createWrapper()
    await Promise.resolve()

    useAuthStore().invalidateAuthOperations()
    response.resolve({ ...profile })
    await flushPromises()

    expect(wrapper.text()).not.toContain('正在加载资料...')
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('旧昵称')
  })

  it('keeps dirty form edits when same-token identity hydration fills an unknown owner', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-profile-nickname').setValue('继续编辑')

    expect(auth.hydrateCurrentUser({ id: 1, username: 'alice' }, 'token-a')).toBe(true)
    await flushPromises()

    expect(getMyProfileMock).toHaveBeenCalledTimes(1)
    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('继续编辑')
    expect(wrapper.emitted('dirty-change')).toContainEqual([true])
  })

  it('hydrates a token-only owner before emitting a profile update with a required id', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: null })
    updateMyProfileMock.mockResolvedValueOnce({ ...profile, nickname: '新昵称' })
    const eventSpy = vi.fn()
    window.addEventListener('profile-updated', eventSpy)
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-profile-nickname').setValue('新昵称')
    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')
    await flushPromises()

    expect(auth.user).toEqual(expect.objectContaining({ id: 1, username: 'alice' }))
    expect(eventSpy).toHaveBeenCalledTimes(1)
    expect((eventSpy.mock.calls[0][0] as CustomEvent).detail).toEqual(expect.objectContaining({ id: 1 }))
    window.removeEventListener('profile-updated', eventSpy)
  })

  it('retains edits made while a save is in flight and sends them in the next payload', async () => {
    const response = deferred<typeof profile>()
    updateMyProfileMock.mockReturnValueOnce(response.promise).mockResolvedValueOnce({ ...profile, nickname: '新昵称', bio: '继续编辑' })
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('#settings-profile-nickname').setValue('新昵称')
    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')
    await wrapper.get('[data-test="bio-editor"]').setValue('继续编辑')
    response.resolve({ ...profile, nickname: '新昵称' })
    await flushPromises()

    expect((wrapper.get('#settings-profile-nickname').element as HTMLInputElement).value).toBe('新昵称')
    expect((wrapper.get('[data-test="bio-editor"]').element as HTMLTextAreaElement).value).toBe('继续编辑')
    expect(wrapper.emitted('dirty-change')).toContainEqual([true])

    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')
    await flushPromises()
    expect(updateMyProfileMock).toHaveBeenLastCalledWith(expect.objectContaining({
      nickname: '新昵称',
      bio: '继续编辑',
    }))
  })

  it('does not PATCH when an avatar upload resolves after a session switch', async () => {
    const uploadResponse = deferred<{ url: string }>()
    uploadImageMock.mockReturnValueOnce(uploadResponse.promise)
    const wrapper = createWrapper()
    await flushPromises()
    const fileInput = wrapper.get('#settings-profile-avatar')
    Object.defineProperty(fileInput.element, 'files', {
      configurable: true,
      value: [new File(['avatar'], 'avatar.png', { type: 'image/png' })],
    })
    await fileInput.trigger('change')

    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    uploadResponse.resolve({ url: 'https://cdn.example/stale.png' })
    await flushPromises()

    expect(updateMyProfileMock).not.toHaveBeenCalled()
  })

  it('does not start an avatar upload while a manual profile save is pending', async () => {
    const response = deferred<typeof profile>()
    updateMyProfileMock.mockReturnValueOnce(response.promise)
    const wrapper = createWrapper()
    await flushPromises()
    await wrapper.get('#settings-profile-nickname').setValue('保存中')
    await wrapper.findAll('button').find((button) => button.text().includes('保存资料'))!.trigger('click')

    const fileInput = wrapper.get('#settings-profile-avatar')
    expect(fileInput.attributes('disabled')).toBeDefined()
    Object.defineProperty(fileInput.element, 'files', {
      configurable: true,
      value: [new File(['avatar'], 'avatar.png', { type: 'image/png' })],
    })
    await fileInput.trigger('change')

    expect(uploadImageMock).not.toHaveBeenCalled()
    response.resolve({ ...profile, nickname: '保存中' })
    await flushPromises()
  })

  it('keeps the next account avatar progress active when the old upload settles', async () => {
    vi.useFakeTimers()
    const oldUpload = deferred<{ url: string }>()
    const nextUpload = deferred<{ url: string }>()
    uploadImageMock.mockReset()
    uploadImageMock.mockReturnValueOnce(oldUpload.promise).mockReturnValueOnce(nextUpload.promise)
    getMyProfileMock.mockReset()
    getMyProfileMock
      .mockResolvedValueOnce({ ...profile })
      .mockResolvedValueOnce({ ...profile, id: 2, userId: 2, username: 'bob' })
    const wrapper = createWrapper()
    await flushPromises()
    const oldFileInput = wrapper.get('#settings-profile-avatar')
    Object.defineProperty(oldFileInput.element, 'files', {
      configurable: true,
      value: [new File(['old'], 'old.png', { type: 'image/png' })],
    })
    await oldFileInput.trigger('change')

    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await vi.runOnlyPendingTimersAsync()
    const nextFileInput = wrapper.get('#settings-profile-avatar')
    Object.defineProperty(nextFileInput.element, 'files', {
      configurable: true,
      value: [new File(['next'], 'next.png', { type: 'image/png' })],
    })
    await nextFileInput.trigger('change')
    expect(wrapper.text()).toContain('上传中')

    oldUpload.resolve({ url: 'https://cdn.example/old.png' })
    await flushPromises()
    expect(wrapper.text()).toContain('上传中')

    nextUpload.resolve({ url: 'https://cdn.example/next.png' })
    await flushPromises()
    expect(updateMyProfileMock).toHaveBeenCalledTimes(1)
  })

  it('keeps the next account bio upload flag when the old upload settles', async () => {
    vi.useFakeTimers()
    const oldUpload = deferred<{ url: string }>()
    const nextUpload = deferred<{ url: string }>()
    uploadImageMock.mockReset()
    uploadImageMock.mockReturnValueOnce(oldUpload.promise).mockReturnValueOnce(nextUpload.promise)
    getMyProfileMock.mockReset()
    getMyProfileMock
      .mockResolvedValueOnce({ ...profile })
      .mockResolvedValueOnce({ ...profile, id: 2, userId: 2, username: 'bob' })
    const wrapper = createWrapper()
    await flushPromises()

    await wrapper.get('[data-test="bio-upload"]').trigger('click')
    useAuthStore().applyAuthState({ accessToken: 'token-b', user: { id: 2, username: 'bob' } })
    await vi.runOnlyPendingTimersAsync()
    await wrapper.get('[data-test="bio-upload"]').trigger('click')
    expect(wrapper.find('[data-test="bio-upload"]').exists()).toBe(true)

    oldUpload.resolve({ url: 'https://cdn.example/old-bio.png' })
    await flushPromises()
    expect(wrapper.text()).toContain('上传简介图片')

    nextUpload.resolve({ url: 'https://cdn.example/next-bio.png' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('正在上传')
  })
})

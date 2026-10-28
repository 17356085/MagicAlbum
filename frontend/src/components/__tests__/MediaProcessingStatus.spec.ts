import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '@/stores/auth'
import { hydrateMediaAssets, mediaAssets, updateMediaAsset, type MediaAssetState } from '@/composables/mediaProcessingState'
import { persistAuthState } from '@/utils/authStorage'
import MediaProcessingStatus from '../MediaProcessingStatus.vue'

const change = vi.hoisted(() => vi.fn())
const getAsset = vi.hoisted(() => vi.fn())
vi.mock('@/api/uploads', () => ({ changeMediaJob: change, getMediaAsset: getAsset }))
const original = { assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/original.png', status: 'READY', processingStatus: 'PENDING' as const }
let wrapper: VueWrapper | undefined

function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T | PromiseLike<T>) => void
  reject: (reason?: unknown) => void
} {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

describe('media status actions', () => {
  beforeEach(() => {
    localStorage.clear(); setActivePinia(createPinia()); change.mockReset(); getAsset.mockReset(); vi.stubEnv('VITE_API_BASE_URL', '/api/v1')
    useAuthStore().applyAuthState({ accessToken: 'owner', user: { id: 1, username: 'owner' } })
    hydrateMediaAssets('owner'); mediaAssets.value = [{ ...original }]
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; mediaAssets.value = []; vi.unstubAllEnvs() })

  it('keeps the original available while processing and shows previews only after acceptance', async () => {
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    expect(wrapper.get('a').attributes('href')).toBe(original.url)
    expect(wrapper.text()).not.toContain('查看预览')
    mediaAssets.value = [{ ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/preview.png' }]
    await flushPromises()
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual([original.url, '/uploads/preview.png'])
  })

  it('normalizes upload paths for an absolute API base and preserves other href forms', async () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://api.example.test/api/v1')
    mediaAssets.value = [
      { ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/preview.png' },
      { ...original, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: 'https://cdn.example.test/original.png', previewUrl: 'https://cdn.example.test/preview.png', processingStatus: 'SUCCEEDED' },
      { ...original, assetId: 'f2a1665b-60b1-40f9-85a5-3632148fda39', url: '//cdn.example.test/original.png', previewUrl: '//cdn.example.test/preview.png', processingStatus: 'SUCCEEDED' },
      { ...original, assetId: 'a2a1665b-60b1-40f9-85a5-3632148fda39', url: 'media/original.png', previewUrl: 'media/preview.png', processingStatus: 'SUCCEEDED' },
    ]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual([
      'https://api.example.test/uploads/original.png', 'https://api.example.test/uploads/preview.png',
      'https://cdn.example.test/original.png', 'https://cdn.example.test/preview.png',
      '//cdn.example.test/original.png', '//cdn.example.test/preview.png',
      'media/original.png', 'media/preview.png',
    ])
    for (const link of wrapper.findAll('a')) {
      expect(link.attributes()).toMatchObject({ target: '_blank', rel: 'noopener noreferrer' })
    }
  })

  it('uses full owner state after cancel and retry, retaining the original URL', async () => {
    change.mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' }).mockResolvedValueOnce({ ...original, jobId: 'e2a1665b-60b1-40f9-85a5-3632148fda39' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    expect(change).toHaveBeenLastCalledWith(original.assetId, 'cancel', 'owner')
    expect(wrapper.text()).toContain('已停止处理')
    await wrapper.findAll('button').find(button => button.text() === '重试预览')!.trigger('click')
    await flushPromises()
    expect(change).toHaveBeenLastCalledWith(original.assetId, 'retry', 'owner')
    expect(wrapper.get('a').attributes('href')).toBe(original.url)
  })

  it('shows a permission message for a message-less 403 and keeps the action retryable', async () => {
    change.mockRejectedValueOnce({ response: { status: 403, data: {} } }).mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' })
    const auth = useAuthStore()
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('管理此图片处理任务的权限')
    expect(wrapper.findAll('button').some(button => button.text() === '停止处理')).toBe(true)
    expect(auth.isLoggedIn).toBe(true)
    expect(auth.token).toBe('owner')

    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    expect(change).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已停止处理')
  })

  it('does not send an action after the persisted token changes before the click', async () => {
    change.mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    localStorage.setItem('accessToken', 'other-token')
    const stop = wrapper.findAll('button').find(button => button.text() === '停止处理')!
    await stop.trigger('click')
    await flushPromises()

    expect(change).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
    expect(stop.attributes('disabled')).toBeUndefined()
    expect(wrapper.findAll('button').some(button => button.text() === '刷新状态')).toBe(false)
  })

  it('does not send an action after the persisted owner changes under the same token', async () => {
    change.mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })
    const stop = wrapper.findAll('button').find(button => button.text() === '停止处理')!
    await stop.trigger('click')
    await flushPromises()

    expect(change).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
    expect(stop.attributes('disabled')).toBeUndefined()
    expect(wrapper.findAll('button').some(button => button.text() === '刷新状态')).toBe(false)
  })

  it('keeps an existing unknown-result feedback when the next action sees an account change', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('操作结果未确认')

    localStorage.setItem('accessToken', 'other-token')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()

    expect(change).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
    expect(wrapper.text()).toContain('操作结果未确认')
    expect(wrapper.findAll('button').filter(button => button.text() === '刷新状态')).toHaveLength(1)
  })

  it.each([
    ['token', () => localStorage.setItem('accessToken', 'other-token')],
    ['owner', () => persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })],
  ])('ignores a late failure after the %s changes and releases pending', async (_kind, drift) => {
    const response = deferred<MediaAssetState>()
    change.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const stop = () => wrapper!.findAll('button').find(button => button.text() === '停止处理')!
    await stop().trigger('click')
    expect(stop().attributes('disabled')).toBeDefined()

    drift()
    response.reject({ response: { status: 503 } })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(stop().attributes('disabled')).toBeUndefined()
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
  })

  it.each([
    ['token', () => localStorage.setItem('accessToken', 'other-token')],
    ['owner', () => persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })],
  ])('ignores a late success after the %s changes and releases pending', async (_kind, drift) => {
    const response = deferred<MediaAssetState>()
    change.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const stop = () => wrapper!.findAll('button').find(button => button.text() === '停止处理')!
    await stop().trigger('click')
    expect(stop().attributes('disabled')).toBeDefined()

    drift()
    response.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(stop().attributes('disabled')).toBeUndefined()
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
  })

  it('does not send a stale row action when polling replaces the image before the click handler runs', async () => {
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    const stop = wrapper.findAll('button').find(button => button.text() === '停止处理')!
    const replacement = { ...original, processingStatus: 'PROCESSING' as const }
    stop.element.addEventListener('click', () => {
      updateMediaAsset(replacement, 'owner')
    }, { capture: true, once: true })

    await stop.trigger('click')
    await flushPromises()

    expect(change).not.toHaveBeenCalled()
    expect(mediaAssets.value[0]).toEqual(replacement)
    expect(wrapper.get('[role="alert"]').text()).toContain('状态已变化，请先查看')
    expect(wrapper.findAll('button').some(button => button.text() === '刷新状态')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === '停止处理' && button.attributes('disabled') === undefined)).toBe(true)
  })

  it.each([
    ['timeout', { code: 'ECONNABORTED' }],
    ['server error', { response: { status: 503 } }],
    ['invalid response', new Error('图片状态暂不可用')],
  ])('does not claim the operation failed when %s leaves its result unknown', async (_scenario, cause) => {
    change.mockRejectedValueOnce(cause)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()

    const message = wrapper.get('[role="alert"]').text()
    expect(message).toContain('操作结果未确认')
    expect(message).toContain('刷新状态')
    expect(message).not.toContain('操作未完成')
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(wrapper.findAll('button').some(button => button.text() === '停止处理' && button.attributes('disabled') === undefined)).toBe(true)
  })

  it('releases a same-token action after invalidation without applying stale success', async () => {
    const response = deferred<MediaAssetState>()
    change.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const stopButton = () => wrapper!.findAll('button').find(button => button.text() === '停止处理')!
    await stopButton().trigger('click')
    expect(stopButton().attributes('disabled')).toBeDefined()

    useAuthStore().invalidateAuthOperations()
    response.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(stopButton().attributes('disabled')).toBeUndefined()
  })

  it('releases a same-token action after invalidation without applying stale failure', async () => {
    const response = deferred<MediaAssetState>()
    change.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const stopButton = () => wrapper!.findAll('button').find(button => button.text() === '停止处理')!
    await stopButton().trigger('click')
    expect(stopButton().attributes('disabled')).toBeDefined()

    useAuthStore().invalidateAuthOperations()
    response.reject({ response: { status: 403, data: {} } })
    await flushPromises()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(stopButton().attributes('disabled')).toBeUndefined()
  })

  it('keeps a new account action pending when the old account finally settles', async () => {
    const oldResponse = deferred<MediaAssetState>()
    const newResponse = deferred<MediaAssetState>()
    change.mockImplementation((_assetId: string, _kind: string, token: string) => token === 'owner' ? oldResponse.promise : newResponse.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const oldStopButton = () => wrapper!.findAll('button').find(button => button.text() === '停止处理')!
    await oldStopButton().trigger('click')

    const nextAsset = { ...original, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/next.png' }
    useAuthStore().applyAuthState({ accessToken: 'other', user: { id: 2, username: 'other' } })
    hydrateMediaAssets('other')
    await wrapper.vm.$nextTick()
    mediaAssets.value = [nextAsset]
    await wrapper.vm.$nextTick()
    await wrapper.get('.media-status-toggle').trigger('click')

    const newStopButton = () => wrapper!.findAll('button').find(button => button.text() === '停止处理')!
    await newStopButton().trigger('click')
    expect(newStopButton().attributes('disabled')).toBeDefined()

    oldResponse.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(newStopButton().attributes('disabled')).toBeDefined()

    newResponse.resolve({ ...nextAsset, processingStatus: 'CANCELLED' })
    await flushPromises()
    expect(mediaAssets.value[0]?.processingStatus).toBe('CANCELLED')
    expect(wrapper.findAll('button').some(button => button.text() === '重试预览' && !button.attributes('disabled'))).toBe(true)
  })

  it('does not apply an old action or error to the next account', async () => {
    let reject!: (reason: unknown) => void
    change.mockReturnValue(new Promise((_resolve, nextReject) => { reject = nextReject }))
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    useAuthStore().applyAuthState({ accessToken: 'other', user: { id: 2, username: 'other' } })
    hydrateMediaAssets('other')
    reject(new Error('late error'))
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(mediaAssets.value).toEqual([])
  })

  it('shows a row-local refresh button after an unknown action without reading automatically', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()

    expect(getAsset).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('刷新状态')
    expect(wrapper.get('[role="alert"]').text()).toContain('刷新状态')
  })

  it('reads once only after clicking refresh and keeps the result uncertain', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    const refresh = wrapper.findAll('button').find(button => button.text() === '刷新状态')!
    await refresh.trigger('click')
    expect(getAsset).toHaveBeenCalledTimes(1)
    expect(getAsset).toHaveBeenCalledWith(original.assetId, 'owner', expect.any(AbortSignal))
    expect(change).toHaveBeenCalledTimes(1)
    expect(refresh.attributes('aria-label')).toBe('刷新图片 1 状态')
    expect(refresh.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').find(button => button.text() === '停止处理')!.attributes('disabled')).toBeDefined()
    response.resolve({ ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/preview.png' })
    await flushPromises()
    expect(mediaAssets.value[0]?.processingStatus).toBe('SUCCEEDED')
    expect(wrapper.text()).toContain('预览已就绪')
    expect(wrapper.find('a[href="/uploads/preview.png"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('已获取状态，之前的操作仍可能完成，请稍后再次核对。')
    expect(wrapper.text()).toContain('刷新状态')
  })

  it('retains separate refresh controls when actions on two images have unknown results', async () => {
    change.mockRejectedValue({ response: { status: 503 } })
    const second = { ...original, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/second.png' }
    mediaAssets.value = [original, second]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').filter(button => button.text() === '停止处理')[0]!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').filter(button => button.text() === '停止处理')[1]!.trigger('click')
    await flushPromises()

    const refreshes = wrapper.findAll('button').filter(button => button.text() === '刷新状态')
    expect(refreshes.map(button => button.attributes('aria-label'))).toEqual(['刷新图片 1 状态', '刷新图片 2 状态'])
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(2)
    expect(getAsset).not.toHaveBeenCalled()
  })

  it('reports a failed manual read and allows another click', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    getAsset.mockRejectedValueOnce(new Error('read failed')).mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('无法读取最新状态')
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    expect(getAsset).toHaveBeenCalledTimes(2)
    await flushPromises()
    expect(wrapper.text()).toContain('已获取状态，之前的操作仍可能完成，请稍后再次核对。')
  })

  it('aborts a manual read and ignores its response after an account switch', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    useAuthStore().applyAuthState({ accessToken: 'other', user: { id: 2, username: 'other' } })
    hydrateMediaAssets('other')
    response.resolve({ ...original, processingStatus: 'SUCCEEDED' })
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value).toEqual([])
    expect(wrapper.find('[role="status"]').exists()).toBe(false)
  })

  it('ignores a manual read when the auth generation changes under the same token', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    useAuthStore().invalidateAuthOperations()
    response.resolve({ ...original, processingStatus: 'SUCCEEDED' })
    await flushPromises()

    expect(signal.aborted).toBe(false)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(wrapper.text()).not.toContain('已获取状态，之前的操作仍可能完成')
  })

  it('does not claim a manual read succeeded after persisted auth storage drifts', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    localStorage.setItem('accessToken', 'drifted')
    response.resolve({ ...original, processingStatus: 'SUCCEEDED' })
    await flushPromises()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(wrapper.text()).not.toContain('已获取状态，之前的操作仍可能完成')
  })

  it('rejects a manual read when the stored owner changes under the same token', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')

    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })
    response.resolve({ ...original, processingStatus: 'SUCCEEDED' })
    await flushPromises()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(wrapper.get('[role="alert"]').text()).toContain('账号或图片记录已变化')
    expect(wrapper.text()).not.toContain('已获取状态，之前的操作仍可能完成')
  })

  it('reports an auth change instead of starting a read when context is already invalid', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()

    localStorage.setItem('accessToken', 'drifted-before-refresh')
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    await flushPromises()

    expect(getAsset).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('账号状态已变化')
  })

  it('keeps feedback when storage drift rejects removing the record', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('操作结果未确认')

    localStorage.setItem('accessToken', 'drifted-before-remove')
    await wrapper.findAll('button').find(button => button.attributes('aria-label')?.startsWith('移除图片'))!.trigger('click')
    await flushPromises()

    expect(mediaAssets.value).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('操作结果未确认')
  })

  it('aborts a manual read when its row is removed and clears its feedback', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    await wrapper.findAll('button').find(button => button.attributes('aria-label')?.startsWith('移除图片'))!.trigger('click')
    response.resolve({ ...original, processingStatus: 'SUCCEEDED' })
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value).toEqual([])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('aborts a manual read when the panel unmounts', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    wrapper.unmount()
    wrapper = undefined
    response.resolve({ ...original, processingStatus: 'SUCCEEDED' })
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
  })

  it('does not overwrite a row replaced by polling while manual read is pending', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    const response = deferred<MediaAssetState>()
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')

    updateMediaAsset({ ...original, processingStatus: 'PROCESSING' }, 'owner')
    response.resolve({ ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/stale.png' })
    await flushPromises()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PROCESSING')
    expect(mediaAssets.value[0]?.previewUrl).toBeUndefined()
    expect(wrapper.get('[role="alert"]').text()).toContain('状态已变化，请先查看；之前的操作仍可能完成')
  })

  it('releases a pending write when an unrelated row is removed', async () => {
    const response = deferred<MediaAssetState>()
    change.mockReturnValue(response.promise)
    const second = { ...original, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/second.png' }
    mediaAssets.value = [original, second]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    const stopButtons = wrapper.findAll('button').filter(button => button.text() === '停止处理')
    await stopButtons[0]!.trigger('click')
    expect(stopButtons[0]!.attributes('disabled')).toBeDefined()

    await wrapper.find('[aria-label="移除图片 2 的状态记录"]').trigger('click')
    response.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(mediaAssets.value.map(item => item.assetId)).toEqual([original.assetId])
    expect(wrapper.findAll('button').some(button => button.text() === '重试预览' && button.attributes('disabled') === undefined)).toBe(true)
  })
})

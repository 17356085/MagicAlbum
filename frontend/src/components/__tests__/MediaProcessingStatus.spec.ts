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
type CapabilityMediaAsset = MediaAssetState

function capabilityAsset(patch: Partial<CapabilityMediaAsset> = {}): CapabilityMediaAsset {
  return { ...original, ...patch }
}

function processingEnabled(item: MediaAssetState | undefined): boolean | undefined {
  return item?.processingEnabled
}

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
    expect(getAsset).not.toHaveBeenCalled()
  })

  it('reconciles a late cancel success against a newer pending job instead of applying the stale DTO', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const newer = { ...original, processingStatus: 'PENDING' as const, jobId: 'e2a1665b-60b1-40f9-85a5-3632148fda39' }
    updateMediaAsset(newer, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(change).toHaveBeenCalledTimes(1)
    expect(getAsset).toHaveBeenCalledTimes(1)
    expect(getAsset).toHaveBeenCalledWith(original.assetId, 'owner', expect.any(AbortSignal))
    expect(mediaAssets.value[0]).toEqual(newer)
    expect(wrapper.findAll('[role="status"]').some(node => node.text() === '正在核对图片状态…')).toBe(true)

    read.resolve(newer)
    await flushPromises()

    expect(mediaAssets.value[0]).toEqual(newer)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(mediaAssets.value[0]?.processingStatus).not.toBe('CANCELLED')
  })

  it('continues a retry reconciliation from an old FAILED poll to the current PENDING snapshot', async () => {
    const failed = { ...original, processingStatus: 'FAILED' as const, errorCode: 'old-failure' }
    mediaAssets.value = [failed]
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '重试预览')!.trigger('click')
    const polledFailed = { ...failed, errorCode: 'poll-failure' }
    updateMediaAsset(polledFailed, 'owner')
    post.resolve({ ...failed, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/stale-preview.png' })
    await flushPromises()

    expect(getAsset).toHaveBeenCalledTimes(1)
    read.resolve({ ...polledFailed, processingStatus: 'PENDING', jobId: 'f2a1665b-60b1-40f9-85a5-3632148fda39' })
    await flushPromises()

    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(mediaAssets.value[0]?.previewUrl).toBeUndefined()
    expect(wrapper.text()).not.toContain('stale-preview')
  })

  it('keeps a replacement made while reconciliation is pending and exposes a manual refresh', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const duringRead = { ...original, processingStatus: 'PROCESSING' as const, jobId: 'e2a1665b-60b1-40f9-85a5-3632148fda39' }
    updateMediaAsset(duringRead, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    const latest = { ...duringRead, processingStatus: 'PENDING' as const, jobId: 'f2a1665b-60b1-40f9-85a5-3632148fda39' }
    updateMediaAsset(latest, 'owner')
    read.resolve({ ...duringRead, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/stale-preview.png' })
    await flushPromises()

    expect(mediaAssets.value[0]).toEqual(latest)
    expect(wrapper.get('[role="alert"]').text()).toContain('操作已处理，但暂时无法核对状态，请点击刷新状态')
    expect(wrapper.findAll('button').some(button => button.text() === '刷新状态')).toBe(true)
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('keeps a later replacement and exposes manual refresh when reconciliation then fails', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const duringRead = { ...original, processingStatus: 'PROCESSING' as const, jobId: 'e2a1665b-60b1-40f9-85a5-3632148fda39' }
    updateMediaAsset(duringRead, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    const latest = { ...duringRead, processingStatus: 'PENDING' as const, jobId: 'f2a1665b-60b1-40f9-85a5-3632148fda39' }
    updateMediaAsset(latest, 'owner')
    read.reject(new Error('read failed'))
    await flushPromises()

    expect(mediaAssets.value[0]).toEqual(latest)
    expect(wrapper.get('[role="alert"]').text()).toContain('操作已处理，但暂时无法核对状态，请点击刷新状态')
    expect(wrapper.findAll('button').some(button => button.text() === '刷新状态')).toBe(true)
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('reports a failed reconciliation and lets manual refresh recover without repeating the POST', async () => {
    const post = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockRejectedValueOnce(new Error('read failed')).mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    updateMediaAsset({ ...original, processingStatus: 'PROCESSING' }, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('操作已处理，但暂时无法核对状态，请点击刷新状态')
    expect(change).toHaveBeenCalledTimes(1)
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    await flushPromises()

    expect(getAsset).toHaveBeenCalledTimes(2)
    expect(change).toHaveBeenCalledTimes(1)
    expect(mediaAssets.value[0]?.processingStatus).toBe('CANCELLED')
  })

  it('does not start reconciliation when the clicked record remains current', async () => {
    change.mockResolvedValueOnce({ ...original, processingStatus: 'CANCELLED' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await flushPromises()

    expect(change).toHaveBeenCalledTimes(1)
    expect(getAsset).not.toHaveBeenCalled()
    expect(mediaAssets.value[0]?.processingStatus).toBe('CANCELLED')
  })

  it('does not read back or reinsert a row removed before the action response', async () => {
    const post = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    await wrapper.find('[aria-label="移除图片 1 的状态记录"]').trigger('click')

    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(mediaAssets.value).toEqual([])
    expect(getAsset).not.toHaveBeenCalled()
  })

  it('keeps the current row and offers refresh if the readback has another asset id', async () => {
    const post = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockResolvedValueOnce({ ...original, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', processingStatus: 'SUCCEEDED' })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const current = { ...original, processingStatus: 'PROCESSING' as const }
    updateMediaAsset(current, 'owner')

    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(mediaAssets.value[0]).toEqual(current)
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法核对状态')
    expect(wrapper.findAll('button').some(button => button.text() === '刷新状态')).toBe(true)
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('aborts reconciliation when the target row is removed and never reinserts it', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    updateMediaAsset({ ...original, processingStatus: 'PROCESSING' }, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    await wrapper.findAll('button').find(button => button.attributes('aria-label')?.startsWith('移除图片'))!.trigger('click')
    read.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value).toEqual([])
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('aborts reconciliation after an account switch and ignores the old response', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    updateMediaAsset({ ...original, processingStatus: 'PROCESSING' }, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    useAuthStore().applyAuthState({ accessToken: 'other', user: { id: 2, username: 'other' } })
    hydrateMediaAssets('other')
    read.resolve({ ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/late.png' })
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value).toEqual([])
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('ignores reconciliation after same-token owner drift', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const replacement = { ...original, processingStatus: 'PROCESSING' as const }
    updateMediaAsset(replacement, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })
    read.resolve({ ...replacement, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/late.png' })
    await flushPromises()

    expect(mediaAssets.value[0]).toEqual(replacement)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PROCESSING')
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('ignores a late reconciliation after same-token auth invalidation and releases loading', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const replacement = { ...original, processingStatus: 'PROCESSING' as const }
    updateMediaAsset(replacement, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()

    expect(wrapper.findAll('[role="status"]').some(node => node.text() === '正在核对图片状态…')).toBe(true)
    useAuthStore().invalidateAuthOperations()
    expect((getAsset.mock.calls[0]?.[2] as AbortSignal).aborted).toBe(false)
    read.resolve({ ...replacement, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/late.png' })
    await flushPromises()

    expect(mediaAssets.value[0]).toEqual(replacement)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PROCESSING')
    expect(wrapper.findAll('[role="status"]').some(node => node.text() === '正在核对图片状态…')).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === '停止处理' && button.attributes('disabled') === undefined)).toBe(true)
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('aborts reconciliation on unmount and ignores its late response', async () => {
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    updateMediaAsset({ ...original, processingStatus: 'PROCESSING' }, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    wrapper.unmount()
    wrapper = undefined
    read.resolve({ ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/late.png' })
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PROCESSING')
    expect(change).toHaveBeenCalledTimes(1)
  })

  it('keeps reconciliation alive when another image is removed', async () => {
    const second = { ...original, assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/second.png' }
    mediaAssets.value = [original, second]
    const post = deferred<MediaAssetState>()
    const read = deferred<MediaAssetState>()
    change.mockReturnValue(post.promise)
    getAsset.mockReturnValue(read.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '停止处理')!.trigger('click')
    const replacement = { ...original, processingStatus: 'PROCESSING' as const }
    updateMediaAsset(replacement, 'owner')
    post.resolve({ ...original, processingStatus: 'CANCELLED' })
    await flushPromises()
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    await wrapper.find('[aria-label="移除图片 2 的状态记录"]').trigger('click')
    read.resolve({ ...replacement, processingStatus: 'PENDING' })
    await flushPromises()

    expect(signal.aborted).toBe(false)
    expect(mediaAssets.value).toHaveLength(1)
    expect(mediaAssets.value[0]?.assetId).toBe(original.assetId)
    expect(mediaAssets.value[0]?.processingStatus).toBe('PENDING')
    expect(change).toHaveBeenCalledTimes(1)
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

  it('does not count explicitly disabled pending rows and keeps their originals usable without actions', async () => {
    const disabledPending = capabilityAsset({ processingEnabled: false })
    const enabledPending = capabilityAsset({ assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/enabled.png' })
    const disabledProcessing = capabilityAsset({ assetId: 'f2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/processing.png', processingStatus: 'PROCESSING', processingEnabled: false })
    mediaAssets.value = [disabledPending, enabledPending, disabledProcessing]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    expect(wrapper.get('.media-status-toggle').text()).toContain('图片预览处理中 · 1')
    const rows = wrapper.findAll('.media-status-list > li')
    expect(rows[0]?.text()).toContain('未启用')
    expect(rows[0]?.text()).toContain('原图仍可使用')
    expect(rows[0]?.find('a[href="/uploads/original.png"]').exists()).toBe(true)
    expect(rows[0]?.text()).not.toContain('停止处理')
    expect(rows[0]?.text()).not.toContain('重试预览')
    expect(rows[0]?.findAll('button').some(button => button.text() === '刷新状态')).toBe(true)
    expect(rows[1]?.findAll('button').some(button => button.text() === '停止处理')).toBe(true)
    expect(rows[2]?.text()).toContain('未启用')
    expect(rows[2]?.text()).toContain('原图仍可使用')
    expect(rows[2]?.text()).not.toContain('停止处理')
  })

  it('keeps a disabled FAILED row truthful and preserves upload-state labels', async () => {
    const disabledFailed = capabilityAsset({ processingStatus: 'FAILED', processingEnabled: false, previewUrl: '/uploads/stale-preview.png' })
    const uploading = capabilityAsset({ assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', status: 'UPLOADING', processingEnabled: false })
    const uploadFailed = capabilityAsset({ assetId: 'f2a1665b-60b1-40f9-85a5-3632148fda39', status: 'UPLOAD_FAILED', processingEnabled: false })
    mediaAssets.value = [disabledFailed, uploading, uploadFailed]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const rows = wrapper.findAll('.media-status-list > li')
    expect(rows[0]?.text()).toContain('未启用')
    expect(rows[0]?.text()).toContain('预览未生成')
    expect(rows[0]?.text()).toContain('原图仍可使用')
    expect(rows[0]?.text()).not.toContain('预览已就绪')
    expect(rows[0]?.text()).not.toContain('已停止处理')
    expect(rows[0]?.find('a[href="/uploads/original.png"]').exists()).toBe(true)
    expect(rows[0]?.find('a[href="/uploads/stale-preview.png"]').exists()).toBe(false)
    expect(rows[0]?.findAll('button').some(button => ['重试预览', '停止处理'].includes(button.text()))).toBe(false)
    expect(rows[1]?.text()).toContain('原图上传尚未确认')
    expect(rows[1]?.text()).toContain('自动核对未启用')
    expect(rows[1]?.find('a').exists()).toBe(false)
    expect(rows[2]?.text()).toContain('原图上传结果未确认')
    expect(rows[2]?.text()).toContain('重复上传可能产生多份图片')
    expect(rows[2]?.find('a').exists()).toBe(false)
  })

  it('keeps a cancelled state visible when processing is configured off', async () => {
    mediaAssets.value = [capabilityAsset({ processingStatus: 'CANCELLED', processingEnabled: false })]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    expect(wrapper.text()).toContain('已停止处理')
    expect(wrapper.text()).toContain('处理未启用')
    expect(wrapper.findAll('button').some(button => button.text() === '重试预览')).toBe(false)
  })

  it('retains retry and cancel controls for true and absent processing capability', async () => {
    const enabledFailed = capabilityAsset({ processingStatus: 'FAILED', processingEnabled: true })
    const absentPending = capabilityAsset({ assetId: 'e2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/absent.png' })
    mediaAssets.value = [enabledFailed, absentPending]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const rows = wrapper.findAll('.media-status-list > li')
    expect(rows[0]?.findAll('button').some(button => button.text() === '重试预览')).toBe(true)
    expect(rows[1]?.findAll('button').some(button => button.text() === '停止处理')).toBe(true)
    expect(rows[0]?.text()).not.toContain('未启用')
    expect(rows[1]?.text()).not.toContain('未启用')
  })

  it('does not POST when a pending action row becomes explicitly disabled before the click handler runs', async () => {
    const raceAsset = capabilityAsset()
    mediaAssets.value = [raceAsset]
    const liveAsset = mediaAssets.value[0] as CapabilityMediaAsset
    change.mockResolvedValue({ ...raceAsset, processingStatus: 'CANCELLED', processingEnabled: false })
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    const stop = wrapper.findAll('button').find(button => button.text() === '停止处理')!
    stop.element.addEventListener('click', () => { liveAsset.processingEnabled = false }, { capture: true, once: true })

    await stop.trigger('click')
    await flushPromises()

    expect(change).not.toHaveBeenCalled()
    expect(processingEnabled(mediaAssets.value[0])).toBe(false)
    expect(wrapper.text()).toContain('未启用')
    expect(wrapper.text()).toContain('未执行此操作')
  })

  it('preserves an unknown-action warning when a stale click becomes disabled and refresh preflight drifts owner', async () => {
    change.mockRejectedValueOnce({ response: { status: 503 } })
    mediaAssets.value = [capabilityAsset()]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    const stop = wrapper.findAll('button').find(button => button.text() === '停止处理')!
    await stop.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('操作结果未确认')

    const liveAsset = mediaAssets.value[0] as CapabilityMediaAsset
    stop.element.addEventListener('click', () => { liveAsset.processingEnabled = false }, { capture: true, once: true })
    await stop.trigger('click')
    await flushPromises()

    expect(change).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('操作结果未确认')
    expect(wrapper.get('[role="alert"]').text()).not.toContain('未执行此操作')

    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    await flushPromises()

    expect(getAsset).not.toHaveBeenCalled()
    expect(wrapper.get('[role="alert"]').text()).toContain('操作结果未确认')
    expect(wrapper.get('[role="alert"]').text()).toContain('之前的操作仍可能完成')
    const firstMessage = wrapper.get('[role="alert"]').text()
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toBe(firstMessage)
  })

  it('prefers an unavailable label over a stale disabled capability while retaining refresh', async () => {
    mediaAssets.value = [capabilityAsset({ processingEnabled: false, unavailable: true })]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')

    const row = wrapper.find('.media-status-list > li')
    expect(row.text()).toContain('暂时无法更新状态')
    expect(row.text()).not.toContain('预览处理未启用')
    expect(row.findAll('button').some(button => button.text() === '刷新状态')).toBe(true)
  })

  it('refreshes a disabled row without action feedback and resumes with the server capability', async () => {
    const disabled = capabilityAsset({ processingEnabled: false })
    const enabled = capabilityAsset({ processingEnabled: true })
    mediaAssets.value = [disabled]
    getAsset.mockResolvedValueOnce(enabled)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    const refresh = wrapper.findAll('button').find(button => button.text() === '刷新状态')!
    expect(getAsset).not.toHaveBeenCalled()

    await refresh.trigger('click')
    await flushPromises()

    expect(getAsset).toHaveBeenCalledWith(original.assetId, 'owner', expect.any(AbortSignal))
    expect(processingEnabled(mediaAssets.value[0])).toBe(true)
    expect(wrapper.text()).toContain('已读取最新状态')
    expect(wrapper.text()).not.toContain('之前的操作仍可能完成')
  })

  it('does not start a capability refresh after the cache owner drifts before the click', async () => {
    mediaAssets.value = [capabilityAsset({ processingEnabled: false })]
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    const refresh = wrapper.findAll('button').find(button => button.text() === '刷新状态')!
    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })

    await refresh.trigger('click')
    await flushPromises()

    expect(getAsset).not.toHaveBeenCalled()
  })

  it('ignores a capability refresh response after the cache owner drifts', async () => {
    const response = deferred<CapabilityMediaAsset>()
    mediaAssets.value = [capabilityAsset({ processingEnabled: false })]
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')

    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })
    response.resolve(capabilityAsset({ processingEnabled: true }))
    await flushPromises()

    expect(processingEnabled(mediaAssets.value[0])).toBe(false)
    expect(wrapper.text()).not.toContain('已读取最新状态')
  })

  it('ignores a capability refresh error after the cache owner drifts', async () => {
    const response = deferred<CapabilityMediaAsset>()
    mediaAssets.value = [capabilityAsset({ processingEnabled: false })]
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')

    persistAuthState({ accessToken: 'owner', user: { id: 2, username: 'other' } })
    response.reject(new Error('late capability read failure'))
    await flushPromises()

    expect(wrapper.text()).not.toContain('无法读取最新状态')
  })

  it('aborts a capability refresh when the session changes and does not apply the late response', async () => {
    const response = deferred<CapabilityMediaAsset>()
    mediaAssets.value = [capabilityAsset({ processingEnabled: false })]
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    useAuthStore().applyAuthState({ accessToken: 'other', user: { id: 2, username: 'other' } })
    hydrateMediaAssets('other')
    response.resolve(capabilityAsset({ processingEnabled: true }))
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value).toEqual([])
  })

  it('aborts a capability refresh when its row is removed and does not reinsert it', async () => {
    const response = deferred<CapabilityMediaAsset>()
    mediaAssets.value = [capabilityAsset({ processingEnabled: false })]
    getAsset.mockReturnValue(response.promise)
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '刷新状态')!.trigger('click')
    const signal = getAsset.mock.calls[0]?.[2] as AbortSignal

    await wrapper.find('[aria-label="移除图片 1 的状态记录"]').trigger('click')
    response.resolve(capabilityAsset({ processingEnabled: true }))
    await flushPromises()

    expect(signal.aborted).toBe(true)
    expect(mediaAssets.value).toEqual([])
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
})

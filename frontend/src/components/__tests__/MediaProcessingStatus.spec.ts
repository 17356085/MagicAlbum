import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '@/stores/auth'
import { hydrateMediaAssets, mediaAssets, type MediaAssetState } from '@/composables/mediaProcessingState'
import MediaProcessingStatus from '../MediaProcessingStatus.vue'

const change = vi.hoisted(() => vi.fn())
vi.mock('@/api/uploads', () => ({ changeMediaJob: change }))
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
    localStorage.clear(); setActivePinia(createPinia()); change.mockReset(); vi.stubEnv('VITE_API_BASE_URL', '/api/v1')
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
    expect(message).toContain('操作结果未能确认')
    expect(message).toContain('刷新页面核对状态')
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
})

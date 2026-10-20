import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { useAuthStore } from '@/stores/auth'
import { hydrateMediaAssets, mediaAssets } from '@/composables/mediaProcessingState'
import MediaProcessingStatus from '../MediaProcessingStatus.vue'

const change = vi.hoisted(() => vi.fn())
vi.mock('@/api/uploads', () => ({ changeMediaJob: change }))
const original = { assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39', url: '/uploads/original.png', status: 'READY', processingStatus: 'PENDING' as const }
let wrapper: VueWrapper | undefined

describe('media status actions', () => {
  beforeEach(() => {
    localStorage.clear(); setActivePinia(createPinia()); change.mockReset()
    useAuthStore().applyAuthState({ accessToken: 'owner', user: { id: 1, username: 'owner' } })
    hydrateMediaAssets('owner'); mediaAssets.value = [{ ...original }]
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined; mediaAssets.value = [] })

  it('keeps the original available while processing and shows previews only after acceptance', async () => {
    wrapper = mount(MediaProcessingStatus)
    await wrapper.get('.media-status-toggle').trigger('click')
    expect(wrapper.get('a').attributes('href')).toBe(original.url)
    expect(wrapper.text()).not.toContain('查看预览')
    mediaAssets.value = [{ ...original, processingStatus: 'SUCCEEDED', previewUrl: '/uploads/preview.png' }]
    await flushPromises()
    expect(wrapper.findAll('a').map(link => link.attributes('href'))).toEqual([original.url, '/uploads/preview.png'])
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

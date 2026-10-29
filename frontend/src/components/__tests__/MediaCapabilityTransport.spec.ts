import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import { uploadImage } from '@/api/uploads'
import { mediaAssets } from '@/composables/mediaProcessingState'
import { useMediaProcessing } from '@/composables/useMediaProcessing'
import { useAuthStore } from '@/stores/auth'
import MediaProcessingStatus from '../MediaProcessingStatus.vue'

const originalAdapter = api.defaults.adapter
const assetId = 'd2a1665b-60b1-40f9-85a5-3632148fda39'
let wrapper: VueWrapper | undefined

async function settle(): Promise<void> {
  await nextTick()
  await flushPromises()
  await nextTick()
}

describe('media capability through the upload and status API', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({ accessToken: 'owner', user: { id: 1, username: 'owner' } })
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    api.defaults.adapter = originalAdapter
    vi.useRealTimers()
    localStorage.clear()
    sessionStorage.clear()
    mediaAssets.value = []
  })

  it('stops polling a configured-off upload and resumes after a manual recheck enables processing', async () => {
    let enabled = false
    let reads = 0
    api.defaults.adapter = async (config) => {
      const isUpload = config.method === 'post' && config.url === '/uploads/images'
      const isRead = config.method === 'get' && config.url === `/uploads/assets/${assetId}`
      if (!isUpload && !isRead) throw new Error('unexpected API request')
      if (isRead) reads += 1
      const finished = isRead && enabled && reads > 1
      return {
        config, headers: {}, status: 200, statusText: 'OK',
        data: {
          assetId, url: '/uploads/original.png', status: 'READY',
          processingStatus: finished ? 'SUCCEEDED' : 'PENDING',
          processingEnabled: enabled,
          ...(finished ? { previewUrl: '/uploads/preview.png' } : {}),
        },
      }
    }
    wrapper = mount(defineComponent({
      setup() {
        useMediaProcessing()
        return () => h(MediaProcessingStatus)
      },
    }))
    await settle()
    await uploadImage(new File(['image'], 'image.png', { type: 'image/png' }), 'owner')
    await settle()
    await vi.advanceTimersByTimeAsync(10_000)
    await settle()

    expect(reads).toBe(0)
    expect(mediaAssets.value[0]?.processingEnabled).toBe(false)
    await wrapper.get('.media-status-toggle').trigger('click')
    expect(wrapper.text()).toContain('未启用')
    expect(wrapper.findAll('button').some(button => button.text() === '停止处理')).toBe(false)

    enabled = true
    await wrapper.get('[aria-label="刷新图片 1 状态"]').trigger('click')
    await settle()
    expect(reads).toBe(2)
    expect(mediaAssets.value[0]?.processingEnabled).toBe(true)
    expect(mediaAssets.value[0]?.processingStatus).toBe('SUCCEEDED')
    expect(wrapper.text()).toContain('预览已就绪')
  })
})

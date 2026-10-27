import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/api/client'
import { uploadImage } from '@/api/uploads'
import { useAuthStore } from '@/stores/auth'
import { hydrateMediaAssets, mediaAssets } from '@/composables/mediaProcessingState'

const asset = {
  assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39',
  url: '/uploads/original.png',
  processingStatus: 'PENDING' as const,
}

const originalAdapter = api.defaults.adapter

function okResponse(config: InternalAxiosRequestConfig): AxiosResponse<typeof asset> {
  return {
    config,
    data: asset,
    headers: {},
    status: 200,
    statusText: 'OK',
  }
}

function readAuthorization(config: InternalAxiosRequestConfig | undefined): unknown {
  if (!config?.headers) return undefined
  return typeof config.headers.get === 'function'
    ? config.headers.get('Authorization')
    : config.headers.Authorization ?? config.headers.authorization
}

function imageFile(): File {
  return new File(['image'], 'image.png', { type: 'image/png' })
}

describe('upload image session ownership', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    mediaAssets.value = []
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    localStorage.clear()
    mediaAssets.value = []
    vi.restoreAllMocks()
  })

  it.each([undefined, '', 'mock-token-placeholder'] as const)(
    'rejects an automatic %s token before HTTP when memory and storage diverge',
    async (token) => {
      const auth = useAuthStore()
      auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'owner-a' } })
      localStorage.setItem('accessToken', 'token-b')
      let adapterCalls = 0
      api.defaults.adapter = async (config) => {
        adapterCalls += 1
        return okResponse(config)
      }

      await expect(uploadImage(imageFile(), token)).rejects.toMatchObject({
        code: 'AUTH_SESSION_CHANGED',
        message: '账号已在其他标签页变更，请刷新后重试',
      })

      expect(adapterCalls).toBe(0)
    },
  )

  it('uses one matching stored token for the header and media tracking', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'owner-a' } })
    hydrateMediaAssets('token-a')
    let capturedConfig: InternalAxiosRequestConfig | undefined
    let adapterCalls = 0
    api.defaults.adapter = async (config) => {
      adapterCalls += 1
      capturedConfig = config
      return okResponse(config)
    }

    await expect(uploadImage(imageFile(), '')).resolves.toEqual(asset)

    expect(adapterCalls).toBe(1)
    expect(readAuthorization(capturedConfig)).toBe('Bearer token-a')
    expect(mediaAssets.value).toEqual([expect.objectContaining(asset)])
  })

  it('preserves an explicit real token override when the store and storage differ', async () => {
    const auth = useAuthStore()
    auth.applyAuthState({ accessToken: 'token-a', user: { id: 1, username: 'owner-a' } })
    localStorage.setItem('accessToken', 'token-b')
    let capturedConfig: InternalAxiosRequestConfig | undefined
    let adapterCalls = 0
    api.defaults.adapter = async (config) => {
      adapterCalls += 1
      capturedConfig = config
      return okResponse(config)
    }

    await expect(uploadImage(imageFile(), 'token-explicit')).resolves.toEqual(asset)

    expect(adapterCalls).toBe(1)
    expect(readAuthorization(capturedConfig)).toBe('Bearer token-explicit')
  })
})

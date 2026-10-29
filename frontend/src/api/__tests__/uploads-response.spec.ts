import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import api from '@/api/client'
import { uploadImage } from '@/api/uploads'
import {
  isUploadResponseUnconfirmedError,
  UploadResponseUnconfirmedError,
} from '@/api/uploadResponse'
import * as mediaState from '@/composables/mediaProcessingState'
import { hydrateMediaAssets, mediaAssets } from '@/composables/mediaProcessingState'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

const originalAdapter = api.defaults.adapter
const token = 'upload-response-token'
const unconfirmedMessage = '图片上传结果未确认，重复上传可能产生多份图片'

const mediaFields = {
  assetId: 'd2a1665b-60b1-40f9-85a5-3632148fda39',
  processingStatus: 'PENDING',
}

function imageFile(): File {
  return new File(['image'], 'image.png', { type: 'image/png' })
}

function response(data: unknown, config: InternalAxiosRequestConfig, status = 200): AxiosResponse<unknown> {
  return { config, data, headers: {}, status, statusText: 'OK' }
}

function installAdapter(data: unknown, status = 200): void {
  api.defaults.adapter = async (config) => response(data, config, status)
}

function createAxiosFailure(status?: number): AxiosError {
  const config = {} as InternalAxiosRequestConfig
  const responseData = status === undefined ? undefined : response({}, config, status)
  return new AxiosError(
    status === undefined ? 'upload transport failed' : `upload failed with ${status}`,
    status === undefined ? 'ERR_NETWORK' : 'ERR_BAD_RESPONSE',
    config,
    undefined,
    responseData,
  )
}

describe('upload image response confirmation', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    useAuthStore().applyAuthState({
      accessToken: token,
      user: { id: 1, username: 'uploader' },
    })
    hydrateMediaAssets(token)
    mediaAssets.value = []
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    mediaAssets.value = []
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it.each([
    {
      name: 'backend url',
      body: {
        ...mediaFields,
        url: '/uploads/original.png',
        extra: { retained: true },
      },
      expected: '/uploads/original.png',
    },
    {
      name: 'legacy path',
      body: {
        ...mediaFields,
        path: '/uploads/legacy.png',
        extra: { retained: true },
      },
      expected: '/uploads/legacy.png',
    },
  ])('accepts $name and retains media registration fields', async ({ body, expected }) => {
    const track = vi.spyOn(mediaState, 'trackMediaUpload')
    installAdapter(body)

    const result = await uploadImage(imageFile(), token)

    expect(result.url).toBe(expected)
    expect(result.extra).toEqual({ retained: true })
    expect(track).toHaveBeenCalledOnce()
    expect(track).toHaveBeenCalledWith(expect.objectContaining({ url: expected, ...mediaFields }), token)
    expect(mediaAssets.value).toEqual([expect.objectContaining({ ...mediaFields, url: expected })])
  })

  it('tracks an explicitly disabled processing capability from the real upload response', async () => {
    installAdapter({
      ...mediaFields,
      url: '/uploads/original-disabled.png',
      processingEnabled: false,
    })

    const result = await uploadImage(imageFile(), token)

    expect(result.processingEnabled).toBe(false)
    expect(mediaAssets.value).toEqual([expect.objectContaining({
      ...mediaFields,
      url: '/uploads/original-disabled.png',
      processingEnabled: false,
    })])

    hydrateMediaAssets(token)
    expect(mediaAssets.value[0]?.processingEnabled).toBe(false)
  })

  it.each([
    '/uploads/relative.png',
    'images/relative.png',
    './images/relative.png',
    '../images/relative.png',
    '//cdn.example.test/relative.png',
    'http://cdn.example.test/relative.png',
    'https://cdn.example.test/relative.png',
  ])('accepts an ordinary safe URL form: %s', async (url) => {
    installAdapter({ url })
    await expect(uploadImage(imageFile(), token)).resolves.toMatchObject({ url })
  })

  it('uses url || path semantics while normalizing unsafe unused fields away', async () => {
    installAdapter({ url: 0, path: '/uploads/fallback.png', extra: 'retained' })

    const result = await uploadImage(imageFile(), token)

    expect(result.url).toBe('/uploads/fallback.png')
    expect(result.path).toBe('/uploads/fallback.png')
    expect(result.extra).toBe('retained')
  })

  it('does not type an unused non-string path as a string', async () => {
    installAdapter({ url: '/uploads/original.png', path: { unsafe: true } })

    const result = await uploadImage(imageFile(), token)

    expect(result.url).toBe('/uploads/original.png')
    expect(Object.prototype.hasOwnProperty.call(result, 'path')).toBe(false)
  })

  it('exposes a stable unconfirmed-response error and predicate', () => {
    const error = new UploadResponseUnconfirmedError()

    expect(error).toBeInstanceOf(UploadResponseUnconfirmedError)
    expect(error.message).toBe(unconfirmedMessage)
    expect(error.name).toBe('UploadResponseUnconfirmedError')
    expect(isUploadResponseUnconfirmedError(error)).toBe(true)
    expect(isUploadResponseUnconfirmedError({
      code: 'UPLOAD_RESPONSE_UNCONFIRMED',
      message: unconfirmedMessage,
    })).toBe(true)
    expect(isUploadResponseUnconfirmedError(new Error(unconfirmedMessage))).toBe(false)
  })

  it.each([
    { name: 'timeout without a response', failure: () => new AxiosError('upload timed out', 'ECONNABORTED') },
    { name: 'network failure without a response', failure: () => createAxiosFailure() },
    { name: 'HTTP 408', failure: () => createAxiosFailure(408) },
    { name: 'HTTP 500', failure: () => createAxiosFailure(500) },
    { name: 'HTTP 502', failure: () => createAxiosFailure(502) },
  ])('turns $name into an unconfirmed response while retaining the cause', async ({ failure: createFailure }) => {
    const failure = createFailure()
    const track = vi.spyOn(mediaState, 'trackMediaUpload')
    let requests = 0
    api.defaults.adapter = async () => {
      requests += 1
      throw failure
    }

    let thrown: unknown
    try {
      await uploadImage(imageFile(), token)
    } catch (error) {
      thrown = error
    }

    expect(thrown).toBeInstanceOf(UploadResponseUnconfirmedError)
    expect((thrown as UploadResponseUnconfirmedError).message).toBe(unconfirmedMessage)
    expect((thrown as UploadResponseUnconfirmedError).cause).toBe(failure)
    expect(requests).toBe(1)
    expect(track).not.toHaveBeenCalled()
    expect(mediaAssets.value).toEqual([])
  })

  it.each([400, 401, 403, 413])('preserves the original Axios %s error identity', async (status) => {
    const failure = createAxiosFailure(status)
    const track = vi.spyOn(mediaState, 'trackMediaUpload')
    let requests = 0
    api.defaults.adapter = async () => {
      requests += 1
      throw failure
    }

    await expect(uploadImage(imageFile(), token)).rejects.toBe(failure)
    expect(requests).toBe(1)
    expect(track).not.toHaveBeenCalled()
  })

  it('preserves a non-Axios adapter failure identity', async () => {
    const failure = new Error('upload failed before an HTTP response')
    const track = vi.spyOn(mediaState, 'trackMediaUpload')
    let requests = 0
    api.defaults.adapter = async () => {
      requests += 1
      throw failure
    }

    await expect(uploadImage(imageFile(), token)).rejects.toBe(failure)
    expect(requests).toBe(1)
    expect(track).not.toHaveBeenCalled()
  })

  it('does not wrap a synchronous session preflight error or issue a request', async () => {
    const track = vi.spyOn(mediaState, 'trackMediaUpload')
    let requests = 0
    api.defaults.adapter = async () => {
      requests += 1
      throw new Error('adapter must not run')
    }
    localStorage.setItem('accessToken', 'different-session-token')

    await expect(uploadImage(imageFile())).rejects.toMatchObject({
      name: 'AuthSessionChangedError',
      code: 'AUTH_SESSION_CHANGED',
    })
    expect(requests).toBe(0)
    expect(track).not.toHaveBeenCalled()
  })

  it.each([
    { name: 'null', body: null },
    { name: 'undefined', body: undefined },
    { name: 'array', body: [] },
    { name: 'text', body: 'ok' },
    { name: 'missing url and path', body: { ...mediaFields } },
    { name: 'blank selected url', body: { url: '   ', path: '/uploads/fallback.png' } },
    { name: 'truthy non-string selected url', body: { url: 42, path: '/uploads/fallback.png' } },
    { name: 'truthy object selected url', body: { url: {}, path: '/uploads/fallback.png' } },
    { name: 'oversized url', body: { url: 'a'.repeat(2049) } },
    { name: 'javascript scheme', body: { url: 'javascript:alert(1)' } },
    { name: 'data scheme', body: { url: 'data:image/png;base64,abc' } },
    { name: 'file scheme', body: { url: 'file:///tmp/image.png' } },
    { name: 'ftp scheme', body: { url: 'ftp://cdn.example.test/image.png' } },
    { name: 'http scheme without authority', body: { url: 'http:relative.png' } },
    { name: 'space', body: { url: '/uploads/image file.png' } },
    { name: 'leading whitespace', body: { url: ' /uploads/image.png' } },
    { name: 'control character', body: { url: '/uploads/image\n.png' } },
    { name: 'backslash', body: { url: '/uploads\\image.png' } },
    { name: 'unsafe url wins over safe path', body: { url: 'javascript:alert(1)', path: '/uploads/fallback.png' } },
  ])('rejects $name without tracking a malformed success', async ({ body }) => {
    const track = vi.spyOn(mediaState, 'trackMediaUpload')
    installAdapter(body)

    await expect(uploadImage(imageFile(), token)).rejects.toMatchObject({
      code: 'UPLOAD_RESPONSE_UNCONFIRMED',
      message: unconfirmedMessage,
    })
    expect(track).not.toHaveBeenCalled()
    expect(mediaAssets.value).toEqual([])
  })
})

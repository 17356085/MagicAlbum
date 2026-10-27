import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '@/api/client'
import {
  AuthProtocolError,
  cancelQrLoginSession,
  createQrLoginSession,
  exchangeQrLoginSession,
  exchangeOAuthCode,
  getQrLoginStatus,
} from '@/api/auth'

vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const apiMock = api as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
}

describe('auth protocol boundaries', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('requires both QR identifiers when creating a session', async () => {
    apiMock.post.mockResolvedValueOnce({
      data: {
        qrId: 'qr-1',
        pollToken: 'poll-1',
        qrUrl: 'magicalbum://auth/qr/qr-1',
        expiresAt: '2026-09-29T00:00:00Z',
        status: 'PENDING',
      },
    } as never)

    await expect(createQrLoginSession()).resolves.toMatchObject({ qrId: 'qr-1', pollToken: 'poll-1' })

    apiMock.post.mockResolvedValueOnce({
      data: {
        qrId: 'qr-2',
        qrUrl: 'magicalbum://auth/qr/qr-2',
        expiresAt: '2026-09-29T00:00:00Z',
        status: 'PENDING',
      },
    } as never)
    await expect(createQrLoginSession()).rejects.toBeInstanceOf(AuthProtocolError)
  })

  it('uses qrId and pollToken for status and cancellation', async () => {
    apiMock.post.mockResolvedValueOnce({
      data: {
        qrId: 'qr-1',
        qrUrl: 'magicalbum://auth/qr/qr-1',
        expiresAt: '2026-09-29T00:00:00Z',
        status: 'PENDING',
      },
    } as never)
    apiMock.post.mockResolvedValueOnce({ status: 204 } as never)

    await getQrLoginStatus('qr-1', 'poll-1')
    await cancelQrLoginSession('qr-1', 'poll-1')

    expect(apiMock.post).toHaveBeenCalledWith('/auth/qr/status', {
      qrId: 'qr-1',
      pollToken: 'poll-1',
    })
    expect(apiMock.post).toHaveBeenCalledWith('/auth/qr/cancel', {
      qrId: 'qr-1',
      pollToken: 'poll-1',
    })
  })

  it('rejects a QR status payload that contains a JWT', async () => {
    apiMock.post.mockResolvedValueOnce({
      data: {
        qrId: 'qr-1',
        qrUrl: 'magicalbum://auth/qr/qr-1',
        expiresAt: '2026-09-29T00:00:00Z',
        status: 'CONFIRMED',
        accessToken: 'must-not-be-here',
      },
    } as never)

    await expect(getQrLoginStatus('qr-1', 'poll-1')).rejects.toBeInstanceOf(AuthProtocolError)
  })

  it('drops legacy status user data while keeping confirmed exchange data separate', async () => {
    apiMock.post
      .mockResolvedValueOnce({
        data: {
          qrId: 'qr-1',
          qrUrl: 'magicalbum://auth/qr/qr-1',
          expiresAt: '2026-09-29T00:00:00Z',
          status: 'CONFIRMED',
          message: '已确认登录',
          user: { id: 7, username: 'legacy-user', email: 'legacy@example.test' },
        },
      } as never)
      .mockResolvedValueOnce({
        data: {
          accessToken: 'qr-jwt',
          user: { id: 7, username: 'exchange-user', email: 'exchange@example.test', phone: '+85255550123' },
        },
      } as never)

    const status = await getQrLoginStatus('qr-1', 'poll-1')
    expect(status).not.toHaveProperty('user')
    await expect(exchangeQrLoginSession('qr-1', 'poll-1')).resolves.toMatchObject({
      accessToken: 'qr-jwt',
      user: { email: 'exchange@example.test', phone: '+85255550123' },
    })
  })

  it('exchanges only a short-lived OAuth code through POST', async () => {
    apiMock.post.mockResolvedValueOnce({
      data: { accessToken: 'app-jwt', user: null },
    } as never)

    await expect(exchangeOAuthCode('one-time-code', 'code-verifier')).resolves.toMatchObject({ accessToken: 'app-jwt' })
    expect(apiMock.post).toHaveBeenCalledWith('/auth/oauth/exchange', {
      code: 'one-time-code',
      codeVerifier: 'code-verifier',
    })
  })
})

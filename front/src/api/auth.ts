import api from './client'
import type {
  ApiError,
  FinishPhoneCodeLoginRequest,
  FinishEmailCodeLoginRequest,
  LoginResponse,
  LoginWithEmailPasswordRequest,
  LoginWithPhonePasswordRequest,
  QrLoginSession,
  QrLoginStatus,
  QrLoginStatusResponse,
  StartEmailCodeLoginRequest,
  StartEmailCodeLoginResponse,
  StartPhoneCodeLoginRequest,
  StartPhoneCodeLoginResponse,
  User,
} from '@/types'

const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function normalizeAuthResponse(resp: unknown): LoginResponse {
  const root = (resp ?? {}) as Record<string, unknown>
  const payload = ((root.data ?? root.result ?? root) ?? {}) as Record<string, unknown>
  const accessToken =
    payload.accessToken ?? payload.access_token ?? payload.token ?? payload.jwt ?? payload.id_token
  const user = (payload.user ?? payload.profile ?? payload.userInfo ?? payload.account ?? null) as User | null
  return { accessToken: String(accessToken ?? ''), user }
}

function buildMockQrUser(): User {
  return {
    id: Math.floor(Math.random() * 10000),
    username: 'qr_user_' + Math.random().toString(36).slice(2, 6),
    avatarUrl: '',
  }
}

type MockQrState = {
  createdAt: number
  canceled?: boolean
  scanned?: boolean
  confirmed?: boolean
  accessToken?: string
  user: User
}

const mockQrSessions = new Map<string, MockQrState>()

export async function loginWithPhonePassword({
  phone,
  password,
  verifyToken,
  verifyProvider,
  verifyScene,
}: LoginWithPhonePasswordRequest): Promise<LoginResponse> {
  if (useMock) {
    await delay(400)
    return {
      accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
      user: {
        id: Math.floor(Math.random() * 10000),
        username: 'user_' + phone.slice(-4),
        phone,
        avatarUrl: '',
      },
    }
  }
  try {
    const { data } = await api.post('/auth/login', { phone, password, verifyToken, verifyProvider, verifyScene })
    // 规范化后端返回结构为 { accessToken, user }
    return normalizeAuthResponse(data)
  } catch (e) {
    // 非 Mock 模式：直接抛出错误，避免写入伪令牌
    throw e as ApiError
  }
}

export async function loginWithEmailPassword({
  email,
  password,
  verifyToken,
  verifyProvider,
  verifyScene,
}: LoginWithEmailPasswordRequest): Promise<LoginResponse> {
  if (useMock) {
    await delay(400)
    return {
      accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
      user: {
        id: Math.floor(Math.random() * 10000),
        username: email.split('@')[0],
        email,
        avatarUrl: '',
      },
    }
  }
  try {
    const { data } = await api.post('/auth/login', { email, password, verifyToken, verifyProvider, verifyScene })
    return normalizeAuthResponse(data)
  } catch (e) {
    // 非 Mock 模式：直接抛出错误，避免写入伪令牌
    throw e as ApiError
  }
}

export async function startEmailCodeLogin({
  channel,
  address,
  verifyToken,
  verifyProvider,
  verifyScene,
}: StartEmailCodeLoginRequest): Promise<StartEmailCodeLoginResponse> {
  if (useMock) {
    await delay(250)
    return {
      channel: 'email',
      maskedAddress: address.replace(/^(.{2}).+(@.+)$/, '$1****$2'),
      session: 'mock-email-session-' + Math.random().toString(36).slice(2, 10),
      expireSeconds: 300,
      cooldownSeconds: 60,
    }
  }

  const { data } = await api.post('/auth/code/start', {
    channel,
    address,
    verifyToken,
    verifyProvider,
    verifyScene,
  })
  const payload = ((data as Record<string, unknown>)?.data ?? data ?? {}) as Record<string, unknown>
  return {
    channel: 'email',
    maskedAddress: String(payload.maskedAddress ?? ''),
    session: String(payload.session ?? ''),
    expireSeconds: Number(payload.expireSeconds ?? 300),
    cooldownSeconds: Number(payload.cooldownSeconds ?? 60),
  }
}

export async function finishEmailCodeLogin({
  channel,
  address,
  code,
  session,
}: FinishEmailCodeLoginRequest): Promise<LoginResponse> {
  if (useMock) {
    await delay(250)
    return {
      accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
      user: {
        id: Math.floor(Math.random() * 10000),
        username: address.split('@')[0],
        email: address,
        avatarUrl: '',
      },
    }
  }

  const { data } = await api.post('/auth/code/finish', {
    channel,
    address,
    code,
    session,
  })
  return normalizeAuthResponse(data)
}

export async function startPhoneCodeLogin({
  channel,
  address,
  verifyToken,
  verifyProvider,
  verifyScene,
}: StartPhoneCodeLoginRequest): Promise<StartPhoneCodeLoginResponse> {
  if (useMock) {
    await delay(250)
    return {
      channel: 'phone',
      maskedAddress: address.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2'),
      session: 'mock-phone-session-' + Math.random().toString(36).slice(2, 10),
      expireSeconds: 300,
      cooldownSeconds: 60,
    }
  }

  const { data } = await api.post('/auth/code/start', {
    channel,
    address,
    verifyToken,
    verifyProvider,
    verifyScene,
  })
  const payload = ((data as Record<string, unknown>)?.data ?? data ?? {}) as Record<string, unknown>
  return {
    channel: 'phone',
    maskedAddress: String(payload.maskedAddress ?? ''),
    session: String(payload.session ?? ''),
    expireSeconds: Number(payload.expireSeconds ?? 300),
    cooldownSeconds: Number(payload.cooldownSeconds ?? 60),
  }
}

export async function finishPhoneCodeLogin({
  channel,
  address,
  code,
  session,
}: FinishPhoneCodeLoginRequest): Promise<LoginResponse> {
  if (useMock) {
    await delay(250)
    return {
      accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
      user: {
        id: Math.floor(Math.random() * 10000),
        username: 'user_' + address.slice(-4),
        phone: address,
        avatarUrl: '',
      },
    }
  }

  const { data } = await api.post('/auth/code/finish', {
    channel,
    address,
    code,
    session,
  })
  return normalizeAuthResponse(data)
}

export async function createQrLoginSession(): Promise<QrLoginSession> {
  if (useMock) {
    await delay(200)
    const qrId = 'mock-qr-' + Math.random().toString(36).slice(2, 10)
    const createdAt = Date.now()
    mockQrSessions.set(qrId, { createdAt, user: buildMockQrUser() })
    return {
      qrId,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt: new Date(createdAt + 60_000).toISOString(),
      status: 'PENDING',
    }
  }
  const { data } = await api.post('/auth/qr/create')
  const payload = ((data as Record<string, unknown>)?.data ?? data ?? {}) as Record<string, unknown>
  return {
    qrId: String(payload.qrId ?? ''),
    qrUrl: String(payload.qrUrl ?? ''),
    expiresAt: String(payload.expiresAt ?? ''),
    status: String(payload.status ?? 'PENDING') as QrLoginStatus,
  }
}

export async function getQrLoginStatus(qrId: string): Promise<QrLoginStatusResponse> {
  if (useMock) {
    await delay(300)
    const session = mockQrSessions.get(qrId)
    if (!session) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt: new Date(Date.now() - 1000).toISOString(),
        status: 'EXPIRED',
        message: '二维码会话不存在或已失效',
      }
    }

    const elapsed = Date.now() - session.createdAt
    const expiresAt = new Date(session.createdAt + 60_000).toISOString()
    if (session.canceled) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt,
        status: 'CANCELED',
        message: '二维码登录已取消',
      }
    }
    if (elapsed >= 60_000) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt,
        status: 'EXPIRED',
        message: '二维码已过期',
      }
    }
    if (session.confirmed) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt,
        status: 'CONFIRMED',
        accessToken: session.accessToken || ('mock-token-' + Math.random().toString(36).slice(2)),
        user: session.user,
        message: '已确认登录',
      }
    }
    if (session.scanned || elapsed >= 8_000) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt,
        status: 'SCANNED',
        message: '已扫码，请在移动端确认登录',
      }
    }
    return {
      qrId,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt,
      status: 'PENDING',
      message: '等待扫码',
    }
  }

  const { data } = await api.get('/auth/qr/status', { params: { qrId } })
  const payload = ((data as Record<string, unknown>)?.data ?? data ?? {}) as Record<string, unknown>
  const authPayload = normalizeAuthResponse(payload)
  return {
    qrId: String(payload.qrId ?? qrId),
    qrUrl: String(payload.qrUrl ?? ''),
    expiresAt: String(payload.expiresAt ?? ''),
    status: String(payload.status ?? 'PENDING') as QrLoginStatus,
    accessToken: authPayload.accessToken || undefined,
    user: authPayload.user ?? ((payload.user as User | null | undefined) ?? null),
    message: payload.message ? String(payload.message) : undefined,
  }
}

export async function scanQrLoginSession(qrId: string): Promise<QrLoginStatusResponse> {
  if (useMock) {
    await delay(150)
    const session = mockQrSessions.get(qrId)
    if (!session) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt: new Date(Date.now() - 1000).toISOString(),
        status: 'EXPIRED',
        message: '二维码会话不存在或已失效',
      }
    }
    session.scanned = true
    return {
      qrId,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt: new Date(session.createdAt + 60_000).toISOString(),
      status: 'SCANNED',
      message: '已扫码，请确认登录',
    }
  }

  const { data } = await api.post('/auth/qr/scan', { qrId })
  const payload = ((data as Record<string, unknown>)?.data ?? data ?? {}) as Record<string, unknown>
  return {
    qrId: String(payload.qrId ?? qrId),
    qrUrl: String(payload.qrUrl ?? ''),
    expiresAt: String(payload.expiresAt ?? ''),
    status: String(payload.status ?? 'SCANNED') as QrLoginStatus,
    message: payload.message ? String(payload.message) : undefined,
    accessToken: payload.accessToken ? String(payload.accessToken) : undefined,
    user: (payload.user as User | null | undefined) ?? null,
  }
}

export async function confirmQrLoginSession(qrId: string): Promise<QrLoginStatusResponse> {
  if (useMock) {
    await delay(150)
    const session = mockQrSessions.get(qrId)
    if (!session) {
      return {
        qrId,
        qrUrl: `mock://auth/qr/${qrId}`,
        expiresAt: new Date(Date.now() - 1000).toISOString(),
        status: 'EXPIRED',
        message: '二维码会话不存在或已失效',
      }
    }
    session.scanned = true
    session.confirmed = true
    session.accessToken = 'mock-token-' + Math.random().toString(36).slice(2)
    return {
      qrId,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt: new Date(session.createdAt + 60_000).toISOString(),
      status: 'CONFIRMED',
      accessToken: session.accessToken,
      user: session.user,
      message: '已确认登录',
    }
  }

  const { data } = await api.post('/auth/qr/confirm', { qrId })
  const payload = ((data as Record<string, unknown>)?.data ?? data ?? {}) as Record<string, unknown>
  const authPayload = normalizeAuthResponse(payload)
  return {
    qrId: String(payload.qrId ?? qrId),
    qrUrl: String(payload.qrUrl ?? ''),
    expiresAt: String(payload.expiresAt ?? ''),
    status: String(payload.status ?? 'CONFIRMED') as QrLoginStatus,
    message: payload.message ? String(payload.message) : undefined,
    accessToken: authPayload.accessToken || undefined,
    user: authPayload.user ?? ((payload.user as User | null | undefined) ?? null),
  }
}

export async function cancelQrLoginSession(qrId: string): Promise<void> {
  if (useMock) {
    await delay(100)
    const session = mockQrSessions.get(qrId)
    if (session) session.canceled = true
    return
  }
  await api.post('/auth/qr/cancel', { qrId })
}

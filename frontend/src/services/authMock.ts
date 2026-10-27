import type {
  FinishEmailCodeLoginRequest,
  FinishPhoneCodeLoginRequest,
  LoginResponse,
  LoginWithEmailPasswordRequest,
  LoginWithPhonePasswordRequest,
  QrLoginSession,
  QrLoginStatusResponse,
  StartEmailCodeLoginRequest,
  StartEmailCodeLoginResponse,
  StartPhoneCodeLoginRequest,
  StartPhoneCodeLoginResponse,
  User,
} from '@/types'

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
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
  pollToken: string
  canceled?: boolean
  scanned?: boolean
  confirmed?: boolean
  exchanged?: boolean
  user: User
}

const mockQrSessions = new Map<string, MockQrState>()

export async function loginWithPhonePasswordMock({
  phone,
}: LoginWithPhonePasswordRequest): Promise<LoginResponse> {
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

export async function loginWithEmailPasswordMock({
  email,
}: LoginWithEmailPasswordRequest): Promise<LoginResponse> {
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

export async function startEmailCodeLoginMock({
  address,
}: StartEmailCodeLoginRequest): Promise<StartEmailCodeLoginResponse> {
  await delay(250)
  return {
    channel: 'email',
    maskedAddress: address.replace(/^(.{2}).+(@.+)$/, '$1****$2'),
    session: 'mock-email-session-' + Math.random().toString(36).slice(2, 10),
    expireSeconds: 300,
    cooldownSeconds: 60,
  }
}

export async function finishEmailCodeLoginMock({
  address,
}: FinishEmailCodeLoginRequest): Promise<LoginResponse> {
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

export async function startPhoneCodeLoginMock({
  address,
}: StartPhoneCodeLoginRequest): Promise<StartPhoneCodeLoginResponse> {
  await delay(250)
  return {
    channel: 'phone',
    maskedAddress: address.replace(/^(\d{3})\d+(\d{4})$/, '$1****$2'),
    session: 'mock-phone-session-' + Math.random().toString(36).slice(2, 10),
    expireSeconds: 300,
    cooldownSeconds: 60,
  }
}

export async function finishPhoneCodeLoginMock({
  address,
}: FinishPhoneCodeLoginRequest): Promise<LoginResponse> {
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

export async function createQrLoginSessionMock(): Promise<QrLoginSession> {
  await delay(200)
  const qrId = 'mock-qr-' + Math.random().toString(36).slice(2, 10)
  const createdAt = Date.now()
  const pollToken = 'mock-poll-' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2)
  mockQrSessions.set(qrId, { createdAt, pollToken, user: buildMockQrUser() })
  return {
    qrId,
    pollToken,
    qrUrl: `mock://auth/qr/${qrId}`,
    expiresAt: new Date(createdAt + 60_000).toISOString(),
    status: 'PENDING',
  }
}

export async function getQrLoginStatusMock(qrId: string, pollToken: string): Promise<QrLoginStatusResponse> {
  await delay(300)
  const session = mockQrSessions.get(qrId)
  if (!session) {
    return {
      qrId,
      pollToken: '',
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
      status: 'EXPIRED',
      message: '二维码会话不存在或已失效',
    }
  }
  if (session.pollToken !== pollToken) {
    throw new Error('二维码轮询凭证无效')
  }

  const elapsed = Date.now() - session.createdAt
  const expiresAt = new Date(session.createdAt + 60_000).toISOString()
  if (session.canceled) {
    return {
      qrId,
      pollToken,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt,
      status: 'CANCELLED',
      message: '二维码登录已取消',
    }
  }
  if (elapsed >= 60_000) {
    return {
      qrId,
      pollToken,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt,
      status: 'EXPIRED',
      message: '二维码已过期',
    }
  }
  if (session.confirmed) {
    return {
      qrId,
      pollToken,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt,
      status: 'CONFIRMED',
      message: '已确认登录',
    }
  }
  if (session.scanned || elapsed >= 8_000) {
    return {
      qrId,
      pollToken,
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt,
      status: 'SCANNED',
      message: '已扫码，请在移动端确认登录',
    }
  }
  return {
    qrId,
    pollToken,
    qrUrl: `mock://auth/qr/${qrId}`,
    expiresAt,
    status: 'PENDING',
    message: '等待扫码',
  }
}

export async function scanQrLoginSessionMock(qrId: string): Promise<QrLoginStatusResponse> {
  await delay(150)
  const session = mockQrSessions.get(qrId)
  if (!session) {
    return {
      qrId,
      pollToken: '',
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
      status: 'EXPIRED',
      message: '二维码会话不存在或已失效',
    }
  }
  session.scanned = true
  return {
    qrId,
    pollToken: session.pollToken,
    qrUrl: `mock://auth/qr/${qrId}`,
    expiresAt: new Date(session.createdAt + 60_000).toISOString(),
    status: 'SCANNED',
    message: '已扫码，请确认登录',
  }
}

export async function confirmQrLoginSessionMock(qrId: string): Promise<QrLoginStatusResponse> {
  await delay(150)
  const session = mockQrSessions.get(qrId)
  if (!session) {
    return {
      qrId,
      pollToken: '',
      qrUrl: `mock://auth/qr/${qrId}`,
      expiresAt: new Date(Date.now() - 1000).toISOString(),
      status: 'EXPIRED',
      message: '二维码会话不存在或已失效',
    }
  }
  session.scanned = true
  session.confirmed = true
  return {
    qrId,
    pollToken: '',
    qrUrl: `mock://auth/qr/${qrId}`,
    expiresAt: new Date(session.createdAt + 60_000).toISOString(),
    status: 'CONFIRMED',
    message: '已确认登录',
  }
}

export async function exchangeQrLoginSessionMock(qrId: string, pollToken: string): Promise<LoginResponse> {
  await delay(100)
  const session = mockQrSessions.get(qrId)
  if (!session || session.pollToken !== pollToken || !session.confirmed || session.exchanged) {
    throw new Error('二维码交换凭证无效或已使用')
  }
  session.exchanged = true
  return {
    accessToken: 'mock-token-' + Math.random().toString(36).slice(2),
    user: session.user,
  }
}

export async function cancelQrLoginSessionMock(qrId: string, pollToken: string): Promise<void> {
  await delay(100)
  const session = mockQrSessions.get(qrId)
  if (!session || session.pollToken !== pollToken) {
    throw new Error('二维码取消凭证无效')
  }
  session.canceled = true
}

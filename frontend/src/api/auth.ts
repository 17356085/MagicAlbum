import api from './client'
import {
  cancelQrLoginSessionMock,
  confirmQrLoginSessionMock,
  createQrLoginSessionMock,
  exchangeQrLoginSessionMock,
  finishEmailCodeLoginMock,
  finishPhoneCodeLoginMock,
  getQrLoginStatusMock,
  loginWithEmailPasswordMock,
  loginWithPhonePasswordMock,
  scanQrLoginSessionMock,
  startEmailCodeLoginMock,
  startPhoneCodeLoginMock,
} from '@/services/authMock'
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
} from '@/types'

const useMock = import.meta.env.VITE_USE_API_MOCK === 'true'

export class AuthProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AuthProtocolError'
  }
}

const QR_LOGIN_STATUSES = new Set<QrLoginStatus>(['PENDING', 'SCANNED', 'CONFIRMED', 'EXPIRED', 'CANCELLED'])
const TOKEN_FIELDS = ['accessToken', 'access_token', 'token', 'jwt', 'id_token'] as const

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function unwrapPayload(resp: unknown, context: string): Record<string, unknown> {
  if (!isRecord(resp)) {
    throw new AuthProtocolError(`${context}响应格式错误`)
  }

  const nested = resp.data ?? resp.result
  if (nested !== undefined) {
    if (!isRecord(nested)) {
      throw new AuthProtocolError(`${context}响应格式错误`)
    }
    return nested
  }
  return resp
}

function requireNonEmptyString(value: unknown, field: string, context: string): string {
  if (typeof value !== 'string' || !value.trim()) {
    throw new AuthProtocolError(`${context}响应缺少有效的 ${field}`)
  }
  return value.trim()
}

function optionalString(value: unknown, field: string, context: string): string | undefined {
  if (value === undefined || value === null || value === '') return undefined
  if (typeof value !== 'string') {
    throw new AuthProtocolError(`${context}响应中的 ${field} 格式错误`)
  }
  return value
}

function requirePositiveNumber(value: unknown, field: string, context: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    throw new AuthProtocolError(`${context}响应缺少有效的 ${field}`)
  }
  return value
}

function parseQrStatus(value: unknown, context: string): QrLoginStatus {
  if (typeof value !== 'string' || !QR_LOGIN_STATUSES.has(value as QrLoginStatus)) {
    throw new AuthProtocolError(`${context}响应中的 status 格式错误`)
  }
  return value as QrLoginStatus
}

function parseUser(value: unknown, context: string): LoginResponse['user'] {
  if (value === undefined || value === null) return null
  if (!isRecord(value)) {
    throw new AuthProtocolError(`${context}响应中的用户信息格式错误`)
  }
  return value as unknown as LoginResponse['user']
}

function rejectTokenFields(payload: Record<string, unknown>, context: string): void {
  if (TOKEN_FIELDS.some((field) => Object.prototype.hasOwnProperty.call(payload, field))) {
    throw new AuthProtocolError(`${context}响应不应包含访问令牌`)
  }
}

function normalizeAuthResponse(resp: unknown): LoginResponse {
  const payload = unwrapPayload(resp, '认证')
  const accessToken = payload.accessToken ?? payload.access_token
  const token = requireNonEmptyString(accessToken, 'accessToken', '认证')
  const user = parseUser(payload.user ?? payload.profile ?? payload.userInfo ?? payload.account, '认证')
  return { accessToken: token, user }
}

function parseQrSession(resp: unknown): QrLoginSession {
  const payload = unwrapPayload(resp, '二维码创建')
  return {
    qrId: requireNonEmptyString(payload.qrId, 'qrId', '二维码创建'),
    pollToken: requireNonEmptyString(payload.pollToken, 'pollToken', '二维码创建'),
    qrUrl: requireNonEmptyString(payload.qrUrl, 'qrUrl', '二维码创建'),
    expiresAt: requireNonEmptyString(payload.expiresAt, 'expiresAt', '二维码创建'),
    status: parseQrStatus(payload.status, '二维码创建'),
  }
}

function parseQrStatusResponse(resp: unknown, expectedQrId: string, pollToken: string): QrLoginStatusResponse {
  const payload = unwrapPayload(resp, '二维码状态')
  rejectTokenFields(payload, '二维码状态')
  const qrId = requireNonEmptyString(payload.qrId, 'qrId', '二维码状态')
  if (qrId !== expectedQrId) {
    throw new AuthProtocolError('二维码状态响应与当前会话不匹配')
  }

  const message = optionalString(payload.message, 'message', '二维码状态')
  return {
    qrId,
    pollToken,
    qrUrl: requireNonEmptyString(payload.qrUrl, 'qrUrl', '二维码状态'),
    expiresAt: requireNonEmptyString(payload.expiresAt, 'expiresAt', '二维码状态'),
    status: parseQrStatus(payload.status, '二维码状态'),
    user: parseUser(payload.user, '二维码状态'),
    ...(message ? { message } : {}),
  }
}

function parseCodeStartResponse(
  resp: unknown,
  channel: 'email' | 'phone',
): StartEmailCodeLoginResponse | StartPhoneCodeLoginResponse {
  const context = `${channel}验证码发送`
  const payload = unwrapPayload(resp, context)
  const responseChannel = requireNonEmptyString(payload.channel, 'channel', context)
  if (responseChannel !== channel) {
    throw new AuthProtocolError(`${context}响应与请求不匹配`)
  }
  return {
    channel,
    maskedAddress: requireNonEmptyString(payload.maskedAddress, 'maskedAddress', context),
    session: requireNonEmptyString(payload.session, 'session', context),
    expireSeconds: requirePositiveNumber(payload.expireSeconds, 'expireSeconds', context),
    cooldownSeconds: requirePositiveNumber(payload.cooldownSeconds, 'cooldownSeconds', context),
  }
}

export async function loginWithPhonePassword({
  phone,
  password,
  verifyToken,
  verifyProvider,
  verifyScene,
}: LoginWithPhonePasswordRequest): Promise<LoginResponse> {
  if (useMock) {
    return loginWithPhonePasswordMock({ phone, password, verifyToken, verifyProvider, verifyScene })
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
    return loginWithEmailPasswordMock({ email, password, verifyToken, verifyProvider, verifyScene })
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
    return startEmailCodeLoginMock({ channel, address, verifyToken, verifyProvider, verifyScene })
  }

  const { data } = await api.post('/auth/code/start', {
    channel,
    address,
    verifyToken,
    verifyProvider,
    verifyScene,
  })
  return parseCodeStartResponse(data, 'email') as StartEmailCodeLoginResponse
}

export async function finishEmailCodeLogin({
  channel,
  address,
  code,
  session,
}: FinishEmailCodeLoginRequest): Promise<LoginResponse> {
  if (useMock) {
    return finishEmailCodeLoginMock({ channel, address, code, session })
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
    return startPhoneCodeLoginMock({ channel, address, verifyToken, verifyProvider, verifyScene })
  }

  const { data } = await api.post('/auth/code/start', {
    channel,
    address,
    verifyToken,
    verifyProvider,
    verifyScene,
  })
  return parseCodeStartResponse(data, 'phone') as StartPhoneCodeLoginResponse
}

export async function finishPhoneCodeLogin({
  channel,
  address,
  code,
  session,
}: FinishPhoneCodeLoginRequest): Promise<LoginResponse> {
  if (useMock) {
    return finishPhoneCodeLoginMock({ channel, address, code, session })
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
    return parseQrSession(await createQrLoginSessionMock())
  }
  const { data } = await api.post('/auth/qr')
  return parseQrSession(data)
}

export async function getQrLoginStatus(qrId: string, pollToken: string): Promise<QrLoginStatusResponse> {
  const normalizedQrId = requireNonEmptyString(qrId, 'qrId', '二维码状态请求')
  const normalizedPollToken = requireNonEmptyString(pollToken, 'pollToken', '二维码状态请求')
  if (useMock) {
    return parseQrStatusResponse(await getQrLoginStatusMock(normalizedQrId, normalizedPollToken), normalizedQrId, normalizedPollToken)
  }

  const { data } = await api.post('/auth/qr/status', {
    qrId: normalizedQrId,
    pollToken: normalizedPollToken,
  })
  return parseQrStatusResponse(data, normalizedQrId, normalizedPollToken)
}

export async function scanQrLoginSession(qrId: string): Promise<QrLoginStatusResponse> {
  const normalizedQrId = requireNonEmptyString(qrId, 'qrId', '二维码扫码请求')
  if (useMock) {
    return parseQrStatusResponse(await scanQrLoginSessionMock(normalizedQrId), normalizedQrId, '')
  }

  const { data } = await api.post('/auth/qr/scan', { qrId: normalizedQrId })
  return parseQrStatusResponse(data, normalizedQrId, '')
}

export async function confirmQrLoginSession(qrId: string): Promise<QrLoginStatusResponse> {
  const normalizedQrId = requireNonEmptyString(qrId, 'qrId', '二维码确认请求')
  if (useMock) {
    return parseQrStatusResponse(await confirmQrLoginSessionMock(normalizedQrId), normalizedQrId, '')
  }

  const { data } = await api.post('/auth/qr/confirm', { qrId: normalizedQrId })
  return parseQrStatusResponse(data, normalizedQrId, '')
}

export async function exchangeQrLoginSession(qrId: string, pollToken: string): Promise<LoginResponse> {
  const normalizedQrId = requireNonEmptyString(qrId, 'qrId', '二维码交换请求')
  const normalizedPollToken = requireNonEmptyString(pollToken, 'pollToken', '二维码交换请求')
  if (useMock) {
    return normalizeAuthResponse(await exchangeQrLoginSessionMock(normalizedQrId, normalizedPollToken))
  }
  const { data } = await api.post('/auth/qr/exchange', { qrId: normalizedQrId, pollToken: normalizedPollToken })
  return normalizeAuthResponse(data)
}

export async function exchangeOAuthCode(code: string): Promise<LoginResponse> {
  const normalizedCode = requireNonEmptyString(code, 'code', 'OAuth 交换请求')
  const { data } = await api.post('/auth/oauth/exchange', { code: normalizedCode })
  return normalizeAuthResponse(data)
}

export async function cancelQrLoginSession(qrId: string, pollToken: string): Promise<void> {
  const normalizedQrId = requireNonEmptyString(qrId, 'qrId', '二维码取消请求')
  const normalizedPollToken = requireNonEmptyString(pollToken, 'pollToken', '二维码取消请求')
  if (useMock) {
    return cancelQrLoginSessionMock(normalizedQrId, normalizedPollToken)
  }
  await api.post('/auth/qr/cancel', { qrId: normalizedQrId, pollToken: normalizedPollToken })
}

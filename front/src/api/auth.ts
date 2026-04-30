import api from './client'
import {
  cancelQrLoginSessionMock,
  confirmQrLoginSessionMock,
  createQrLoginSessionMock,
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

function normalizeAuthResponse(resp: unknown): LoginResponse {
  const root = (resp ?? {}) as Record<string, unknown>
  const payload = ((root.data ?? root.result ?? root) ?? {}) as Record<string, unknown>
  const accessToken =
    payload.accessToken ?? payload.access_token ?? payload.token ?? payload.jwt ?? payload.id_token
  const user = (payload.user ?? payload.profile ?? payload.userInfo ?? payload.account ?? null) as LoginResponse['user']
  return { accessToken: String(accessToken ?? ''), user }
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
    return createQrLoginSessionMock()
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
    return getQrLoginStatusMock(qrId)
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
    user: authPayload.user ?? ((payload.user as LoginResponse['user']) ?? null),
    message: payload.message ? String(payload.message) : undefined,
  }
}

export async function scanQrLoginSession(qrId: string): Promise<QrLoginStatusResponse> {
  if (useMock) {
    return scanQrLoginSessionMock(qrId)
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
    user: (payload.user as LoginResponse['user']) ?? null,
  }
}

export async function confirmQrLoginSession(qrId: string): Promise<QrLoginStatusResponse> {
  if (useMock) {
    return confirmQrLoginSessionMock(qrId)
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
    user: authPayload.user ?? ((payload.user as LoginResponse['user']) ?? null),
  }
}

export async function cancelQrLoginSession(qrId: string): Promise<void> {
  if (useMock) {
    return cancelQrLoginSessionMock(qrId)
  }
  await api.post('/auth/qr/cancel', { qrId })
}

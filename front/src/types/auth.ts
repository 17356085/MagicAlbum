import type { User } from './user'

export type AuthVerifyProvider = 'mock-manual' | 'turnstile' | 'geetest' | 'aliyun' | 'tencent'
export type AuthVerifyScene = 'login' | 'register'
export type OAuthProvider = 'github' | 'google' | 'apple' | 'wechat'

export interface AuthVerifyPayload {
  verifyToken?: string
  verifyProvider?: AuthVerifyProvider
  verifyScene?: AuthVerifyScene
}

export interface LoginWithPhonePasswordPayload {
  phone: string
  password: string
}

export interface LoginWithPhonePasswordRequest extends LoginWithPhonePasswordPayload, AuthVerifyPayload {}

export interface LoginWithEmailPasswordPayload {
  email: string
  password: string
}

export interface LoginWithEmailPasswordRequest extends LoginWithEmailPasswordPayload, AuthVerifyPayload {}

export interface StartEmailCodeLoginRequest extends AuthVerifyPayload {
  channel: 'email'
  address: string
}

export interface StartEmailCodeLoginResponse {
  channel: 'email'
  maskedAddress: string
  session: string
  expireSeconds: number
  cooldownSeconds: number
}

export interface FinishEmailCodeLoginRequest {
  channel: 'email'
  address: string
  code: string
  session: string
}

export interface StartPhoneCodeLoginRequest extends AuthVerifyPayload {
  channel: 'phone'
  address: string
}

export interface StartPhoneCodeLoginResponse {
  channel: 'phone'
  maskedAddress: string
  session: string
  expireSeconds: number
  cooldownSeconds: number
}

export interface FinishPhoneCodeLoginRequest {
  channel: 'phone'
  address: string
  code: string
  session: string
}

export interface LoginResponse {
  accessToken: string
  user: User | null
}

export type QrLoginStatus = 'PENDING' | 'SCANNED' | 'CONFIRMED' | 'EXPIRED' | 'CANCELED'

export interface QrLoginSession {
  qrId: string
  qrUrl: string
  expiresAt: string
  status: QrLoginStatus
}

export interface QrLoginStatusResponse extends QrLoginSession {
  accessToken?: string
  user?: User | null
  message?: string
}

export interface PersistedAuthState {
  accessToken: string
  user: User | null
}

import type { AuthVerifyPayload, AuthVerifyProvider, AuthVerifyScene } from '@/types'

export type AuthVerifyMode = 'off' | 'mock' | 'turnstile'
export type AuthVerifyStatus = 'idle' | 'verifying' | 'verified' | 'failed' | 'expired' | 'unavailable'

const verifyMode = resolveVerifyMode()

export interface StartVerifyOptions {
  scene: AuthVerifyScene
}

export interface AuthVerifyResult {
  token: string
  provider: AuthVerifyProvider
  scene: AuthVerifyScene
  verifiedAt: string
  expiresAt?: string
}

function resolveVerifyMode(): AuthVerifyMode {
  const raw = String(import.meta.env.VITE_AUTH_VERIFY_MODE || '').trim().toLowerCase()
  if (raw === 'mock') return 'mock'
  if (raw === 'turnstile') return 'turnstile'
  if (import.meta.env.VITE_USE_API_MOCK === 'true') return 'mock'
  return 'off'
}

function buildMockVerifyToken(): string {
  return `mock_verify_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

export async function startVerify({ scene }: StartVerifyOptions): Promise<AuthVerifyResult> {
  if (verifyMode !== 'mock') {
    throw new Error('当前验证模式不支持手动触发验证')
  }
  await new Promise((resolve) => setTimeout(resolve, 300))
  return createVerifyResult({
    token: buildMockVerifyToken(),
    provider: 'mock-manual',
    scene,
    expiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
  })
}

export function createVerifyResult({
  token,
  provider,
  scene,
  expiresAt,
}: {
  token: string
  provider: AuthVerifyProvider
  scene: AuthVerifyScene
  expiresAt?: string
}): AuthVerifyResult {
  return {
    token,
    provider,
    scene,
    verifiedAt: new Date().toISOString(),
    expiresAt,
  }
}

export function resetVerify(): void {
  // 由具体验证方式自行在组件层执行 reset。
}

export function getVerifyToken(result: AuthVerifyResult | null | undefined): string {
  return result?.token || ''
}

export function isVerified(result: AuthVerifyResult | null | undefined): boolean {
  return Boolean(result?.token)
}

export function toVerifyPayload(result: AuthVerifyResult | null | undefined): AuthVerifyPayload {
  if (!result?.token) return {}
  return {
    verifyToken: result.token,
    verifyProvider: result.provider,
    verifyScene: result.scene,
  }
}

export function getAuthVerifyMode(): AuthVerifyMode {
  return verifyMode
}

export function isMockVerifyEnabled(): boolean {
  return verifyMode === 'mock'
}

export function isTurnstileVerifyEnabled(): boolean {
  return verifyMode === 'turnstile'
}

export function isAuthVerifyEnabled(): boolean {
  return verifyMode !== 'off'
}

export function getTurnstileSiteKey(): string {
  return String(import.meta.env.VITE_TURNSTILE_SITE_KEY || '').trim()
}

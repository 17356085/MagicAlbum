import type { OAuthProvider } from '@/types'

export const OAUTH_BROWSER_PROOF_STORAGE_KEY = 'oauthBrowserProof'
export const OAUTH_BROWSER_PROOF_TTL_MS = 5 * 60 * 1000

export interface OAuthBrowserProof {
  provider: OAuthProvider
  verifier: string
  expiresAt: number
}

export interface CreatedOAuthBrowserProof extends OAuthBrowserProof {
  challenge: string
}

const OAUTH_PROVIDERS = new Set<OAuthProvider>(['github', 'google', 'apple', 'wechat'])
const OAUTH_CODE_VERIFIER_PATTERN = /^[A-Za-z0-9._~-]{43,128}$/

export function isOAuthProvider(value: unknown): value is OAuthProvider {
  return typeof value === 'string' && OAUTH_PROVIDERS.has(value as OAuthProvider)
}

function getWebCrypto(): Crypto {
  const value = globalThis.crypto
  if (!value?.getRandomValues || !value.subtle) {
    throw new Error('当前浏览器不支持 OAuth 登录校验')
  }
  return value
}

function encodeBase64Url(bytes: Uint8Array): string {
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

export function generateOAuthCodeVerifier(): string {
  const bytes = new Uint8Array(32)
  getWebCrypto().getRandomValues(bytes)
  return encodeBase64Url(bytes)
}

export async function createOAuthCodeChallenge(verifier: string): Promise<string> {
  const normalized = String(verifier || '')
  if (!OAUTH_CODE_VERIFIER_PATTERN.test(normalized)) throw new Error('OAuth code verifier 格式无效')
  const digest = await getWebCrypto().subtle.digest('SHA-256', new TextEncoder().encode(normalized))
  return encodeBase64Url(new Uint8Array(digest))
}

export async function createOAuthBrowserProof(
  provider: OAuthProvider,
  now = Date.now(),
): Promise<CreatedOAuthBrowserProof> {
  if (!isOAuthProvider(provider)) throw new Error('OAuth Provider 无效')
  const verifier = generateOAuthCodeVerifier()
  return {
    provider,
    verifier,
    challenge: await createOAuthCodeChallenge(verifier),
    expiresAt: now + OAUTH_BROWSER_PROOF_TTL_MS,
  }
}

function getSessionStorage(): Storage | null {
  try {
    return globalThis.sessionStorage
  } catch (_) {
    return null
  }
}

function parseStoredProof(raw: string | null): OAuthBrowserProof | null {
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>
    if (!isOAuthProvider(parsed.provider)) return null
    if (typeof parsed.verifier !== 'string' || !OAUTH_CODE_VERIFIER_PATTERN.test(parsed.verifier)) return null
    if (typeof parsed.expiresAt !== 'number' || !Number.isFinite(parsed.expiresAt)) return null
    return {
      provider: parsed.provider,
      verifier: parsed.verifier,
      expiresAt: parsed.expiresAt,
    }
  } catch (_) {
    return null
  }
}

export function storeOAuthBrowserProof(proof: OAuthBrowserProof): boolean {
  if (!isOAuthProvider(proof.provider)
    || !OAUTH_CODE_VERIFIER_PATTERN.test(proof.verifier)
    || !Number.isFinite(proof.expiresAt)) return false
  const storage = getSessionStorage()
  if (!storage) return false
  try {
    storage.setItem(OAUTH_BROWSER_PROOF_STORAGE_KEY, JSON.stringify({
      provider: proof.provider,
      verifier: proof.verifier,
      expiresAt: proof.expiresAt,
    }))
    return true
  } catch (_) {
    return false
  }
}

export function readOAuthBrowserProof(provider?: OAuthProvider, now = Date.now()): OAuthBrowserProof | null {
  const storage = getSessionStorage()
  if (!storage) return null
  let proof: OAuthBrowserProof | null
  try {
    proof = parseStoredProof(storage.getItem(OAUTH_BROWSER_PROOF_STORAGE_KEY))
  } catch (_) {
    return null
  }
  if (!proof || proof.expiresAt <= now) {
    try { storage.removeItem(OAUTH_BROWSER_PROOF_STORAGE_KEY) } catch (_) {}
    return null
  }
  if (provider && proof.provider !== provider) return null
  return proof
}

export function consumeOAuthBrowserProof(provider: OAuthProvider, now = Date.now()): string | null {
  const proof = readOAuthBrowserProof(provider, now)
  if (!proof) return null
  const storage = getSessionStorage()
  if (!storage) return null
  try {
    storage.removeItem(OAUTH_BROWSER_PROOF_STORAGE_KEY)
  } catch (_) {
    return null
  }
  return proof.verifier
}

export function clearOAuthBrowserProof(expected?: OAuthBrowserProof): void {
  const storage = getSessionStorage()
  if (!storage) return
  if (!expected) {
    try { storage.removeItem(OAUTH_BROWSER_PROOF_STORAGE_KEY) } catch (_) {}
    return
  }
  try {
    const current = parseStoredProof(storage.getItem(OAUTH_BROWSER_PROOF_STORAGE_KEY))
    if (current
      && current.provider === expected.provider
      && current.verifier === expected.verifier
      && current.expiresAt === expected.expiresAt) {
      storage.removeItem(OAUTH_BROWSER_PROOF_STORAGE_KEY)
    }
  } catch (_) {}
}

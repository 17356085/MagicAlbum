import { beforeEach, describe, expect, it } from 'vitest'
import {
  OAUTH_BROWSER_PROOF_STORAGE_KEY,
  OAUTH_BROWSER_PROOF_TTL_MS,
  clearOAuthBrowserProof,
  createOAuthBrowserProof,
  createOAuthCodeChallenge,
  readOAuthBrowserProof,
  storeOAuthBrowserProof,
} from '@/utils/oauthBrowserProof'

describe('OAuth browser proof', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('matches the RFC 7636 S256 test vector', async () => {
    await expect(createOAuthCodeChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'))
      .resolves.toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  })

  it('creates a 32-byte verifier and stores only the tab proof fields', async () => {
    const proof = await createOAuthBrowserProof('google', 1_000)

    expect(proof.verifier).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(proof.challenge).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(proof.expiresAt).toBe(1_000 + OAUTH_BROWSER_PROOF_TTL_MS)
    expect(storeOAuthBrowserProof(proof)).toBe(true)
    expect(JSON.parse(sessionStorage.getItem(OAUTH_BROWSER_PROOF_STORAGE_KEY) || '{}'))
      .toEqual({ provider: 'google', verifier: proof.verifier, expiresAt: proof.expiresAt })
  })

  it('rejects a mismatched or expired proof without accepting it', () => {
    storeOAuthBrowserProof({ provider: 'google', verifier: 'v'.repeat(43), expiresAt: 5_000 })

    expect(readOAuthBrowserProof('apple', 4_000)).toBeNull()
    expect(readOAuthBrowserProof('google', 5_000)).toBeNull()
    expect(sessionStorage.getItem(OAUTH_BROWSER_PROOF_STORAGE_KEY)).toBeNull()
  })

  it('clears only the exact expected proof snapshot', () => {
    const proof = { provider: 'google' as const, verifier: 'v'.repeat(43), expiresAt: 5_000 }
    storeOAuthBrowserProof(proof)

    clearOAuthBrowserProof({ ...proof, verifier: 'n'.repeat(43) })
    expect(readOAuthBrowserProof('google', 4_000)).toEqual(proof)

    clearOAuthBrowserProof(proof)
    expect(sessionStorage.getItem(OAUTH_BROWSER_PROOF_STORAGE_KEY)).toBeNull()
  })

  it('rejects verifier values outside the RFC 7636 character and length bounds', async () => {
    await expect(createOAuthCodeChallenge('too-short')).rejects.toThrow('格式无效')
    expect(storeOAuthBrowserProof({ provider: 'google', verifier: 'too-short', expiresAt: 5_000 })).toBe(false)
    sessionStorage.setItem(OAUTH_BROWSER_PROOF_STORAGE_KEY, JSON.stringify({
      provider: 'google', verifier: 'too-short', expiresAt: 5_000,
    }))
    expect(readOAuthBrowserProof('google', 4_000)).toBeNull()
  })

})

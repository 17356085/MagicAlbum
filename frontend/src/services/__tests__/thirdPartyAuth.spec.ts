import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { getOAuthProviderConfig, startOAuthAuthorize } from '@/services/thirdPartyAuth'

describe('third-party OAuth browser binding', () => {
  function stubLocationAssign() {
    const assign = vi.fn()
    const windowStub = Object.create(window) as Window & typeof globalThis
    Object.defineProperty(windowStub, 'location', {
      configurable: true, value: { assign, href: 'https://community.example.test/discover' },
    })
    vi.stubGlobal('window', windowStub)
    return assign
  }

  beforeEach(() => {
    sessionStorage.clear()
    vi.stubEnv('VITE_AUTH_OAUTH_GOOGLE_ENABLED', 'true')
    vi.stubEnv('VITE_AUTH_OAUTH_GOOGLE_AUTHORIZE_URL', 'https://api.example.test/auth/oauth/authorize?provider=google')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('appends S256 challenge and stores only a tab-scoped verifier', async () => {
    const assign = stubLocationAssign()

    const result = await startOAuthAuthorize('google', '/settings?tab=account')

    expect(result.ok).toBe(true)
    const target = assign.mock.calls[0]?.[0] as string
    const url = new URL(target)
    expect(url.searchParams.get('code_challenge_method')).toBe('S256')
    expect(url.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(target).not.toContain('code_verifier')
    const proof = JSON.parse(sessionStorage.getItem('oauthBrowserProof') || '{}') as Record<string, unknown>
    expect(proof.provider).toBe('google')
    expect(proof.verifier).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(proof.expiresAt).toEqual(expect.any(Number))
  })

  it('checks enabled and configured URL before starting WebCrypto', async () => {
    const assign = stubLocationAssign()
    vi.stubEnv('VITE_AUTH_OAUTH_GOOGLE_ENABLED', 'false')

    await expect(startOAuthAuthorize('google', '/discover')).resolves.toMatchObject({ ok: false })
    expect(assign).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()

    vi.stubEnv('VITE_AUTH_OAUTH_GOOGLE_ENABLED', 'true')
    vi.stubEnv('VITE_AUTH_OAUTH_GOOGLE_AUTHORIZE_URL', '')
    vi.stubEnv('VITE_API_BASE_URL', '')
    expect(getOAuthProviderConfig('google')?.authorizeUrl).toBe('')
    await expect(startOAuthAuthorize('google', '/discover')).resolves.toMatchObject({ ok: false })
    expect(assign).not.toHaveBeenCalled()
  })

  it('replaces stale challenge parameters on a relative authorization URL and preserves its fragment', async () => {
    const assign = stubLocationAssign()
    vi.stubEnv('VITE_AUTH_OAUTH_GOOGLE_AUTHORIZE_URL',
      '/api/v1/auth/oauth/authorize?provider=google&code_challenge=old&code_challenge_method=plain#login')
    await expect(startOAuthAuthorize('google', '/discover')).resolves.toMatchObject({ ok: true })
    const url = new URL(assign.mock.calls[0]?.[0] as string)
    expect(url.origin).toBe('https://community.example.test')
    expect(url.hash).toBe('#login')
    expect(url.searchParams.getAll('code_challenge')).toHaveLength(1)
    expect(url.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(url.searchParams.getAll('code_challenge_method')).toEqual(['S256'])
  })

  it('does not write proof or navigate when the caller becomes stale during digest', async () => {
    const assign = stubLocationAssign()
    let current = true
    const pending = startOAuthAuthorize('google', '/discover', () => current)
    current = false

    await expect(pending).resolves.toMatchObject({ ok: false, cancelled: true })
    expect(assign).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('oauthBrowserProof')).toBeNull()
  })
})

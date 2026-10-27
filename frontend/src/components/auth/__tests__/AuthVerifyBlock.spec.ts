import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const verifyState = vi.hoisted(() => ({ mode: 'mock' as 'mock' | 'turnstile' }))
const verifyMocks = vi.hoisted(() => ({
  createVerifyResult: vi.fn(({ token, provider, scene, expiresAt }: {
    token: string
    provider: 'mock-manual' | 'turnstile'
    scene: 'login' | 'register'
    expiresAt?: string
  }) => ({
    token,
    provider,
    scene,
    verifiedAt: '2026-01-01T00:00:00.000Z',
    expiresAt,
  })),
  getAuthVerifyMode: vi.fn(() => verifyState.mode),
  getTurnstileSiteKey: vi.fn(() => 'test-site-key'),
  getVerifyToken: vi.fn((result: { token?: string } | null | undefined) => result?.token || ''),
  isMockVerifyEnabled: vi.fn(() => verifyState.mode === 'mock'),
  isTurnstileVerifyEnabled: vi.fn(() => verifyState.mode === 'turnstile'),
  isVerified: vi.fn((result: { token?: string } | null | undefined) => Boolean(result?.token)),
  resetVerify: vi.fn(),
  startVerify: vi.fn(),
  toVerifyPayload: vi.fn((result: { token?: string; provider?: string; scene?: string } | null | undefined) => {
    if (!result?.token) return {}
    return {
      verifyToken: result.token,
      verifyProvider: result.provider,
      verifyScene: result.scene,
    }
  }),
}))

vi.mock('@/services/authVerify', () => verifyMocks)

type VerifyResult = {
  token: string
  provider: 'mock-manual' | 'turnstile'
  scene: 'login' | 'register'
  verifiedAt: string
  expiresAt?: string
}

type VerifyBlockExposed = {
  ensureVerified: () => Promise<VerifyResult | null>
  reset: () => void
  resetIfMatches: (expected: VerifyResult) => boolean
  isVerified: () => boolean
  getVerifyResult: () => VerifyResult | null
}

type Deferred<T> = {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
}

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((nextResolve, nextReject) => {
    resolve = nextResolve
    reject = nextReject
  })
  return { promise, resolve, reject }
}

async function loadAuthVerifyBlock(mode: 'mock' | 'turnstile') {
  verifyState.mode = mode
  vi.resetModules()
  const module = await import('../AuthVerifyBlock.vue')
  return module.default
}

async function settle(): Promise<void> {
  await flushPromises()
  await flushPromises()
}

function exposed(wrapper: ReturnType<typeof mount>): VerifyBlockExposed {
  return wrapper.vm as unknown as VerifyBlockExposed
}

describe('AuthVerifyBlock lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    verifyState.mode = 'mock'
    verifyMocks.startVerify.mockReset()
    verifyMocks.startVerify.mockResolvedValue({
      token: 'mock-proof',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    })
    delete window.turnstile
    document.getElementById('cf-turnstile-script')?.remove()
  })

  it('resets only when the expected proof is still current', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('mock')
    const firstProof: VerifyResult = {
      token: 'first-proof',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    }
    const secondProof: VerifyResult = {
      token: 'second-proof',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:01:00.000Z',
    }
    verifyMocks.startVerify
      .mockResolvedValueOnce(firstProof)
      .mockResolvedValueOnce(secondProof)
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    const block = exposed(wrapper)

    const consumed = await block.ensureVerified()
    expect(consumed).toBe(firstProof)
    expect(block.resetIfMatches(consumed!)).toBe(true)
    expect(block.getVerifyResult()).toBeNull()
    expect(wrapper.emitted('reset')).toHaveLength(1)

    const newer = await block.ensureVerified()
    expect(newer).toBe(secondProof)
    expect(block.resetIfMatches(firstProof)).toBe(false)
    expect(block.getVerifyResult()).toMatchObject(secondProof)
    expect(wrapper.emitted('reset')).toHaveLength(1)
    wrapper.unmount()
  })

  it('ignores a mock verification result that resolves after reset or unmount', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('mock')
    const pending = deferred<VerifyResult>()
    verifyMocks.startVerify.mockReturnValueOnce(pending.promise)
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    const block = exposed(wrapper)
    const verification = block.ensureVerified()
    block.reset()
    pending.resolve({
      token: 'late-proof',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    })
    expect(await verification).toBeNull()
    expect(block.getVerifyResult()).toBeNull()
    expect(wrapper.emitted('verified')).toBeUndefined()

    const pendingAfterUnmount = deferred<VerifyResult>()
    verifyMocks.startVerify.mockReturnValueOnce(pendingAfterUnmount.promise)
    const verificationAfterUnmount = block.ensureVerified()
    wrapper.unmount()
    pendingAfterUnmount.resolve({
      token: 'unmounted-proof',
      provider: 'mock-manual',
      scene: 'register',
      verifiedAt: '2026-01-01T00:00:00.000Z',
    })
    expect(await verificationAfterUnmount).toBeNull()
    expect(wrapper.emitted('verified')).toBeUndefined()
  })

  it('shares one pending manual verification between concurrent callers', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('mock')
    const pending = deferred<VerifyResult>()
    verifyMocks.startVerify.mockReturnValue(pending.promise)
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    try {
      const block = exposed(wrapper)
      const first = block.ensureVerified()
      const second = block.ensureVerified()
      const proof: VerifyResult = {
        token: 'shared-proof', provider: 'mock-manual', scene: 'register', verifiedAt: '2026-01-01T00:00:00Z',
      }
      pending.resolve(proof)
      expect(await Promise.all([first, second])).toEqual([proof, proof])
      expect(verifyMocks.startVerify).toHaveBeenCalledTimes(1)
      expect(wrapper.emitted('verified')).toHaveLength(1)
    } finally { wrapper.unmount() }
  })

  it('does not let an old manual completion release a newer pending verification', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('mock')
    const older = deferred<VerifyResult>()
    const newer = deferred<VerifyResult>()
    verifyMocks.startVerify.mockReturnValueOnce(older.promise).mockReturnValue(newer.promise)
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    try {
      const block = exposed(wrapper)
      const oldRequest = block.ensureVerified()
      block.reset()
      const newRequest = block.ensureVerified()
      older.resolve({ token: 'old', provider: 'mock-manual', scene: 'register', verifiedAt: '2026-01-01T00:00:00Z' })
      expect(await oldRequest).toBeNull()
      const sharedNewRequest = block.ensureVerified()
      const proof: VerifyResult = {
        token: 'new', provider: 'mock-manual', scene: 'register', verifiedAt: '2026-01-01T00:01:00Z',
      }
      newer.resolve(proof)
      expect(await Promise.all([newRequest, sharedNewRequest])).toEqual([proof, proof])
      expect(verifyMocks.startVerify).toHaveBeenCalledTimes(2)
      expect(wrapper.emitted('verified')).toHaveLength(1)
    } finally { wrapper.unmount() }
  })

  it('ignores calls to the exposed reset method after unmount', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('mock')
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    const block = exposed(wrapper)
    wrapper.unmount()
    block.reset()
    expect(wrapper.emitted('reset')).toBeUndefined()
    expect(verifyMocks.resetVerify).not.toHaveBeenCalled()
  })

  it('coalesces initial Turnstile rendering and ignores stale callbacks after reset', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('turnstile')
    type TurnstileOptions = {
      callback?: (token: string) => void
      'error-callback'?: (errorCode?: string) => void
      'expired-callback'?: () => void
    }
    let firstOptions: TurnstileOptions | undefined
    let secondOptions: TurnstileOptions | undefined
    let renderCount = 0
    const turnstile = {
      render: vi.fn((_container: string | HTMLElement, options: TurnstileOptions) => {
        renderCount += 1
        if (renderCount === 1) firstOptions = options
        else secondOptions = options
        return `widget-${renderCount}`
      }),
      reset: vi.fn(),
      remove: vi.fn(),
      getResponse: vi.fn(() => ''),
    }
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    const block = exposed(wrapper)
    await settle()
    const script = document.getElementById('cf-turnstile-script') as HTMLScriptElement | null
    expect(script).not.toBeNull()

    const ensureDuringInitialRender = block.ensureVerified()
    window.turnstile = turnstile
    script?.dispatchEvent(new Event('load'))
    await settle()
    expect(renderCount).toBe(1)
    expect(await ensureDuringInitialRender).toBeNull()
    expect(firstOptions).toBeDefined()

    firstOptions?.callback?.('stale-before-reset')
    await settle()
    expect(block.getVerifyResult()?.token).toBe('stale-before-reset')

    block.reset()
    await settle()
    expect(renderCount).toBe(2)
    expect(turnstile.remove).toHaveBeenCalledWith('widget-1')
    expect(secondOptions).toBeDefined()

    firstOptions?.callback?.('stale-token')
    firstOptions?.['error-callback']?.('stale-error')
    firstOptions?.['expired-callback']?.()
    await settle()
    expect(block.getVerifyResult()).toBeNull()
    expect(wrapper.emitted('verified')).toHaveLength(1)

    secondOptions?.callback?.('fresh-token')
    await settle()
    expect(block.getVerifyResult()?.token).toBe('fresh-token')
    expect(wrapper.emitted('verified')).toHaveLength(2)
    wrapper.unmount()
  })

  it('does not render Turnstile after unmount while script loading', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('turnstile')
    const render = vi.fn(() => 'late-widget')
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    await settle()
    const script = document.getElementById('cf-turnstile-script') as HTMLScriptElement | null
    expect(script).not.toBeNull()

    wrapper.unmount()
    window.turnstile = {
      render,
      reset: vi.fn(),
      remove: vi.fn(),
      getResponse: vi.fn(() => ''),
    }
    script?.dispatchEvent(new Event('load'))
    await settle()

    expect(render).not.toHaveBeenCalled()
  })

  it('clears a failed script load so a later reset can retry rendering', async () => {
    const AuthVerifyBlock = await loadAuthVerifyBlock('turnstile')
    const wrapper = mount(AuthVerifyBlock, { props: { scene: 'register' } })
    await settle()
    const failedScript = document.getElementById('cf-turnstile-script') as HTMLScriptElement | null
    expect(failedScript).not.toBeNull()

    failedScript?.dispatchEvent(new Event('error'))
    await settle()
    expect(document.getElementById('cf-turnstile-script')).toBeNull()
    expect(wrapper.get('button').text()).toContain('重新验证')

    const render = vi.fn(() => 'retry-widget')
    await wrapper.get('button').trigger('click')
    await settle()
    const retryScript = document.getElementById('cf-turnstile-script') as HTMLScriptElement | null
    expect(retryScript).not.toBeNull()
    window.turnstile = {
      render,
      reset: vi.fn(),
      remove: vi.fn(),
      getResponse: vi.fn(() => ''),
    }
    retryScript?.dispatchEvent(new Event('load'))
    await settle()
    expect(render).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createVerifyResult, isVerified, toVerifyPayload } from '../authVerify'

const NOW = Date.parse('2026-01-01T00:00:00.000Z')

function createResult(expiresAt?: string) {
  return createVerifyResult({
    token: 'proof-token',
    provider: 'mock-manual',
    scene: 'login',
    ...(expiresAt === undefined ? {} : { expiresAt }),
  })
}

describe('auth verification expiry', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('accepts a result whose expiry is in the future', () => {
    const result = createResult(new Date(NOW + 1_000).toISOString())

    expect(isVerified(result)).toBe(true)
    expect(toVerifyPayload(result)).toEqual({
      verifyToken: 'proof-token',
      verifyProvider: 'mock-manual',
      verifyScene: 'login',
    })
  })

  it('rejects a result at the exact expiry instant', () => {
    const result = createResult(new Date(NOW).toISOString())

    expect(isVerified(result)).toBe(false)
    expect(toVerifyPayload(result)).toEqual({})
  })

  it('rejects a result whose expiry is in the past', () => {
    const result = createResult(new Date(NOW - 1_000).toISOString())

    expect(isVerified(result)).toBe(false)
    expect(toVerifyPayload(result)).toEqual({})
  })

  it('rejects a result with an invalid expiry timestamp', () => {
    const result = createResult('not-a-date')

    expect(isVerified(result)).toBe(false)
    expect(toVerifyPayload(result)).toEqual({})
  })

  it('keeps a result without an expiry valid', () => {
    const result = createResult()

    expect(isVerified(result)).toBe(true)
    expect(toVerifyPayload(result)).toEqual({
      verifyToken: 'proof-token',
      verifyProvider: 'mock-manual',
      verifyScene: 'login',
    })
  })
})

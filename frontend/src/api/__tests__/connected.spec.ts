import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import { ConnectedAccountsResponseError, listConnectedAccounts } from '../connected'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

const getMock = vi.mocked(api.get)

async function expectInvalidResponse(data: unknown): Promise<void> {
  getMock.mockResolvedValueOnce({ data })
  const request = listConnectedAccounts()
  await expect(request).rejects.toBeInstanceOf(ConnectedAccountsResponseError)
  await expect(request).rejects.toMatchObject({
    name: 'ConnectedAccountsResponseError',
    message: '关联账号状态响应格式无效',
  })
}

describe('connected-account read response contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each([
    ['missing envelope', {}],
    ['null envelope', null],
    ['bare array envelope', []],
    ['map envelope', new Map([['items', []]])],
    ['non-array items', { items: {} }],
  ])('rejects a malformed %s without exposing the payload', async (_label, data) => {
    await expectInvalidResponse(data)
  })

  it.each([
    ['missing provider', { connected: true }],
    ['blank provider', { provider: '  ', connected: true }],
    ['missing connected status', { provider: 'github' }],
    ['string connected status', { provider: 'github', connected: 'true' }],
    ['null connected status', { provider: 'github', connected: null }],
  ])('rejects a row with %s', async (_label, row) => {
    await expectInvalidResponse({ items: [row] })
  })

  it('rejects duplicate providers after trim-and-case canonicalization', async () => {
    await expectInvalidResponse({
      items: [
        { provider: ' GitHub ', connected: true },
        { provider: 'github', connected: false },
      ],
    })
  })

  it.each([
    ['displayName', { provider: 'github', connected: true, displayName: 42 }],
    ['linkedAt', { provider: 'github', connected: true, linkedAt: 42 }],
  ])('rejects a non-string non-null optional DTO field: %s', async (_label, row) => {
    await expectInvalidResponse({ items: [row] })
  })

  it('accepts the backend nullable DTO fields without coercion', async () => {
    const data = {
      items: [{ provider: 'github', connected: false, displayName: null, linkedAt: null }],
    }
    getMock.mockResolvedValueOnce({ data })

    const result = await listConnectedAccounts()

    expect(result).toEqual(data)
    expect(result).not.toBe(data)
    expect(result.items[0]).not.toBe(data.items[0])
    expect(api.get).toHaveBeenCalledWith('/users/me/connected-accounts')
  })

  it('accepts future providers and preserves raw spelling and row extras', async () => {
    const row = {
      provider: '  custom-idp  ',
      connected: true,
      displayName: 'Custom User',
      linkedAt: '2026-10-27T00:00:00Z',
      providerUserId: 'remote-7',
      rowExtra: { source: 'fixture' },
    }
    const data = { items: [row] }
    getMock.mockResolvedValueOnce({ data })

    const result = await listConnectedAccounts()

    expect(result.items[0]).toEqual(row)
    expect(result.items[0]).not.toBe(row)
  })

  it('returns fresh rows without mutating frozen transport input', async () => {
    const row = Object.freeze({
      provider: 'GitHub',
      connected: true,
      displayName: null,
      linkedAt: null,
      rowExtra: 'preserve-me',
    })
    const data = Object.freeze({ items: Object.freeze([row]) })
    const before = structuredClone(data)
    getMock.mockResolvedValueOnce({ data })

    const result = await listConnectedAccounts()

    expect(result.items[0]).toEqual(row)
    expect(result.items[0]).not.toBe(row)
    expect(data).toEqual(before)
  })

  it('passes transport rejections through unchanged', async () => {
    const failure = new Error('transport unavailable')
    getMock.mockRejectedValueOnce(failure)

    await expect(listConnectedAccounts()).rejects.toBe(failure)
  })
})

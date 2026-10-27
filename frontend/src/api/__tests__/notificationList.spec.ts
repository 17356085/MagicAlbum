import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import { listNotifications } from '../notifications'
import { PageResponseError } from '../pagination'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}))

vi.mock('../client', () => ({
  default: {
    get: mocks.get,
  },
}))

const apiMock = api as unknown as { get: ReturnType<typeof vi.fn> }

type RawNotification = Record<string, unknown>

function validRow(overrides: RawNotification = {}): RawNotification {
  return {
    id: 7,
    type: 'reply',
    title: '有人回复了你',
    content: '请查看新的回复',
    read: false,
    createdAt: '2026-10-27T00:00:00Z',
    link: '/threads/7',
    ...overrides,
  }
}

function envelope(items: unknown[], metadata: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    items,
    page: 1,
    size: 20,
    total: items.length,
    ...metadata,
  }
}

function expectSafeResponseError(error: unknown, payload: unknown): void {
  expect(error).toBeInstanceOf(PageResponseError)
  expect((error as Error).message).not.toContain(JSON.stringify(payload))
}

describe('notification list transport baseline', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('preserves valid nullable targets, future string kinds, and high-precision decimal IDs', async () => {
    const row = validRow({
      id: '900719925474099312345678901',
      type: 'future-event',
      title: null,
      content: null,
      createdAt: null,
      actorName: null,
      actorId: null,
      threadId: '900719925474099312345678902',
      targetId: null,
      targetType: 'future-target',
      link: null,
    })
    apiMock.get.mockResolvedValueOnce({ data: envelope([row], { source: 'notification-service' }) })

    const result = await listNotifications({ page: 1, size: 20 })

    expect(result.items).toEqual([row])
    expect(result.source).toBe('notification-service')
    expect(apiMock.get).toHaveBeenCalledWith('/notifications', { params: { page: 1, size: 20 } })
  })

  it('keeps legacy bare arrays and fills missing page metadata', async () => {
    const row = validRow()
    apiMock.get
      .mockResolvedValueOnce({ data: [row] })
      .mockResolvedValueOnce({ data: { items: [row] } })

    await expect(listNotifications({ page: 2, size: 10 })).resolves.toMatchObject({
      items: [row],
      page: 2,
      size: 10,
      total: 1,
    })
    await expect(listNotifications({ page: 3, size: 5 })).resolves.toMatchObject({
      items: [row],
      page: 3,
      size: 5,
      total: 1,
    })
  })

  it('returns fresh rows without mutating frozen input', async () => {
    const row = Object.freeze(validRow({
      title: null,
      targetId: null,
      link: null,
    }))
    const payload = Object.freeze(envelope([row], { source: 'notification-service' }))
    const before = JSON.stringify(payload)
    apiMock.get.mockResolvedValueOnce({ data: payload })

    const result = await listNotifications()

    expect(result.items[0]).toEqual(row)
    expect(result.items[0]).not.toBe(row)
    expect(JSON.stringify(payload)).toBe(before)
  })

  it('preserves an HTTP rejection unchanged', async () => {
    const httpError = Object.assign(new Error('gateway unavailable'), { response: { status: 502 } })
    apiMock.get.mockRejectedValueOnce(httpError)

    await expect(listNotifications()).rejects.toBe(httpError)
  })

  it('rejects a null row instead of passing it to either notification template', async () => {
    const payload = envelope([null], { marker: 'null-row' })
    apiMock.get.mockResolvedValueOnce({ data: payload })

    await expect(listNotifications()).rejects.toSatisfy((error: unknown) => {
      expectSafeResponseError(error, payload)
      return true
    })
  })

  it.each([
    ['missing id', validRow({ id: undefined })],
    ['unsafe numeric id', validRow({ id: Number.MAX_SAFE_INTEGER + 1 })],
    ['missing read', validRow({ read: undefined })],
    ['non-boolean read', validRow({ read: 'false' })],
    ['title object', validRow({ title: { text: 'bad' } })],
    ['title number', validRow({ title: 42 })],
  ])('rejects %s without exposing the payload', async (_label, row) => {
    const payload = envelope([row], { marker: _label })
    apiMock.get.mockResolvedValueOnce({ data: payload })

    await expect(listNotifications()).rejects.toSatisfy((error: unknown) => {
      expectSafeResponseError(error, payload)
      return true
    })
  })

  it.each([
    ['actorId zero', { actorId: 0 }],
    ['threadId zero string', { threadId: '0' }],
    ['targetId unsafe numeric', { targetId: Number.MAX_SAFE_INTEGER + 1 }],
    ['targetId object', { targetId: { id: 7 } }],
  ])('rejects invalid optional notification ID: %s', async (_label, overrides) => {
    const row = validRow(overrides)
    const payload = envelope([row], { marker: _label })
    apiMock.get.mockResolvedValueOnce({ data: payload })

    await expect(listNotifications()).rejects.toSatisfy((error: unknown) => {
      expectSafeResponseError(error, payload)
      return true
    })
  })

  it('rejects duplicate IDs after canonicalizing safe numbers and decimal strings', async () => {
    const payload = envelope([
      validRow({ id: 42 }),
      validRow({ id: '42', title: '重复 ID' }),
    ])
    apiMock.get.mockResolvedValueOnce({ data: payload })

    await expect(listNotifications()).rejects.toSatisfy((error: unknown) => {
      expectSafeResponseError(error, payload)
      return true
    })
  })
})

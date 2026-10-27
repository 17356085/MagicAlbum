import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import {
  markNotificationRead,
  NotificationReadAcknowledgementError,
} from '../notifications'

vi.mock('../client', () => ({
  default: {
    patch: vi.fn(),
  },
}))

describe('markNotificationRead API acknowledgement contract', () => {
  beforeEach(() => {
    vi.mocked(api.patch).mockReset()
  })

  it('returns only a minimal confirmed acknowledgement and ignores extra DTO fields', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({
      data: { id: 7, read: true, title: '不应进入 UI', content: '也不应进入 UI' },
    })

    await expect(markNotificationRead(7)).resolves.toEqual({ id: 7, read: true })
    expect(api.patch).toHaveBeenCalledWith('/notifications/7/read')
  })

  it('matches a large decimal string id without converting it through Number', async () => {
    const id = '900719925474099312345678901'
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { id, read: true, ignored: 'field' } })

    await expect(markNotificationRead(id)).resolves.toEqual({ id, read: true })
    expect(api.patch).toHaveBeenCalledWith(`/notifications/${id}/read`)
  })

  it('accepts equivalent safe number and canonical decimal string ids', async () => {
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { id: '7', read: true } })
    await expect(markNotificationRead(7)).resolves.toEqual({ id: '7', read: true })

    vi.mocked(api.patch).mockResolvedValueOnce({ data: { id: 7, read: true } })
    await expect(markNotificationRead('7')).resolves.toEqual({ id: 7, read: true })
  })

  it('rejects a nearby large decimal id without lossy numeric coercion', async () => {
    const requestedId = '900719925474099312345678901'
    const nearbyId = '900719925474099312345678902'
    vi.mocked(api.patch).mockResolvedValueOnce({ data: { id: nearbyId, read: true } })

    await expect(markNotificationRead(requestedId)).rejects.toBeInstanceOf(NotificationReadAcknowledgementError)
  })

  it.each([
    ['null', null],
    ['array', []],
    ['missing id', { read: true }],
    ['missing read', { id: 7 }],
    ['read false', { id: 7, read: false }],
    ['read string', { id: 7, read: 'true' }],
    ['wrong id', { id: 8, read: true }],
    ['unsafe number id', { id: Number.MAX_SAFE_INTEGER + 1, read: true }],
    ['non-canonical decimal string id', { id: '007', read: true }],
    ['inherited id', Object.assign(Object.create({ id: 7 }), { read: true })],
    ['inherited read', Object.assign(Object.create({ read: true }), { id: 7 })],
  ])('throws a dedicated safe error for an unconfirmed %s response', async (_label, data) => {
    vi.mocked(api.patch).mockResolvedValueOnce({ data })
    const readPromise = markNotificationRead(7)

    await expect(readPromise).rejects.toBeInstanceOf(NotificationReadAcknowledgementError)
    await expect(readPromise).rejects.toThrow('已读结果暂未确认，请重试')
  })

  it('preserves an HTTP rejection unchanged', async () => {
    const httpError = Object.assign(new Error('gateway unavailable'), { response: { status: 502 } })
    vi.mocked(api.patch).mockRejectedValueOnce(httpError)

    await expect(markNotificationRead(7)).rejects.toBe(httpError)
  })
})

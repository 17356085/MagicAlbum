import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createThread } from '../threads'
import { updateThread } from '../my'

const client = vi.hoisted(() => ({ post: vi.fn(), patch: vi.fn() }))
vi.mock('../client', () => ({ default: client }))
const payload = { sectionId: 3, title: '标题', content: '正文', tags: [] }
beforeEach(() => { vi.resetAllMocks() })

describe('thread write response confirmation', () => {
  it('keeps the creation avatar alias and optional metadata after confirming required fields', async () => {
    const body = { id: 77, title: '标题', authorAvatar: '/old.png', extra: { retained: true } }
    client.post.mockResolvedValue({ data: body })
    await expect(createThread(payload)).resolves.toEqual({ ...body, authorAvatarUrl: '/old.png' })
    expect(client.post).toHaveBeenCalledOnce()
  })

  it('compares decimal string ids without lossy conversion, including padded request ids', async () => {
    const id = '9007199254740993123'
    client.patch.mockResolvedValue({ data: { id, title: '原帖' } })
    await expect(updateThread('00' + id, { title: '原帖' })).resolves.toMatchObject({ id })
    client.patch.mockResolvedValue({ data: { id: '9007199254740993124', title: '别帖' } })
    await expect(updateThread(id, { title: '原帖' })).rejects.toMatchObject({ code: 'THREAD_WRITE_UNCONFIRMED' })
  })

  it('accepts a numeric response for the equivalent route id and preserves the update DTO', async () => {
    const body = { id: 42, title: '更新', contentMd: '正文', extra: true }
    client.patch.mockResolvedValue({ data: body })
    await expect(updateThread('00042', { title: '更新' })).resolves.toEqual(body)
  })

  it.each([
    { name: 'null', body: null },
    { name: 'undefined', body: undefined },
    { name: 'HTML', body: '<html>error</html>' },
    { name: 'array', body: [{ id: 42, title: '标题' }] },
    { name: 'missing id', body: { title: '标题' } },
    { name: 'missing title', body: { id: 42 } },
    { name: 'non-string title', body: { id: 42, title: {} } },
    { name: 'zero id', body: { id: 0, title: '标题' } },
    { name: 'unsafe number id', body: { id: Number.MAX_SAFE_INTEGER + 1, title: '标题' } },
  ])('rejects $name instead of acknowledging a write', async ({ body }) => {
    client.post.mockResolvedValue({ data: body })
    client.patch.mockResolvedValue({ data: body })
    await expect(createThread(payload)).rejects.toMatchObject({ code: 'THREAD_WRITE_UNCONFIRMED' })
    await expect(updateThread(42, payload)).rejects.toMatchObject({ code: 'THREAD_WRITE_UNCONFIRMED' })
  })

  it('rejects an otherwise valid update for another target', async () => {
    client.patch.mockResolvedValue({ data: { id: 99, title: '别帖' } })
    await expect(updateThread(42, payload)).rejects.toMatchObject({ code: 'THREAD_WRITE_UNCONFIRMED' })
  })

  it('propagates ordinary HTTP failures without retrying or relabeling them', async () => {
    const failure = { isAxiosError: true, response: { status: 403, data: { message: '无权限' } } }
    client.post.mockRejectedValue(failure)
    client.patch.mockRejectedValue(failure)
    await expect(createThread(payload)).rejects.toBe(failure)
    await expect(updateThread(42, payload)).rejects.toBe(failure)
    expect(client.post).toHaveBeenCalledOnce()
    expect(client.patch).toHaveBeenCalledOnce()
  })

  it.each([
    { name: 'lost response', error: { isAxiosError: true, code: 'ECONNABORTED', message: 'timeout' } },
    { name: 'server/proxy failure', error: { isAxiosError: true, response: { status: 503 }, message: 'upstream' } },
  ])('does not claim a failed write for $name or automatically resend it', async ({ error }) => {
    client.post.mockRejectedValue(error)
    client.patch.mockRejectedValue(error)
    await expect(createThread(payload)).rejects.toMatchObject({ code: 'THREAD_WRITE_UNCONFIRMED', cause: error })
    await expect(updateThread(42, payload)).rejects.toMatchObject({ code: 'THREAD_WRITE_UNCONFIRMED', cause: error })
    expect(client.post).toHaveBeenCalledOnce()
    expect(client.patch).toHaveBeenCalledOnce()
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPost } from '../posts'

const client = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('../client', () => ({ default: client }))
beforeEach(() => { vi.resetAllMocks() })
const payload = { contentMd: '评论', replyToPostId: null }

describe('comment creation confirmation', () => {
  it('preserves valid DTO metadata and compares decimal thread and reply ids without precision loss', async () => {
    const threadId = '9007199254740993123'
    const parentId = '9007199254740993124'
    const body = { id: '9007199254740993125', threadId, replyToPostId: parentId, content: '评论', extra: { kept: true } }
    client.post.mockResolvedValue({ data: body })
    await expect(createPost('00' + threadId, { contentMd: '评论', replyToPostId: '00' + parentId })).resolves.toBe(body)
    expect(client.post).toHaveBeenCalledOnce()
  })

  it('accepts the existing contentMd alias and an omitted top-level parent', async () => {
    const body = { id: 17, threadId: 42, contentMd: '评论' }
    client.post.mockResolvedValue({ data: body })
    await expect(createPost(42, payload)).resolves.toBe(body)
  })

  it.each([
    { name: 'null', body: null },
    { name: 'HTML', body: '<html>error</html>' },
    { name: 'array', body: [{ id: 17, threadId: 42, content: '评论' }] },
    { name: 'missing id', body: { threadId: 42, content: '评论' } },
    { name: 'unsafe numeric id', body: { id: Number.MAX_SAFE_INTEGER + 1, threadId: 42, content: '评论' } },
    { name: 'wrong thread', body: { id: 17, threadId: 99, content: '评论' } },
    { name: 'neighboring large thread', body: { id: 17, threadId: '9007199254740993124', content: '评论' }, threadId: '9007199254740993123' },
    { name: 'missing text', body: { id: 17, threadId: 42 } },
    { name: 'object text', body: { id: 17, threadId: 42, content: {} } },
    { name: 'unexpected parent', body: { id: 17, threadId: 42, content: '评论', replyToPostId: 7 } },
  ])('does not confirm $name', async ({ body, threadId }) => {
    client.post.mockResolvedValue({ data: body })
    await expect(createPost(threadId ?? 42, payload)).rejects.toMatchObject({ code: 'POST_CREATE_UNCONFIRMED' })
    expect(client.post).toHaveBeenCalledOnce()
  })

  it('requires confirmation of the requested reply target', async () => {
    client.post.mockResolvedValue({ data: { id: 17, threadId: 42, content: '评论' } })
    await expect(createPost(42, { contentMd: '评论', replyToPostId: 7 })).rejects.toMatchObject({ code: 'POST_CREATE_UNCONFIRMED' })
    client.post.mockResolvedValue({ data: { id: 17, threadId: 42, content: '评论', replyToPostId: 8 } })
    await expect(createPost(42, { contentMd: '评论', replyToPostId: 7 })).rejects.toMatchObject({ code: 'POST_CREATE_UNCONFIRMED' })
  })

  it.each([
    { name: 'lost response', error: { isAxiosError: true, code: 'ECONNABORTED' } },
    { name: 'server error', error: { isAxiosError: true, response: { status: 503 } } },
  ])('marks $name as unconfirmed without resending', async ({ error }) => {
    client.post.mockRejectedValue(error)
    await expect(createPost(42, payload)).rejects.toMatchObject({ code: 'POST_CREATE_UNCONFIRMED', cause: error })
    expect(client.post).toHaveBeenCalledOnce()
  })

  it('preserves a definite ordinary HTTP rejection', async () => {
    const error = { isAxiosError: true, response: { status: 403, data: { message: '无权限' } } }
    client.post.mockRejectedValue(error)
    await expect(createPost(42, payload)).rejects.toBe(error)
  })

  it('checks the dispatched reply target even if the caller later mutates its payload', async () => {
    let finish!: (response: unknown) => void
    client.post.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const request = { contentMd: '评论', replyToPostId: 7 }
    const pending = createPost(42, request)
    request.replyToPostId = 8
    const body = { id: 17, threadId: 42, replyToPostId: 7, content: '评论' }
    finish({ data: body })
    await expect(pending).resolves.toBe(body)
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import {
  bookmarkThread,
  getThreadBookmarkState,
  unbookmarkThread,
} from '../bookmarks'
import { getPostLikeState, likePost, unlikePost } from '../posts'
import { getThreadLikeState, likeThread, unlikeThread } from '../threads'
import { followUser, getUserFollowStatus, unfollowUser } from '../users'
import { InteractionResponseError } from '../interactionState'

vi.mock('../client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

const mockedApi = vi.mocked(api)

describe('interaction response contracts', () => {
  beforeEach(() => {
    mockedApi.get.mockReset()
    mockedApi.post.mockReset()
    mockedApi.delete.mockReset()
  })

  it('decodes valid like, bookmark, and follow responses through every HTTP variant', async () => {
    mockedApi.get
      .mockResolvedValueOnce({ data: { postId: '7', liked: false, likeCount: 0 } })
      .mockResolvedValueOnce({ data: { threadId: 8, liked: true, likeCount: 1 } })
      .mockResolvedValueOnce({ data: { bookmarked: false } })
      .mockResolvedValueOnce({
        data: {
          userId: '9007199254740993',
          following: false,
          followingMe: true,
          followerCount: 0,
          followingCount: 2,
        },
      })
    mockedApi.post
      .mockResolvedValueOnce({ data: { postId: 7, liked: true, likeCount: 2 } })
      .mockResolvedValueOnce({ data: { threadId: '8', liked: false, likeCount: 0 } })
      .mockResolvedValueOnce({ data: { bookmarked: true } })
      .mockResolvedValueOnce({
        data: {
          userId: 9,
          followedByMe: true,
          followingMe: false,
          followerCount: 3,
          followingCount: 4,
        },
      })
    mockedApi.delete
      .mockResolvedValueOnce({ data: { postId: '7', liked: false, likeCount: 0 } })
      .mockResolvedValueOnce({ data: { threadId: 8, liked: false, likeCount: 0 } })
      .mockResolvedValueOnce({ data: { bookmarked: false } })
      .mockResolvedValueOnce({
        data: {
          userId: '9',
          following: false,
          followedByMe: false,
          followingMe: false,
          followerCount: 0,
          followingCount: 0,
        },
      })

    await expect(getPostLikeState(7)).resolves.toMatchObject({ postId: '7', liked: false, likeCount: 0 })
    await expect(getThreadLikeState(8)).resolves.toMatchObject({ threadId: 8, liked: true, likeCount: 1 })
    await expect(getThreadBookmarkState(9)).resolves.toEqual({ bookmarked: false })
    await expect(getUserFollowStatus('9007199254740993')).resolves.toMatchObject({
      userId: '9007199254740993',
      following: false,
      followedByMe: false,
      followingMe: true,
      followerCount: 0,
      followingCount: 2,
    })

    await expect(likePost(7)).resolves.toMatchObject({ postId: 7, liked: true, likeCount: 2 })
    await expect(likeThread(8)).resolves.toMatchObject({ threadId: '8', liked: false, likeCount: 0 })
    await expect(bookmarkThread(9)).resolves.toEqual({ bookmarked: true })
    await expect(followUser(9)).resolves.toMatchObject({
      userId: 9,
      following: true,
      followedByMe: true,
      followingMe: false,
      followerCount: 3,
      followingCount: 4,
    })

    await expect(unlikePost(7)).resolves.toMatchObject({ postId: '7', liked: false, likeCount: 0 })
    await expect(unlikeThread(8)).resolves.toMatchObject({ threadId: 8, liked: false, likeCount: 0 })
    await expect(unbookmarkThread(9)).resolves.toEqual({ bookmarked: false })
    await expect(unfollowUser(9)).resolves.toMatchObject({
      userId: '9',
      following: false,
      followedByMe: false,
      followingMe: false,
      followerCount: 0,
      followingCount: 0,
    })

    expect(mockedApi.get).toHaveBeenNthCalledWith(1, '/posts/7/like')
    expect(mockedApi.get).toHaveBeenNthCalledWith(2, '/threads/8/like')
    expect(mockedApi.get).toHaveBeenNthCalledWith(3, '/threads/9/bookmark')
    expect(mockedApi.get).toHaveBeenNthCalledWith(4, '/users/9007199254740993/follow-status')
    expect(mockedApi.post).toHaveBeenNthCalledWith(1, '/posts/7/like')
    expect(mockedApi.post).toHaveBeenNthCalledWith(2, '/threads/8/like')
    expect(mockedApi.post).toHaveBeenNthCalledWith(3, '/threads/9/bookmark')
    expect(mockedApi.post).toHaveBeenNthCalledWith(4, '/users/9/follow')
    expect(mockedApi.delete).toHaveBeenNthCalledWith(1, '/posts/7/like')
    expect(mockedApi.delete).toHaveBeenNthCalledWith(2, '/threads/8/like')
    expect(mockedApi.delete).toHaveBeenNthCalledWith(3, '/threads/9/bookmark')
    expect(mockedApi.delete).toHaveBeenNthCalledWith(4, '/users/9/follow')
  })

  it.each([
    ['post like', getPostLikeState, 'get', 7, { postId: 8, liked: true, likeCount: 1 }],
    ['thread like', getThreadLikeState, 'get', 8, { threadId: 9, liked: true, likeCount: 1 }],
    ['post like boolean', likePost, 'post', 7, { postId: 7, liked: 'true', likeCount: 1 }],
    ['thread like boolean', likeThread, 'post', 8, { threadId: 8, liked: false, likeCount: '1' }],
    ['post unlike', unlikePost, 'delete', 7, { postId: null, liked: false, likeCount: 0 }],
    ['thread unlike', unlikeThread, 'delete', 8, { threadId: [], liked: false, likeCount: 0 }],
  ] as const)('rejects malformed or mismatched %s responses', async (_label, request, method, id, data) => {
    vi.mocked(api[method]).mockResolvedValueOnce({ data })

    await expect(request(id)).rejects.toBeInstanceOf(InteractionResponseError)
    expect(mockedApi[method]).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['null', null],
    ['array', []],
  ] as const)('rejects a %s response root', async (_label, data) => {
    mockedApi.get.mockResolvedValueOnce({ data })

    await expect(getPostLikeState(7)).rejects.toBeInstanceOf(InteractionResponseError)
    expect(mockedApi.get).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['bookmark get', getThreadBookmarkState, 'get'],
    ['bookmark add', bookmarkThread, 'post'],
    ['bookmark remove', unbookmarkThread, 'delete'],
  ] as const)('rejects a non-boolean %s response', async (_label, request, method) => {
    vi.mocked(api[method]).mockResolvedValueOnce({ data: { bookmarked: 'false' } })

    await expect(request(9)).rejects.toBeInstanceOf(InteractionResponseError)
    expect(mockedApi[method]).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['missing id', { liked: true, likeCount: 1 }],
    ['missing liked', { postId: 7, likeCount: 1 }],
    ['null liked', { postId: 7, liked: null, likeCount: 1 }],
    ['array liked', { postId: 7, liked: [], likeCount: 1 }],
    ['missing count', { postId: 7, liked: true }],
    ['negative count', { postId: 7, liked: true, likeCount: -1 }],
    ['NaN count', { postId: 7, liked: true, likeCount: Number.NaN }],
    ['infinite count', { postId: 7, liked: true, likeCount: Number.POSITIVE_INFINITY }],
    ['fractional count', { postId: 7, liked: true, likeCount: 1.5 }],
    ['unsafe count', { postId: 7, liked: true, likeCount: Number.MAX_SAFE_INTEGER + 1 }],
    ['string count', { postId: 7, liked: true, likeCount: '1' }],
    ['unsafe numeric id', { postId: Number.MAX_SAFE_INTEGER + 1, liked: true, likeCount: 1 }],
    ['noncanonical string id', { postId: '07', liked: true, likeCount: 1 }],
  ] as const)('rejects %s like payloads', async (_label, data) => {
    mockedApi.get.mockResolvedValueOnce({ data })

    await expect(getPostLikeState(7)).rejects.toBeInstanceOf(InteractionResponseError)
  })

  it.each([
    ['missing relationship', { userId: 9, followingMe: false, followerCount: 0, followingCount: 0 }],
    ['non-boolean relationship', { userId: 9, following: 1, followingMe: false, followerCount: 0, followingCount: 0 }],
    ['non-boolean alias', { userId: 9, following: false, followedByMe: 'false', followingMe: false, followerCount: 0, followingCount: 0 }],
    ['conflicting aliases', { userId: 9, following: true, followedByMe: false, followingMe: false, followerCount: 0, followingCount: 0 }],
    ['missing followingMe', { userId: 9, following: false, followerCount: 0, followingCount: 0 }],
    ['non-boolean followingMe', { userId: 9, following: false, followingMe: 'false', followerCount: 0, followingCount: 0 }],
    ['negative follower count', { userId: 9, following: false, followingMe: false, followerCount: -1, followingCount: 0 }],
    ['unsafe following count', { userId: 9, following: false, followingMe: false, followerCount: 0, followingCount: Number.MAX_SAFE_INTEGER + 1 }],
    ['wrong target', { userId: 10, following: false, followingMe: false, followerCount: 0, followingCount: 0 }],
  ] as const)('rejects %s follow payloads for every follow verb', async (_label, data) => {
    mockedApi.get.mockResolvedValueOnce({ data })
    await expect(getUserFollowStatus(9)).rejects.toBeInstanceOf(InteractionResponseError)

    mockedApi.post.mockResolvedValueOnce({ data })
    await expect(followUser(9)).rejects.toBeInstanceOf(InteractionResponseError)

    mockedApi.delete.mockResolvedValueOnce({ data })
    await expect(unfollowUser(9)).rejects.toBeInstanceOf(InteractionResponseError)
  })

  it('rejects distinct large decimal IDs without numeric precision loss', async () => {
    mockedApi.get.mockResolvedValueOnce({
      data: { postId: '9007199254740992', liked: true, likeCount: 1 },
    })
    await expect(getPostLikeState('9007199254740993')).rejects.toBeInstanceOf(InteractionResponseError)

    mockedApi.get.mockResolvedValueOnce({
      data: { threadId: '9007199254740993', liked: false, likeCount: 0 },
    })
    await expect(getThreadLikeState('9007199254740992')).rejects.toBeInstanceOf(InteractionResponseError)

    mockedApi.get.mockResolvedValueOnce({
      data: {
        userId: '9007199254740992',
        following: false,
        followingMe: false,
        followerCount: 0,
        followingCount: 0,
      },
    })
    await expect(getUserFollowStatus('9007199254740993')).rejects.toBeInstanceOf(InteractionResponseError)
    expect(mockedApi.get).toHaveBeenCalledTimes(3)
  })

  it('propagates transport errors unchanged and does not retry', async () => {
    const transportError = new Error('network failed')
    mockedApi.get.mockRejectedValueOnce(transportError)

    await expect(getPostLikeState(7)).rejects.toBe(transportError)
    expect(mockedApi.get).toHaveBeenCalledTimes(1)
  })

  it.each([
    ['POST', 'post', likePost],
    ['DELETE', 'delete', unlikePost],
  ] as const)('propagates %s transport errors unchanged and does not retry', async (_label, method, request) => {
    const transportError = new Error(`${method} failed`)
    mockedApi[method].mockRejectedValueOnce(transportError)

    await expect(request(7)).rejects.toBe(transportError)
    expect(mockedApi[method]).toHaveBeenCalledTimes(1)
  })

  it('does not mutate a valid response object', async () => {
    const payload = {
      postId: 7,
      liked: true,
      likeCount: 1,
      auditTag: 'keep-me',
    }
    const before = { ...payload }
    mockedApi.get.mockResolvedValueOnce({ data: payload })

    await expect(getPostLikeState(7)).resolves.toMatchObject(payload)
    expect(payload).toEqual(before)
  })
})

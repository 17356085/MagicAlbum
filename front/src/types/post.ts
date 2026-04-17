import type { Id } from './common'

export interface Post {
  id: Id
  threadId: Id
  content?: string
  contentMd?: string
  contentHtml?: string
  authorId?: Id
  authorUsername?: string
  authorNickname?: string
  authorAvatarUrl?: string
  replyToPostId?: Id | null
  parentAuthorId?: Id
  parentAuthorUsername?: string
  parentAuthorNickname?: string
  createdAt?: string
  updatedAt?: string
}

export interface CreatePostPayload {
  contentMd: string
  replyToPostId?: Id | null
}

export interface UpdatePostPayload {
  contentMd?: string
}

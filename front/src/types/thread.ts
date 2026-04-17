import type { Id } from './common'

export interface Thread {
  id: Id
  title: string
  content?: string
  contentMd?: string
  contentHtml?: string
  summary?: string
  authorId?: Id
  authorUsername?: string
  authorNickname?: string
  authorAvatarUrl?: string
  sectionId?: Id
  sectionName?: string
  createdAt?: string
  updatedAt?: string
  replyCount?: number
  viewCount?: number
  likeCount?: number
}

export interface CreateThreadPayload {
  sectionId: Id
  title: string
  content: string
}

export interface UpdateThreadPayload {
  title?: string
  content?: string
  sectionId?: Id
}

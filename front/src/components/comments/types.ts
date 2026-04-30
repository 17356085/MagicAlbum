import type { Id, Post } from '@/types'

export interface CommentsProps {
  threadId: number
  autoCollapseCountThreshold?: number
  autoCollapseWidthThreshold?: number
  autoCollapseHeightThreshold?: number
  childPageSize?: number
  scrollToPostId?: number | null
}

export type SortKey = 'time' | 'replies' | 'likes'
export type SortOrder = 'asc' | 'desc'

export interface CommentItem extends Post {
  children?: CommentItem[]
  parentAuthorUsername?: string | null
  parentAuthorId?: Id | null
  parentAuthorNickname?: string | null
  floorLabel?: string
  depth?: number
  _optimistic?: boolean
}

export interface CommentGroup {
  root: CommentItem
  items: CommentItem[]
}

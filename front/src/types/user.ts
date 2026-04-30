import type { AuthVerifyPayload } from './auth'
import type { Id } from './common'

export interface UserLink {
  label?: string
  title?: string
  name?: string
  url: string
}

export interface ProfileUpdatedDetail {
  avatarUrl?: string
  nickname?: string
  userId?: Id
  id?: Id
  username?: string
}

export interface User {
  id: Id
  userId?: Id
  username: string
  nickname?: string
  email?: string
  phone?: string
  avatarUrl?: string
  bio?: string
  homepageUrl?: string
  location?: string
  links?: UserLink[]
  createdAt?: string
  updatedAt?: string
}

export interface UserProfile {
  id?: Id
  userId?: Id
  username?: string
  nickname: string
  bio: string
  homepageUrl: string
  location: string
  links: Array<UserLink | string>
  avatarUrl: string
  followerCount?: number
  followingCount?: number
  followingMe?: boolean
  followedByMe?: boolean
}

export interface UserFollowStatus {
  userId: Id
  following: boolean
  followedByMe?: boolean
  followingMe?: boolean
  followerCount: number
  followingCount: number
}

export interface UserSettings {
  [key: string]: unknown
}

export interface RegisterUserPayload extends AuthVerifyPayload {
  username: string
  password: string
  email?: string
  phone?: string
}

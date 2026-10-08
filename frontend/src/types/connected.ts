import type { Id } from './common'

export interface ConnectedAccount {
  id?: Id
  provider: string
  connected?: boolean
  providerUserId?: string
  username?: string
  nickname?: string
  avatarUrl?: string
  connectedAt?: string
}

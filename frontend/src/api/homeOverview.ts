import api from './client'
import { getStoredAccessToken } from '@/utils/authStorage'
import type { User, UserProfile } from '@/types'

export const homeOverviewEnabled = import.meta.env.VITE_HOME_BFF_ENABLED === 'true'
  && import.meta.env.VITE_USE_API_MOCK !== 'true'

interface HomeOverview {
  threads?: unknown
  sections?: unknown
  profile?: (UserProfile & Partial<User>) | null
  following?: unknown
  unreadNotifications?: number | null
}

interface Request {
  token: string
  expiresAt: number
  response: Promise<HomeOverview | null>
}

let recent: Request | undefined

/** Coalesces initial page requests within the same browser session. */
export function getHomeOverview(): Promise<HomeOverview | null> {
  const token = getStoredAccessToken()
  if (recent?.token === token && recent.expiresAt > Date.now()) return recent.response

  const request: Request = {
    token,
    expiresAt: Date.now() + 2_000,
    response: api.get<HomeOverview>('/home/overview').then(({ data }) => {
      return getStoredAccessToken() === token ? data : null
    }).catch(() => null),
  }
  recent = request
  void request.response.then(result => {
    if (result === null && recent === request) recent = undefined
  })
  return request.response
}

import type { Id } from './common'

export interface RecentVisit {
  path: string
  name?: string
  title?: string
  id?: Id
  sectionId?: Id
  sectionName?: string
  ts: number
}

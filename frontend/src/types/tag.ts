import type { Id } from './common'

export interface TagStats {
  id: Id
  name: string
  type?: string
  threadCount: number
}

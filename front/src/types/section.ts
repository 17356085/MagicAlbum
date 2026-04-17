import type { Id } from './common'

export interface Section {
  id: Id
  name: string
  slug?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

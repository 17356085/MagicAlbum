export type Id = string | number

export type Nullable<T> = T | null

export interface PageResult<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export interface ApiErrorPayload {
  message?: string
  code?: string | number
  details?: unknown
  traceId?: string
}

export interface ApiError extends Error {
  response?: {
    status?: number
    data?: ApiErrorPayload
  }
}

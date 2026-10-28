import type { Id } from './common'

export interface PasswordUpdatePayload {
  currentPassword: string
  newPassword: string
}

export interface BasicInfoPayload {
  username: string
  phone: string
  email: string
}

/** Canonical response returned by GET/PATCH /users/me/basic. */
export interface BasicInfoResponse extends BasicInfoPayload {
  id: Id
}

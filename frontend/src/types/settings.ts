export interface PasswordUpdatePayload {
  currentPassword: string
  newPassword: string
}

export interface BasicInfoPayload {
  username: string
  phone?: string
  email?: string
}

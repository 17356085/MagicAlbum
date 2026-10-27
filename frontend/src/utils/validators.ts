// 通用表单校验工具：用户名/手机号/邮箱/密码强度

export function isValidUsername(username: string | null | undefined): boolean {
  const u = String(username || '').trim()
  if (!u) return false
  // 基础资料接口与 users.username 列支持 3-64 位，保留表单的字符规则。
  return /^[A-Za-z0-9_]{3,64}$/.test(u)
}

export function isValidPhone(phone: string | null | undefined): boolean {
  const p = String(phone || '').trim()
  if (!p) return false
  // 与后端保持一致：仅允许大陆手机号，1 开头的 11 位数字
  return /^1\d{10}$/.test(p)
}

export function isValidEmail(email: string | null | undefined): boolean {
  const e = String(email || '').trim()
  if (!e) return false
  // 常用邮箱格式校验（避免过于严格导致合法地址失败）
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)
}

export function isStrongPassword(pwd: string | null | undefined): boolean {
  return getPasswordError(pwd) === ''
}

export function getPasswordError(pwd: string | null | undefined): string {
  const p = String(pwd || '')
  if (!p) return '请输入密码'
  if (p.length < 8) return '密码不少于 8 位'
  // New passwords must fit the configured BCrypt encoder. Do not use this
  // rule for login or current-password verification of existing credentials.
  if (new TextEncoder().encode(p).length > 72) return '密码过长，请减少字符数量'
  if (!/[a-z]/.test(p)) return '需包含小写字母'
  if (!/[A-Z]/.test(p)) return '需包含大写字母'
  if (!/\d/.test(p)) return '需包含数字'
  return ''
}

export function passwordStrength(pwd: string | null | undefined): number {
  const p = String(pwd || '')
  let score = 0
  if (p.length >= 8) score++
  if (/[a-z]/.test(p)) score++
  if (/[A-Z]/.test(p)) score++
  if (/\d/.test(p)) score++
  if (/[^A-Za-z0-9]/.test(p)) score++
  return Math.min(score, 5)
}

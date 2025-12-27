// 通用表单校验工具：用户名/手机号/邮箱/密码强度

export function isValidUsername(username) {
  const u = String(username || '').trim()
  if (!u) return false
  // 3-20 位，字母数字下划线
  return /^[A-Za-z0-9_]{3,20}$/.test(u)
}

export function isValidPhone(phone) {
  const p = String(phone || '').trim()
  if (!p) return false
  // 与后端保持一致：仅允许大陆手机号，1 开头的 11 位数字
  return /^1\d{10}$/.test(p)
}

export function isValidEmail(email) {
  const e = String(email || '').trim()
  if (!e) return false
  // 常用邮箱格式校验（避免过于严格导致合法地址失败）
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)
}

export function isStrongPassword(pwd) {
  const p = String(pwd || '')
  if (p.length < 8) return false
  const hasLower = /[a-z]/.test(p)
  const hasUpper = /[A-Z]/.test(p)
  const hasDigit = /\d/.test(p)
  // 可选：包含特殊字符（不强制）
  // const hasSymbol = /[^A-Za-z0-9]/.test(p)
  return hasLower && hasUpper && hasDigit
}

export function getPasswordError(pwd) {
  const p = String(pwd || '')
  if (!p) return '请输入密码'
  if (p.length < 8) return '密码不少于 8 位'
  if (!/[a-z]/.test(p)) return '需包含小写字母'
  if (!/[A-Z]/.test(p)) return '需包含大写字母'
  if (!/\d/.test(p)) return '需包含数字'
  return ''
}

export function passwordStrength(pwd) {
  const p = String(pwd || '')
  let score = 0
  if (p.length >= 8) score++
  if (/[a-z]/.test(p)) score++
  if (/[A-Z]/.test(p)) score++
  if (/\d/.test(p)) score++
  if (/[^A-Za-z0-9]/.test(p)) score++
  return Math.min(score, 5)
}
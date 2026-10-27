import { describe, expect, it } from 'vitest'
import { getPasswordError, isStrongPassword } from '../validators'

describe('new password encoding limits', () => {
  it.each([
    `Aa1${'x'.repeat(69)}`,
    `Aa1${'汉'.repeat(23)}`,
    `Aa1x${'😀'.repeat(17)}`,
  ])('accepts a strong password occupying exactly 72 UTF-8 bytes', password => {
    expect(new TextEncoder().encode(password)).toHaveLength(72)
    expect(isStrongPassword(password)).toBe(true)
    expect(getPasswordError(password)).toBe('')
  })

  it.each([
    `Aa1${'x'.repeat(70)}`,
    `Aa1x${'汉'.repeat(23)}`,
    `Aa1${'😀'.repeat(18)}`,
  ])('rejects new passwords exceeding the encoder limit, including short multibyte input', password => {
    expect(new TextEncoder().encode(password).length).toBeGreaterThan(72)
    expect(isStrongPassword(password)).toBe(false)
    expect(getPasswordError(password)).toBe('密码过长，请减少字符数量')
  })

  it('continues to validate required input and complexity without trimming the password', () => {
    expect(getPasswordError('')).toBe('请输入密码')
    expect(getPasswordError('Aa1')).toBe('密码不少于 8 位')
    expect(getPasswordError('lowercase1')).toBe('需包含大写字母')
    expect(isStrongPassword(' Aa12345')).toBe(true)
  })
})

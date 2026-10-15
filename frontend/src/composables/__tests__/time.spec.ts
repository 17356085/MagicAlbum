import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatAbsoluteDate, formatAbsoluteTime, formatRelativeTime } from '../time'

afterEach(() => vi.useRealTimers())

describe('time formatting boundaries', () => {
  it('keeps missing and invalid timestamps empty instead of inventing a date', () => {
    for (const input of [null, undefined, '', 'not-a-date', Number.NaN]) {
      expect(formatRelativeTime(input)).toBe('')
      expect(formatAbsoluteTime(input)).toBe('')
      expect(formatAbsoluteDate(input)).toBe('')
    }
  })

  it('keeps timestamp zero valid', () => {
    expect(formatAbsoluteTime(0)).toContain('1970')
    expect(formatAbsoluteDate(0)).toContain('1970')
  })

  it('preserves relative labels for valid timestamps', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-14T12:00:00Z'))
    expect(formatRelativeTime('2026-10-14T11:30:00Z')).toBe('30分钟前')
    expect(formatRelativeTime('2026-10-14T12:00:30Z')).toBe('即将发生')
    expect(formatRelativeTime('2026-10-14T12:10:00Z')).toBe('10分钟后')
  })
})

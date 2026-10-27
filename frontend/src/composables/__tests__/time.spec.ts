import { afterEach, describe, expect, it, vi } from 'vitest'
import { formatAbsoluteDate, formatAbsoluteTime, formatRelativeTime } from '../time'

afterEach(() => vi.useRealTimers())

const FIXED_NOW = new Date('2026-10-14T12:00:00Z')
const DAY_MS = 24 * 60 * 60 * 1000

function dateOffsetByDays(days: number): string {
  return new Date(FIXED_NOW.getTime() + days * DAY_MS).toISOString()
}

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

  it('uses non-zero week, month, and year units at past and future day boundaries', () => {
    vi.useFakeTimers()
    vi.setSystemTime(FIXED_NOW)

    const boundaries = [
      { days: 27, unit: '3周' },
      { days: 28, unit: '4周' },
      { days: 29, unit: '4周' },
      { days: 30, unit: '1个月' },
      { days: 359, unit: '11个月' },
      { days: 360, unit: '12个月' },
      { days: 364, unit: '12个月' },
      { days: 365, unit: '1年' },
    ]

    for (const direction of [-1, 1]) {
      const suffix = direction < 0 ? '前' : '后'
      for (const { days, unit } of boundaries) {
        expect(formatRelativeTime(dateOffsetByDays(direction * days))).toBe(`${unit}${suffix}`)
      }
    }
  })
})

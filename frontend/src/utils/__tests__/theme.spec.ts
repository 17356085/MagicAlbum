import { afterEach, describe, expect, it, vi } from 'vitest'
import { initializeTheme, persistTheme } from '../theme'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.documentElement.classList.remove('dark')
  localStorage.clear()
})

describe('theme preference storage', () => {
  it('uses the system preference when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError') })
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
    expect(initializeTheme()).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('still switches the page when saving the preference fails', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Blocked', 'SecurityError') })
    expect(() => persistTheme(true)).not.toThrow()
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    persistTheme(false)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('keeps an explicit preference ahead of the system theme', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })))
    localStorage.setItem('theme', 'light')
    expect(initializeTheme()).toBe(false)
    persistTheme(true)
    expect(localStorage.getItem('theme')).toBe('dark')
  })
})

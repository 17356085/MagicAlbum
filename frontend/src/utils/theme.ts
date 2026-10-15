function savedTheme(): string | null {
  try {
    return localStorage.getItem('theme')
  } catch {
    // Browser storage may be blocked; the system preference remains usable.
    return null
  }
}

export function applyThemeClass(dark: boolean): void {
  document.documentElement.classList.toggle('dark', dark)
}

export function initializeTheme(): boolean {
  const saved = savedTheme()
  const dark = saved === 'dark' || (saved !== 'light' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches)
  applyThemeClass(dark)
  return dark
}

export function persistTheme(dark: boolean): void {
  applyThemeClass(dark)
  try {
    localStorage.setItem('theme', dark ? 'dark' : 'light')
  } catch {
    // An unavailable preference store must not prevent switching the current page.
  }
}

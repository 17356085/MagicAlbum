import type { LocationQueryValue, Router, RouteLocationRaw } from 'vue-router'

export function getSingleQueryValue(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

export function safeBack(router: Router, fallback: RouteLocationRaw = { name: 'discover' }): void {
  const ref = document.referrer || ''
  const sameOrigin = ref && ref.startsWith(location.origin)
  if (!sameOrigin || window.history.length <= 1) {
    router.replace(fallback)
  } else {
    router.back()
  }
}

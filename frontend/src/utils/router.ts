import type { LocationQueryValue, Router, RouteLocationRaw } from 'vue-router'

export function getSingleQueryValue(value: LocationQueryValue | LocationQueryValue[] | undefined): string {
  return Array.isArray(value) ? String(value[0] ?? '') : String(value ?? '')
}

export function safeBack(router: Router, fallback: RouteLocationRaw = { name: 'discover' }): void {
  const back = router.options.history.state?.back
  const isInternalPath = typeof back === 'string' && back.startsWith('/') && !back.startsWith('//')
  if (isInternalPath) {
    router.back()
  } else {
    router.replace(fallback)
  }
}

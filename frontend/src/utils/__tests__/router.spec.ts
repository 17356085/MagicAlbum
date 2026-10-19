import type { Router } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { safeBack } from '../router'

function routerWithBack(back: unknown) {
  return {
    options: { history: { state: { back } } },
    back: vi.fn(),
    replace: vi.fn(),
  } as unknown as Router & { back: ReturnType<typeof vi.fn>; replace: ReturnType<typeof vi.fn> }
}

describe('safeBack', () => {
  it('uses Vue Router history for an internal previous route', () => {
    const router = routerWithBack('/discover?sectionId=1&page=2')

    safeBack(router)

    expect(router.back).toHaveBeenCalledTimes(1)
    expect(router.replace).not.toHaveBeenCalled()
  })

  it.each([null, '', 'https://external.example/path', '//external.example/path'])('falls back when history.back is not an internal path: %s', (back) => {
    const router = routerWithBack(back)

    safeBack(router)

    expect(router.back).not.toHaveBeenCalled()
    expect(router.replace).toHaveBeenCalledWith({ name: 'discover' })
  })
})

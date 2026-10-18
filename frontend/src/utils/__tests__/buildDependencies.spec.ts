import { createRequire } from 'node:module'
import { describe, expect, it } from 'vitest'

const require = createRequire(import.meta.url)
const tailwindRequire = createRequire(require.resolve('tailwindcss'))
const micromatchRequire = createRequire(tailwindRequire.resolve('micromatch'))
const braces = micromatchRequire('braces') as {
  compile: (pattern: string) => string
  expand: (pattern: string) => string[]
}

describe('patched build dependency boundaries', () => {
  it('preserves ordinary file globs and alternatives', () => {
    expect(braces.expand('src/**/*.{vue,ts}')).toEqual(['src/**/*.vue', 'src/**/*.ts'])
    const expression = new RegExp(`^${braces.compile('a/{b,c}/d')}$`)
    expect(expression.test('a/b/d')).toBe(true)
    expect(expression.test('a/c/d')).toBe(true)
    expect(expression.test('a/x/d')).toBe(false)
  })

  it.each(['{', '('])('rejects deeply nested %s before recursive walkers exhaust the stack', (open) => {
    const close = open === '{' ? '}' : ')'
    const pattern = open.repeat(4000) + 'x' + close.repeat(4000)
    for (const operation of [braces.compile, braces.expand]) {
      expect(() => operation(pattern)).toThrow(new SyntaxError('Input nesting exceeds maximum depth of 100'))
    }
  })
})

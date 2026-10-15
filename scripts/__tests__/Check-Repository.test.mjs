import { strict as assert } from 'node:assert'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { checkRepository, resolvesLocalImport } from '../Check-Repository.mjs'

function write(root, path, text = '') {
  mkdirSync(dirname(join(root, path)), { recursive: true })
  writeFileSync(join(root, path), text)
}

test('detects package mismatch, moved imports and broken links', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-structure-'))
  try {
    write(root, 'backend/src/main/java/example/Thing.java', 'package wrong; class Thing {}')
    write(root, 'frontend/src/App.vue', "import Header from '@/components/Missing.vue'")
    write(root, 'README.md', '[handbook](docs/missing.md)')
    write(root, 'threads.json', '{}')
    const result = checkRepository(root)
    assert.ok(result.errors.some(error => error.includes('Java package does not match')))
    assert.ok(result.errors.some(error => error.includes('Unresolved local import')))
    assert.ok(result.errors.some(error => error.includes('Broken document link')))
    assert.ok(result.errors.some(error => error.includes('Loose generated')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('resolves aliases, extensionless indexes and URL asset imports', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-imports-'))
  try {
    write(root, 'frontend/src/components/AppHeader.vue')
    write(root, 'frontend/src/router/index.ts')
    write(root, 'frontend/src/assets/theme.css')
    const source = join(root, 'frontend/src/App.vue')
    assert.equal(resolvesLocalImport(root, source, '@/components/AppHeader.vue'), true)
    assert.equal(resolvesLocalImport(root, source, './router'), true)
    assert.equal(resolvesLocalImport(root, source, './assets/theme.css?url'), true)
    assert.equal(resolvesLocalImport(root, source, './missing'), false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, extname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const sourceExtensions = new Set(['.ts', '.tsx', '.js', '.vue'])
const skippedDirectories = new Set(['node_modules', 'target', 'dist', 'build', '.git', 'coverage', '__pycache__', '.pytest_cache'])
const moduleNames = ['backend', 'gateway', 'notification-service']
const requiredEntries = [
  'README.md', 'CONTRIBUTING.md', 'PRODUCT.md', '.editorconfig', 'pom.xml',
  'frontend/package.json', 'frontend/tsconfig.json', 'frontend/src/App.vue',
  ...moduleNames.map(module => `${module}/pom.xml`),
  'scripts/database/run-mysql.cmd',
  'config/checkstyle.xml',
]

function walk(directory) {
  if (!existsSync(directory)) return []
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) return skippedDirectories.has(entry.name) ? [] : walk(path)
    return entry.isFile() ? [path] : []
  })
}

function hasFile(path) {
  return existsSync(path) && statSync(path).isFile()
}

export function resolvesLocalImport(root, sourceFile, specifier) {
  if (!specifier.startsWith('@/') && !specifier.startsWith('.')) return true
  const cleaned = specifier.split('?')[0]
  const base = cleaned.startsWith('@/')
    ? resolve(root, 'frontend/src', cleaned.slice(2))
    : resolve(dirname(sourceFile), cleaned)
  return [base, ...['.ts', '.tsx', '.js', '.vue', '.json'].map(extension => base + extension),
    ...['index.ts', 'index.tsx', 'index.js', 'index.vue'].map(name => join(base, name))].some(hasFile)
}

export function checkRepository(root) {
  root = resolve(root)
  const errors = []
  const counts = { java: 0, frontend: 0, imports: 0, documentLinks: 0 }
  for (const entry of requiredEntries) {
    if (!hasFile(join(root, entry))) errors.push(`Missing required entry: ${entry}`)
  }

  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.isFile() && /(?:\.log$|\.docx$|^hs_err_pid|^replay_pid|^output\.txt$|^project_tree\.txt$|^threads\.json$)/i.test(entry.name)) {
      errors.push(`Loose generated or historical file at root: ${entry.name}`)
    }
  }

  for (const module of moduleNames) {
    for (const scope of ['main', 'test']) {
      const javaRoot = join(root, module, 'src', scope, 'java')
      for (const file of walk(javaRoot).filter(path => extname(path) === '.java')) {
        counts.java += 1
        const expected = relative(javaRoot, dirname(file)).split(/[\\/]/).join('.')
        const actual = readFileSync(file, 'utf8').match(/^\s*package\s+([\w.]+)\s*;/m)?.[1]
        if (actual !== expected) errors.push(`Java package does not match directory: ${relative(root, file)} (${actual ?? 'missing'} vs ${expected})`)
      }
    }
  }

  const frontendFiles = [...walk(join(root, 'frontend/src')),
    ...['vite.config.ts', 'vitest.config.ts'].map(name => join(root, 'frontend', name))]
    .filter(path => hasFile(path) && sourceExtensions.has(extname(path)))
  for (const file of frontendFiles) {
    counts.frontend += 1
    const text = readFileSync(file, 'utf8')
    // Static imports/re-exports and literal dynamic imports. Computed paths require build checks.
    const patterns = [
      /\b(?:import|export)\s+(?:type\s+)?(?:[^'";]*?\s+from\s*)?['"]([^'"]+)['"]/g,
      /\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)/g,
    ]
    for (const pattern of patterns) {
      for (const match of text.matchAll(pattern)) {
        if (!match[1].startsWith('@/') && !match[1].startsWith('.')) continue
        counts.imports += 1
        if (!resolvesLocalImport(root, file, match[1])) errors.push(`Unresolved local import: ${relative(root, file)} -> ${match[1]}`)
      }
    }
  }

  const documents = [join(root, 'README.md'), join(root, 'CONTRIBUTING.md'),
    ...walk(join(root, 'docs/engineering')).filter(path => extname(path) === '.md')]
  for (const document of documents.filter(hasFile)) {
    const text = readFileSync(document, 'utf8')
    for (const match of text.matchAll(/\[[^\]]*\]\(([^\s)]+)\)/g)) {
      const target = match[1].split('#')[0]
      if (!target || /^(?:[a-z]+:|\/)/i.test(target)) continue
      counts.documentLinks += 1
      if (!existsSync(resolve(dirname(document), decodeURIComponent(target)))) {
        errors.push(`Broken document link: ${relative(root, document)} -> ${target}`)
      }
    }
  }
  return { counts, errors }
}

const thisFile = fileURLToPath(import.meta.url)
if (process.argv[1] && resolve(process.argv[1]) === thisFile) {
  const result = checkRepository(resolve(dirname(thisFile), '..'))
  console.log(JSON.stringify(result, null, 2))
  process.exitCode = result.errors.length ? 1 : 0
}

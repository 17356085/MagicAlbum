import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { gzipSync } from 'node:zlib'

const listingEntries = ['index.html', 'src/pages/Discover.vue', 'src/pages/SectionsList.vue']
const deferredPages = ['src/pages/ThreadCreate.vue', 'src/pages/ThreadDetail.vue']
const authModalSource = 'src/components/auth/AuthModal.vue'

export function staticChunks(manifest, entry) {
  const visited = new Set()
  function visit(key) {
    if (visited.has(key)) return
    const chunk = manifest[key]
    if (!chunk || typeof chunk.file !== 'string') throw new Error(`Missing build manifest entry: ${key}`)
    visited.add(key)
    for (const dependency of chunk.imports || []) visit(dependency)
  }
  visit(entry)
  return [...visited].map(key => manifest[key])
}

export function checkDeferredAuthModal(manifest) {
  const authModal = manifest[authModalSource]
  if (!authModal || typeof authModal.file !== 'string') {
    throw new Error(`Missing build manifest entry: ${authModalSource}`)
  }
  if (!authModal.isDynamicEntry) {
    throw new Error(`Auth modal must remain dynamically loaded: ${authModalSource}`)
  }

  const staticFiles = new Set(staticChunks(manifest, 'index.html').map(chunk => chunk.file))
  if (staticFiles.has(authModal.file)) {
    throw new Error(`Auth modal is reachable from the initial static graph: ${authModal.file}`)
  }
}

export function checkInitialLoad(manifest) {
  checkDeferredAuthModal(manifest)
  const routes = {}
  for (const entry of listingEntries) {
    const chunks = staticChunks(manifest, entry)
    const heavy = chunks.filter(chunk => ['math', 'highlight'].includes(chunk.name))
    if (heavy.length) {
      throw new Error(`Optional rendering dependencies loaded by ${entry}: ${heavy.map(chunk => chunk.file).join(', ')}`)
    }
    routes[entry] = chunks.map(chunk => chunk.file)
  }
  for (const entry of deferredPages) {
    if (!manifest[entry]?.isDynamicEntry) throw new Error(`Page must remain lazy-loaded: ${entry}`)
  }
  return routes
}

function main() {
  const dist = resolve(dirname(fileURLToPath(import.meta.url)), '../dist')
  const manifest = JSON.parse(readFileSync(resolve(dist, '.vite/manifest.json'), 'utf8'))
  const routes = checkInitialLoad(manifest)
  const metrics = {}
  for (const [entry, files] of Object.entries(routes)) {
    let bytes = 0
    let gzipBytes = 0
    for (const file of files) {
      const buffer = readFileSync(resolve(dist, file))
      bytes += buffer.length
      gzipBytes += gzipSync(buffer).length
    }
    metrics[entry] = { files: files.length, bytes, gzipBytes }
  }
  process.stdout.write(`${JSON.stringify({ initialLoad: metrics }, null, 2)}\n`)
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main()

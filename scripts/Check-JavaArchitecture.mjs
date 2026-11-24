import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { basename, dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const MODULES = {
  'platform-web': { boundary: 'com.example.demo.', dependencies: [] },
  'chat-domain': { boundary: 'com.example.demo.', dependencies: ['platform-web'] },
  backend: { boundary: 'com.example.demo.', dependencies: ['platform-web', 'chat-domain'] },
  'chat-service': { boundary: 'com.example.chatprocess.', dependencies: ['platform-web', 'chat-domain'] },
  gateway: { boundary: 'com.example.gateway.', dependencies: [] },
  'notification-service': { boundary: 'com.example.notification.', dependencies: [] },
}
const CONFIG_RELATIVE_PATH = join('config', 'java-domain-couplings.json')
const DEFAULT_DOMAIN_CONFIG = {
  domainRoots: {},
  guardedLayers: ['entity', 'repo'],
  allowedCrossDomainDependencies: [],
}

function walk(path) {
  if (!existsSync(path)) return []
  return readdirSync(path, { withFileTypes: true }).flatMap(entry => {
    const target = join(path, entry.name)
    return entry.isDirectory() ? walk(target) : entry.name.endsWith('.java') ? [target] : []
  })
}

function applicationBoundary(module) {
  return MODULES[module].boundary
}

function owningModules(target, owners, packageOwners) {
  if (target.endsWith('.*') && packageOwners.has(target.slice(0, -2))) {
    return packageOwners.get(target.slice(0, -2))
  }
  // Resolve nested types and static member imports to their top-level source.
  for (let qualified = target; qualified.includes('.'); qualified = qualified.slice(0, qualified.lastIndexOf('.'))) {
    if (owners.has(qualified)) return [owners.get(qualified)]
  }
  return []
}

function normalizePath(path) {
  return path.split('\\').join('/')
}

function readDomainConfig(root) {
  const path = join(root, CONFIG_RELATIVE_PATH)
  if (!existsSync(path)) {
    return {
      config: DEFAULT_DOMAIN_CONFIG,
      error: `Missing Java domain coupling config: ${normalizePath(relative(root, path))}`,
    }
  }
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'))
    const guardedLayers = Array.isArray(parsed.guardedLayers) ? parsed.guardedLayers : null
    const allowed = Array.isArray(parsed.allowedCrossDomainDependencies)
      ? parsed.allowedCrossDomainDependencies : null
    if (!parsed.domainRoots || typeof parsed.domainRoots !== 'object' || !guardedLayers || !allowed) {
      throw new Error('expected domainRoots, guardedLayers, and allowedCrossDomainDependencies arrays')
    }
    for (const entry of allowed) {
      if (!entry || typeof entry.source !== 'string' || typeof entry.target !== 'string') {
        throw new Error('each allowed dependency needs string source and target fields')
      }
    }
    return { config: parsed, error: null }
  } catch (error) {
    return {
      config: DEFAULT_DOMAIN_CONFIG,
      error: `Invalid Java domain coupling config: ${normalizePath(relative(root, path))}: ${error.message}`,
    }
  }
}

function packageRoot(packageName, module) {
  const boundary = applicationBoundary(module)
  if (!packageName.startsWith(boundary)) return null
  const remainder = packageName.slice(boundary.length)
  return remainder.split('.')[0] || null
}

function domainForPackage(packageName, module, config) {
  const root = packageRoot(packageName, module)
  if (!root || module !== 'backend') return null
  for (const [domain, roots] of Object.entries(config.domainRoots ?? {})) {
    if (Array.isArray(roots) && roots.includes(root)) return domain
  }
  // An unregistered backend package is treated as its own domain so a new
  // package cannot bypass the guard by omitting a config entry.
  return root
}

function guardedLayer(target, config) {
  const parts = target.split('.')
  return (config.guardedLayers ?? []).find(layer => parts.includes(layer)) ?? null
}

function isAllowedCrossDomainDependency(sourceFile, target, config) {
  return (config.allowedCrossDomainDependencies ?? []).some(entry =>
    entry.source === sourceFile && entry.target === target)
}

export function domainCouplingViolation(sourcePackage, target, module, sourceFile, config = DEFAULT_DOMAIN_CONFIG) {
  if (module !== 'backend') return null
  const layer = guardedLayer(target, config)
  if (!layer) return null
  const sourceDomain = domainForPackage(sourcePackage, module, config)
  const targetDomain = domainForPackage(target, module, config)
  if (!sourceDomain || !targetDomain || sourceDomain === targetDomain) return null
  if (sourceFile && isAllowedCrossDomainDependency(sourceFile, target, config)) return null
  return `forbidden cross-domain ${layer} dependency: ${sourceDomain} -> ${targetDomain}: ${target}`
}

// Enforces declared source dependencies. It does not analyze reflection or runtime wiring.
export function dependencyViolation(sourcePackage, target, module, options = {}) {
  const boundary = applicationBoundary(module)
  const normalizedOptions = typeof options === 'string' ? { sourceFile: options } : options
  const targetModules = normalizedOptions.targetModules ?? []
  if (targetModules.some(owner => owner !== module && !MODULES[module].dependencies.includes(owner))) {
    return 'cross-application dependency'
  }
  if (targetModules.length === 0 && target.startsWith('com.example.') && target !== boundary.slice(0, -1)
      && !target.startsWith(boundary)) return 'cross-application dependency'
  const sourceParts = sourcePackage.split('.')
  const targetParts = target.split('.')
  if (sourceParts.includes('dto') && target.startsWith(boundary)
      && ['repo', 'service', 'controller', 'entity'].some(part => targetParts.includes(part))) return 'DTO depends on implementation layer'
  if (sourceParts.includes('entity') && target.startsWith(boundary)
      && ['controller', 'service', 'repo', 'dto'].some(part => targetParts.includes(part))) return 'entity depends on upper layer'
  if (sourceParts.includes('repo') && (targetParts.includes('controller')
      || target.startsWith('org.springframework.web.'))) return 'repository depends on web layer'
  if (sourceParts.includes('service') && (targetParts.includes('controller')
      || target.startsWith('org.springframework.web.bind.annotation.'))) return 'service depends on controller layer'
  return domainCouplingViolation(
    sourcePackage,
    target,
    module,
    normalizedOptions.sourceFile,
    normalizedOptions.config ?? DEFAULT_DOMAIN_CONFIG,
  )
}

export function checkJavaArchitecture(root) {
  const projectRoot = resolve(root)
  const errors = []
  let files = 0
  let dependencies = 0
  const { config, error: configError } = readDomainConfig(projectRoot)
  if (configError) errors.push(configError)
  const owners = new Map()
  const packageOwners = new Map()
  const sources = []
  for (const module of Object.keys(MODULES)) {
    for (const file of walk(join(projectRoot, module, 'src/main/java'))) {
      files += 1
      // Skip strings and comments, preserving newlines for line-oriented import matching.
      const text = readFileSync(file, 'utf8')
        .replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g,
          token => token.replace(/[^\r\n]/g, ' '))
      const sourcePackage = text.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1]
      if (!sourcePackage) {
        errors.push(`${normalizePath(relative(projectRoot, file))}: missing package`)
        continue
      }
      const sourceFile = normalizePath(relative(projectRoot, file))
      const qualified = sourcePackage + '.' + basename(file, '.java')
      if (owners.has(qualified)) errors.push(sourceFile + ': duplicate Java source type ' + qualified)
      owners.set(qualified, module)
      if (!packageOwners.has(sourcePackage)) packageOwners.set(sourcePackage, new Set())
      packageOwners.get(sourcePackage).add(module)
      sources.push({ module, sourcePackage, sourceFile, text })
    }
  }
  for (const { module, sourcePackage, sourceFile, text } of sources) {
    // Match imports and explicit qualified application references as well as wildcard imports.
    const targets = new Set([...text.matchAll(/^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/gm)].map(match => match[1]))
    for (const match of text.matchAll(/\bcom\.example\.[\w.]+/g)) {
      if (match[0] !== sourcePackage) targets.add(match[0])
    }
    for (const target of targets) {
      dependencies += 1
      const targetModules = target.startsWith('com.example.') ? [...owningModules(target, owners, packageOwners)] : []
      const violation = dependencyViolation(sourcePackage, target, module, { sourceFile, config, targetModules })
      if (violation) errors.push(`${sourceFile}: ${violation}`)
    }
  }
  if (files === 0) errors.push('No Java production sources inspected')
  return { files, dependencies, errors }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve(process.argv[2] ?? join(dirname(fileURLToPath(import.meta.url)), '..'))
  const result = checkJavaArchitecture(root)
  console.log(JSON.stringify(result, null, 2))
  process.exitCode = result.errors.length ? 1 : 0
}

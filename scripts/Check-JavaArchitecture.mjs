import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

function walk(path) {
  if (!existsSync(path)) return []
  return readdirSync(path, { withFileTypes: true }).flatMap(entry => {
    const target = join(path, entry.name)
    return entry.isDirectory() ? walk(target) : entry.name.endsWith('.java') ? [target] : []
  })
}

// Enforces declared source dependencies. It does not analyze reflection or runtime wiring.
export function dependencyViolation(sourcePackage, target, module) {
  const boundary = module === 'backend' ? 'com.example.demo.'
    : module === 'gateway' ? 'com.example.gateway.' : 'com.example.notification.'
  if (target.startsWith('com.example.') && target !== boundary.slice(0, -1)
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
  return null
}

export function checkJavaArchitecture(root) {
  const errors = []
  let files = 0
  let dependencies = 0
  for (const module of ['backend', 'gateway', 'notification-service']) {
    for (const file of walk(join(root, module, 'src/main/java'))) {
      files += 1
      // Skip strings and comments, preserving newlines for line-oriented import matching.
      const text = readFileSync(file, 'utf8')
        .replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\*[\s\S]*?\*\/|\/\/[^\r\n]*/g,
          token => token.replace(/[^\r\n]/g, ' '))
      const sourcePackage = text.match(/^\s*package\s+([\w.]+)\s*;/m)?.[1]
      if (!sourcePackage) {
        errors.push(`${relative(root, file)}: missing package`)
        continue
      }
      // Match imports and explicit qualified application references as well as wildcard imports.
      const targets = new Set([...text.matchAll(/^\s*import\s+(?:static\s+)?([\w.*]+)\s*;/gm)].map(match => match[1]))
      for (const match of text.matchAll(/\bcom\.example\.[\w.]+/g)) {
        if (match[0] !== sourcePackage) targets.add(match[0])
      }
      for (const target of targets) {
        dependencies += 1
        const violation = dependencyViolation(sourcePackage, target, module)
        if (violation) errors.push(`${relative(root, file)}: ${violation}: ${target}`)
      }
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

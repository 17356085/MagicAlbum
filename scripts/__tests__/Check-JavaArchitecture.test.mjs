import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { checkJavaArchitecture, dependencyViolation } from '../Check-JavaArchitecture.mjs'

function write(root, path, text = '') {
  mkdirSync(dirname(join(root, path)), { recursive: true })
  writeFileSync(join(root, path), text)
}

const domainRoots = {
  identity: ['auth', 'user'],
  content: ['posts', 'sections', 'tags', 'threads'],
  interaction: ['bookmarks'],
  notifications: ['notifications'],
  storage: ['storage'],
  ai: ['ai'],
  governance: ['admin', 'moderation'],
  infrastructure: ['bootstrap', 'common', 'config'],
}

function writeDomainConfig(root, allowedCrossDomainDependencies = []) {
  mkdirSync(join(root, 'config'), { recursive: true })
  writeFileSync(join(root, 'config/java-domain-couplings.json'), JSON.stringify({
    version: 1,
    domainRoots,
    guardedLayers: ['entity', 'repo'],
    allowedCrossDomainDependencies,
  }, null, 2))
}

test('rejects layer inversions and cross-application coupling', () => {
  for (const [source, target] of [
    ['com.example.demo.posts.dto', 'com.example.demo.posts.entity.Post'],
    ['com.example.demo.posts.entity', 'com.example.demo.posts.service.PostService'],
    ['com.example.demo.posts.repo', 'org.springframework.web.bind.annotation.GetMapping'],
    ['com.example.demo.posts.service', 'com.example.demo.posts.controller.PostsController'],
    ['com.example.demo.posts.service', 'org.springframework.web.bind.annotation.*'],
    ['com.example.demo.common', 'com.example.notification.service.NotificationService'],
    ['com.example.demo.search.service', 'com.example.demo.threads.repo.ThreadRepository'],
  ]) assert.ok(dependencyViolation(source, target, 'backend'), `${source} -> ${target}`)
})

test('allows repository projections, HTTP errors and application-neutral types', () => {
  for (const [source, target] of [
    ['com.example.demo.posts.repo', 'com.example.demo.posts.dto.PostDto'],
    ['com.example.demo.posts.service', 'org.springframework.web.server.ResponseStatusException'],
    ['com.example.demo.posts.dto', 'java.time.OffsetDateTime'],
    ['com.example.demo.posts.entity', 'jakarta.persistence.Entity'],
    ['com.example.demo.posts.controller', 'com.example.demo.posts.service.PostService'],
  ]) assert.equal(dependencyViolation(source, target, 'backend'), null)
})

test('inspects real source references while ignoring comments and literals', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-java-boundaries-'))
  try {
    writeDomainConfig(root)
    const directory = join(root, 'backend/src/main/java/com/example/demo/posts/service')
    mkdirSync(directory, { recursive: true })
    writeFileSync(join(directory, 'Example.java'), `
      package com.example.demo.posts.service;
      // import com.example.notification.service.RemoteService;
      class Example {
        String help = "com.example.notification.service.RemoteService";
        com.example.demo.posts.controller.PostsController forbidden;
      }
    `)
    const result = checkJavaArchitecture(root)
    assert.equal(result.files, 1)
    assert.equal(result.errors.length, 1)
    assert.match(result.errors[0], /service depends on controller layer/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('rejects new cross-domain entity and repository references', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-domain-couplings-'))
  try {
    writeDomainConfig(root)
    write(root, 'backend/src/main/java/com/example/demo/search/service/SearchService.java', `
      package com.example.demo.search.service;
      class SearchService { com.example.demo.threads.repo.ThreadRepository repository; }
    `)
    write(root, 'backend/src/main/java/com/example/demo/storage/service/StorageReader.java', `
      package com.example.demo.storage.service;
      class StorageReader { com.example.demo.user.entity.User owner; }
    `)
    const result = checkJavaArchitecture(root)
    assert.ok(result.errors.some(error => error.includes('SearchService.java')
      && error.includes('forbidden cross-domain repo dependency')))
    assert.ok(result.errors.some(error => error.includes('StorageReader.java')
      && error.includes('forbidden cross-domain entity dependency')))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('permits exact historical pairs only and keeps public DTO/service edges available', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-domain-allowlist-'))
  const allowedSource = 'backend/src/main/java/com/example/demo/moderation/service/LegacyBridge.java'
  const target = 'com.example.demo.threads.repo.ThreadRepository'
  try {
    writeDomainConfig(root, [{ source: allowedSource, target }])
    write(root, allowedSource, `
      package com.example.demo.moderation.service;
      import ${target};
      class LegacyBridge { ThreadRepository repository; }
    `)
    write(root, 'backend/src/main/java/com/example/demo/moderation/service/NewBridge.java', `
      package com.example.demo.moderation.service;
      import ${target};
      class NewBridge { ThreadRepository repository; }
    `)
    write(root, 'backend/src/main/java/com/example/demo/search/service/SearchService.java', `
      package com.example.demo.search.service;
      import com.example.demo.threads.dto.ThreadDto;
      import com.example.demo.threads.service.ThreadService;
      class SearchService { ThreadDto read(ThreadService service) { return null; } }
    `)
    const result = checkJavaArchitecture(root)
    assert.equal(result.errors.filter(error => error.includes('LegacyBridge.java')).length, 0)
    assert.ok(result.errors.some(error => error.includes('NewBridge.java')
      && error.includes('forbidden cross-domain repo dependency')))
    assert.equal(result.errors.filter(error => error.includes('SearchService.java')).length, 0)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('allows a content-owned event to use content entities without a whitelist', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-content-event-'))
  try {
    writeDomainConfig(root)
    write(root, 'backend/src/main/java/com/example/demo/threads/event/ThreadContentEvent.java', `
      package com.example.demo.threads.event;
      import com.example.demo.threads.entity.Thread;
      class ThreadContentEvent { Thread aggregate; }
    `)
    const result = checkJavaArchitecture(root)
    assert.deepEqual(result.errors, [])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test('fails when no Java production sources are inspected', () => {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-empty-java-'))
  try {
    writeDomainConfig(root)
    const result = checkJavaArchitecture(root)
    assert.equal(result.files, 0)
    assert.ok(result.errors.includes('No Java production sources inspected'))
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

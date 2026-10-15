import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { checkJavaArchitecture, dependencyViolation } from '../Check-JavaArchitecture.mjs'

test('rejects layer inversions and cross-application coupling', () => {
  for (const [source, target] of [
    ['com.example.demo.posts.dto', 'com.example.demo.posts.entity.Post'],
    ['com.example.demo.posts.entity', 'com.example.demo.posts.service.PostService'],
    ['com.example.demo.posts.repo', 'org.springframework.web.bind.annotation.GetMapping'],
    ['com.example.demo.posts.service', 'com.example.demo.posts.controller.PostsController'],
    ['com.example.demo.posts.service', 'org.springframework.web.bind.annotation.*'],
    ['com.example.demo.common', 'com.example.notification.service.NotificationService'],
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

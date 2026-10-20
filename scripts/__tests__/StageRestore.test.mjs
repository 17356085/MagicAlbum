import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { once } from 'node:events'
import { test } from 'node:test'
import { createServer } from 'node:net'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const modulePath = join(repoRoot, 'scripts', 'lib', 'StageRestore.psm1').replaceAll("'", "''")

const fullSummary = (summary = 'mock summary') => ({
  threadId: 123,
  taskId: 'task-1',
  contentVersion: 0,
  taskContentVersion: 0,
  status: 'COMPLETED',
  summary,
  canManage: true,
  attempts: 1,
  errorCode: null,
  provider: 'mock',
  model: 'mock-model',
  inputTokens: 8,
  outputTokens: 7,
  usageEstimated: false,
  chargedTokens: 7,
  requestId: 'request-1',
  updatedAt: '2026-10-20T02:00:00Z',
})

function psLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`
}

async function freePort() {
  const server = createServer()
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const port = server.address().port
  server.close()
  await once(server, 'close')
  return port
}

function serverScript(mode, port) {
  return `
$ErrorActionPreference = 'Stop'
$Port = ${port}
$Mode = '${String(mode).replaceAll("'", "''")}'
$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Output 'READY'
function Reply([object]$Context, [int]$Status, [object]$Payload) {
  $Context.Response.StatusCode = $Status
  $Context.Response.ContentType = 'application/json; charset=utf-8'
  $json = if ($null -eq $Payload) { '{}' } else { ConvertTo-Json -InputObject $Payload -Compress -Depth 20 }
  $bytes = [Text.Encoding]::UTF8.GetBytes($json)
  $Context.Response.ContentLength64 = $bytes.Length
  $Context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
  $Context.Response.Close()
}
function Copy-Summary {
  $copy = [ordered]@{}
  foreach ($entry in $summary.GetEnumerator()) { $copy[$entry.Key] = $entry.Value }
  return $copy
}
$summary = [ordered]@{
  threadId = 123; taskId = 'task-1'; contentVersion = 0; taskContentVersion = 0
  status = 'COMPLETED'; summary = if ($Mode -eq 'badHash') { 'tampered summary' } else { 'mock summary' }
  canManage = $true; attempts = 1; errorCode = $null; provider = 'mock'; model = 'mock-model'
  inputTokens = 8; outputTokens = 7; usageEstimated = $false; chargedTokens = 7
  requestId = 'request-1'; updatedAt = '2026-10-20T02:00:00Z'
}
$audit = @(
  [ordered]@{ id = 10; actorId = 1; action = 'MODERATOR_SECTIONS_CHANGED'; targetType = 'USER'; targetId = 1; reason = 'stage-restore-ABCDEF1234'; occurredAt = '2026-10-20T02:00:00Z'; requestId = 'audit-1' },
  [ordered]@{ id = 11; actorId = 1; action = 'POSTING_RESTRICTION_CHANGED'; targetType = 'USER'; targetId = 20; reason = 'stage-restore-ABCDEF1234'; occurredAt = '2026-10-20T02:00:01Z'; requestId = 'audit-2' }
)
$pollCount = 0
$notificationCalls = 0
try {
  while ($true) {
    $context = $listener.GetContext()
    $path = $context.Request.Url.AbsolutePath
    $method = $context.Request.HttpMethod
    if ($Mode -eq 'permission' -and $path -eq '/api/v1/governance/me') { Reply $context 403 ([ordered]@{ message = 'forbidden' }); continue }
    if ($method -eq 'GET' -and $path -eq '/api/v1/governance/me') {
      $authorization = [string]$context.Request.Headers['Authorization']
      if ($authorization -eq 'Bearer OTHER') {
        Reply $context 200 ([ordered]@{ admin = $false; moderatedSectionIds = @(); postingRestricted = $true; restrictedUntil = $null })
      } else {
        Reply $context 200 ([ordered]@{ admin = $true; moderatedSectionIds = @(5); postingRestricted = $false; restrictedUntil = $null })
      }
      continue
    }
    if ($method -eq 'PUT' -and $path -like '/api/v1/governance/users/*/moderator-sections') { Reply $context 200 ([ordered]@{ id = 1; moderatedSectionIds = @(5) }); continue }
    if ($method -eq 'PUT' -and $path -like '/api/v1/governance/users/*/posting-restriction') { Reply $context 200 ([ordered]@{ id = 20; postingRestricted = $true; restrictedUntil = $null }); continue }
    if ($method -eq 'POST' -and $path -eq '/api/v1/threads/123/like') { Reply $context 200 ([ordered]@{ threadId = 123; liked = $true; likeCount = 1 }); continue }
    if ($method -eq 'GET' -and $path -eq '/api/v1/threads/123/like') { Reply $context 200 ([ordered]@{ threadId = 123; liked = $true; likeCount = 1 }); continue }
    if ($method -eq 'POST' -and $path -eq '/api/v1/ai/summary/123') {
      $key = [string]$context.Request.Headers['Idempotency-Key']
      if ($key -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$' -or $context.Request.QueryString['force'] -ne 'false') { Reply $context 400 ([ordered]@{ message = 'idempotency or force missing' }); continue }
      if ($Mode -eq 'pending') {
        $pending = Copy-Summary; $pending['status'] = 'PENDING'; $pending['summary'] = ''
        Reply $context 200 $pending
      } else { Reply $context 200 $summary }
      continue
    }
    if ($method -eq 'GET' -and $path -eq '/api/v1/ai/summary/tasks/task-1') {
      if ($Mode -eq 'pending') {
        $pollCount++
        if ($pollCount -eq 1) { $processing = Copy-Summary; $processing['status'] = 'PROCESSING'; $processing['summary'] = ''; Reply $context 200 $processing }
        else { Reply $context 200 $summary }
      } else { Reply $context 200 $summary }
      continue
    }
    if ($method -eq 'GET' -and $path -eq '/api/v1/ai/summary/123') { Reply $context 200 $summary; continue }
    if ($method -eq 'POST' -and $path -eq '/api/v1/threads') { Reply $context 403 ([ordered]@{ message = 'restricted' }); continue }
    if ($method -eq 'GET' -and $path -eq '/api/v1/notifications') {
      $notificationCalls++
      $items = @(if (-not ($Mode -eq 'missingNotification' -or ($Mode -eq 'delayedNotification' -and $notificationCalls -eq 1) -or ($context.Request.Headers['Authorization'] -eq 'Bearer OTHER' -and $Mode -ne 'leakedNotification'))) { [ordered]@{ id = 77; type = 'like'; threadId = 123; targetId = 123; targetType = 'thread'; link = '/threads/123'; title = 'like'; content = 'like' } })
      Reply $context 200 ([ordered]@{ items = $items; page = 1; size = 100; total = $items.Count; source = 'primary' }); continue
    }
    if ($method -eq 'GET' -and $path -eq '/api/v1/governance/audit') {
      if ([int]$context.Request.QueryString['size'] -gt 50) { Reply $context 400 ([ordered]@{ message = 'size too large' }); continue }
      Reply $context 200 ([ordered]@{ items = $audit; page = 1; size = 50; total = $audit.Count }); continue
    }
    Reply $context 404 ([ordered]@{ message = 'not found' })
  }
} finally {
  $listener.Stop()
  $listener.Close()
}
`
}

async function startServer(mode = 'ok') {
  const port = await freePort()
  const process = spawn('pwsh', ['-NoProfile', '-NonInteractive', '-Command', serverScript(mode, port)], {
    cwd: repoRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let stdout = ''
  let stderr = ''
  process.stdout.on('data', (chunk) => { stdout += chunk.toString() })
  process.stderr.on('data', (chunk) => { stderr += chunk.toString() })
  const deadline = Date.now() + 5000
  while (!stdout.includes('READY')) {
    if (process.exitCode !== null) throw new Error(`HttpListener exited: ${stdout}\n${stderr}`)
    if (Date.now() > deadline) throw new Error(`HttpListener did not start: ${stdout}\n${stderr}`)
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
  return { process, baseUrl: `http://127.0.0.1:${port}`, getLogs: () => `${stdout}\n${stderr}` }
}

function runPowerShell(script, expectedStatus = 0) {
  const result = spawnSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], {
    cwd: repoRoot,
    encoding: 'utf8',
    maxBuffer: 1024 * 1024,
    timeout: 12000,
  })
  assert.equal(result.status, expectedStatus, `${result.stdout}\n${result.stderr}`)
  return result.stdout
}

function clientPrefix(baseUrl) {
  return `$ErrorActionPreference = 'Stop'
Import-Module ${psLiteral(join(repoRoot, 'scripts', 'lib', 'StageRestore.psm1'))} -Force
$base = ${psLiteral(baseUrl)}
`
}

test('executes a full stage fixture and restore readback through a PowerShell HttpListener', async () => {
  const server = await startServer()
  try {
    const script = `${clientPrefix(server.baseUrl)}
$fixture = New-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -AccountId 1 -ThreadId 123 -SectionId 5 -RunId 'ABCDEF1234' -OtherToken 'OTHER' -OtherUserId 20
$evidence = Assert-StageRestoreFixture -BaseUrl $base -Token 'RESTORED' -Fixture $fixture -OtherToken 'OTHER'
if (-not $evidence.passed -or $evidence.provider -ne 'mock' -or -not $evidence.permissionsVerified -or -not $evidence.summaryVerified -or -not $evidence.notificationVerified -or -not $evidence.auditVerified -or -not $evidence.idempotencyVerified) { throw 'stage evidence gate did not pass' }
if ($fixture.PSObject.Properties.Name -contains 'Token' -or $fixture.PSObject.Properties.Name -contains 'OtherToken') { throw 'fixture leaked a token property' }
ConvertTo-Json -InputObject $evidence -Compress -Depth 10
`
    const output = runPowerShell(script)
    const evidence = JSON.parse(output.trim().split(/\r?\n/).at(-1))
    assert.equal(evidence.passed, true)
    assert.equal(evidence.provider, 'mock')
    assert.equal(evidence.contentVersion, 0)
    assert.equal(evidence.notificationId, 77)
    assert.equal(evidence.notificationActorId, null)
    assert.match(evidence.summarySHA256, /^[0-9a-f]{64}$/)
    assert.doesNotMatch(output, /ADMIN|OTHER|RESTORED/)
    assert.doesNotMatch(server.getLogs(), /ADMIN|OTHER|RESTORED/)
  } finally {
    server.process.kill()
    await once(server.process, 'exit').catch(() => {})
  }
})

test('rejects a restored fixture when the summary hash changed', async () => {
  const server = await startServer('badHash')
  try {
    const script = `${clientPrefix(server.baseUrl)}
$fixture = [pscustomobject][ordered]@{ runId = 'ABCDEF1234'; reason = 'stage-restore-ABCDEF1234'; accountId = 1; otherUserId = 20; threadId = 123; sectionId = 5; idempotencyKey = '11111111-1111-4111-8111-111111111111'; taskId = 'task-1'; contentVersion = 0; summarySHA256 = ('good summary' | ForEach-Object { $sha = [Security.Cryptography.SHA256]::Create(); try { (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($_)) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() } }); attempts = 1; chargedTokens = 7; usageEstimated = $false; provider = 'mock'; notification = [pscustomobject]@{ id = 77 }; audit = @() }
try { Assert-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -Fixture $fixture -OtherToken 'OTHER'; throw 'hash mismatch was accepted' } catch { if ($_.Exception.Message -notmatch 'summary') { throw }; Write-Output 'EXPECTED_HASH_ERROR' }
`
    const output = runPowerShell(script)
    assert.match(output, /EXPECTED_HASH_ERROR/)
  } finally {
    server.process.kill()
    await once(server.process, 'exit').catch(() => {})
  }
})

test('rejects a restored notification exposed to the wrong account', async () => {
  const server = await startServer('leakedNotification')
  try {
    const script = `${clientPrefix(server.baseUrl)}
$fixture = New-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -AccountId 1 -ThreadId 123 -SectionId 5 -RunId 'ABCDEF1234' -OtherToken 'OTHER' -OtherUserId 20
try { Assert-StageRestoreFixture -BaseUrl $base -Token 'RESTORED' -Fixture $fixture -OtherToken 'OTHER'; throw 'cross-account leak was accepted' } catch { if ($_.Exception.Message -notmatch 'leaked into the other account') { throw }; Write-Output 'EXPECTED_ISOLATION_ERROR' }
`
    assert.match(runPowerShell(script), /EXPECTED_ISOLATION_ERROR/)
  } finally {
    server.process.kill()
    await once(server.process, 'exit').catch(() => {})
  }
})

test('polls a pending summary serially until the mock task completes', async () => {
  const server = await startServer('pending')
  try {
    const script = `${clientPrefix(server.baseUrl)}
$fixture = New-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -AccountId 1 -ThreadId 123 -SectionId 5 -RunId 'ABCDEF1234' -OtherToken 'OTHER' -OtherUserId 20
if ($fixture.provider -ne 'mock' -or $fixture.taskId -ne 'task-1' -or $fixture.contentVersion -ne 0) { throw 'pending summary did not reach the completed task readback' }
ConvertTo-Json -InputObject ([pscustomobject]@{ taskId = $fixture.taskId; status = 'COMPLETED'; provider = $fixture.provider }) -Compress
`
    let output
    try { output = runPowerShell(script) } catch (error) {
      await new Promise((resolve) => setTimeout(resolve, 100))
      throw new Error(`${error.message}\nserver=${server.getLogs()}`)
    }
    assert.deepEqual(JSON.parse(output.trim().split(/\r?\n/).at(-1)), { taskId: 'task-1', status: 'COMPLETED', provider: 'mock' })
  } finally {
    server.process.kill()
    await once(server.process, 'exit').catch(() => {})
  }
})

test('keeps polling an empty notification page until the like event arrives', async () => {
  const server = await startServer('delayedNotification')
  try {
    const script = `${clientPrefix(server.baseUrl)}
$fixture = New-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -AccountId 1 -ThreadId 123 -SectionId 5 -RunId 'ABCDEF1234' -OtherToken 'OTHER' -OtherUserId 20
if ($fixture.notification.id -ne 77 -or $null -ne $fixture.notification.actorId) { throw 'delayed notification fixture did not use the exact DTO fields' }
ConvertTo-Json -InputObject ([pscustomobject]@{ notificationId = $fixture.notification.id; actorId = $fixture.notification.actorId }) -Compress
`
    const output = runPowerShell(script)
    assert.deepEqual(JSON.parse(output.trim().split(/\r?\n/).at(-1)), { notificationId: 77, actorId: null })
  } finally {
    server.process.kill()
    await once(server.process, 'exit').catch(() => {})
  }
})

test('rejects permission errors and missing notifications with explicit API evidence', async () => {
  const permission = await startServer('permission')
  try {
    const script = `${clientPrefix(permission.baseUrl)}
$fixture = [pscustomobject][ordered]@{ runId = 'ABCDEF1234'; reason = 'stage-restore-ABCDEF1234'; accountId = 1; otherUserId = 20; threadId = 123; sectionId = 5; idempotencyKey = '11111111-1111-4111-8111-111111111111'; taskId = 'task-1'; contentVersion = 0; summarySHA256 = ('mock summary' | ForEach-Object { $sha = [Security.Cryptography.SHA256]::Create(); try { (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($_)) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() } }); attempts = 1; chargedTokens = 7; usageEstimated = $false; provider = 'mock'; notification = [pscustomobject]@{ id = 77 }; audit = @() }
try { Assert-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -Fixture $fixture -OtherToken 'OTHER'; throw 'permission error was accepted' } catch { if ($_.Exception.Message -notmatch 'HTTP 403') { throw }; Write-Output 'EXPECTED_PERMISSION_ERROR' }
`
    assert.match(runPowerShell(script), /EXPECTED_PERMISSION_ERROR/)
  } finally {
    permission.process.kill()
    await once(permission.process, 'exit').catch(() => {})
  }

  const missing = await startServer('missingNotification')
  try {
    const script = `${clientPrefix(missing.baseUrl)}
$fixture = [pscustomobject][ordered]@{ runId = 'ABCDEF1234'; reason = 'stage-restore-ABCDEF1234'; accountId = 1; otherUserId = 20; threadId = 123; sectionId = 5; idempotencyKey = '11111111-1111-4111-8111-111111111111'; taskId = 'task-1'; contentVersion = 0; summarySHA256 = ('mock summary' | ForEach-Object { $sha = [Security.Cryptography.SHA256]::Create(); try { (($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($_)) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() } }); attempts = 1; chargedTokens = 7; usageEstimated = $false; provider = 'mock'; notification = [pscustomobject]@{ id = 77 }; audit = @() }
try { Assert-StageRestoreFixture -BaseUrl $base -Token 'ADMIN' -Fixture $fixture -OtherToken 'OTHER'; throw 'missing notification was accepted' } catch { if ($_.Exception.Message -notmatch 'notification') { throw }; Write-Output 'EXPECTED_NOTIFICATION_ERROR' }
`
    assert.match(runPowerShell(script), /EXPECTED_NOTIFICATION_ERROR/)
  } finally {
    missing.process.kill()
    await once(missing.process, 'exit').catch(() => {})
  }
})

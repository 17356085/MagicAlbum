import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const modulePath = join(repoRoot, 'scripts', 'lib', 'LocalRuntime.psm1').replaceAll("'", "''")

function runPowerShell(script) {
  const result = spawnSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', script], {
    cwd: repoRoot,
    encoding: 'utf8',
  })
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`)
  return result.stdout
}

function runtimeScript(body) {
  return `
$ErrorActionPreference = 'Stop'
Import-Module '${modulePath}' -Force
${body}
`
}

test('validates local runtime identifiers, credentials, required keys and ports', () => {
  runPowerShell(runtimeScript(`
$cfg = [ordered]@{}
@'
SPRING_PROFILES_ACTIVE=local
DB_HOST=127.0.0.1
DB_PORT=13306
DB_NAME=bluealbum_local
DB_USER=bluealbum
DB_PASSWORD=DbPass123!
DB_ROOT_PASSWORD=RootPass123!
NOTIFICATION_DB_NAME=bluealbum_local_notifications
REDIS_HOST=127.0.0.1
REDIS_PORT=16379
REDIS_PASSWORD=RedisPass123!
SPRING_RABBITMQ_HOST=127.0.0.1
SPRING_RABBITMQ_PORT=15673
SPRING_RABBITMQ_USERNAME=bluealbum
SPRING_RABBITMQ_PASSWORD=RabbitPass123!
RABBITMQ_MANAGEMENT_PORT=25673
APP_RABBIT_ENABLED=true
JWT_ISSUER=MagicAlbum
JWT_SECRET=Abcdefghijklmnop1234567890!XYZq2
INTERNAL_SERVICE_TOKEN=Abcdefghijklmnop1234567890!XYZq2
NOTIFICATION_SERVICE_MODE=remote
NOTIFICATION_SERVICE_BASE_URL=http://127.0.0.1:8081
APP_AUTH_VERIFY_MODE=off
AI_PROVIDER=mock
VITE_AUTH_VERIFY_MODE=off
VITE_API_BASE_URL=/api/v1
VITE_USE_API_MOCK=false
VITE_AUTH_OAUTH_GITHUB_ENABLED=false
VITE_AUTH_OAUTH_GOOGLE_ENABLED=false
VITE_AUTH_OAUTH_APPLE_ENABLED=false
VITE_AUTH_OAUTH_WECHAT_ENABLED=false
'@ -split '\r?\n' | Where-Object { $_ } | ForEach-Object {
    $parts = $_ -split '=', 2
    $cfg[$parts[0]] = $parts[1].Trim()
}
Assert-LocalRuntimeConfiguration -Values $cfg
if (-not (Test-LocalIdentifier -Value 'bluealbum_local')) { throw 'valid identifier rejected' }
if (Test-LocalIdentifier -Value 'blue-album') { throw 'invalid identifier accepted' }
if (-not (Test-LocalCredential -Value 'DbPass123!')) { throw 'valid credential rejected' }

function Copy-Config([System.Collections.IDictionary]$InputConfig) {
    $copy = [ordered]@{}
    foreach ($entry in $InputConfig.GetEnumerator()) { $copy[$entry.Key] = $entry.Value }
    return $copy
}
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    $threw = $false
    try { & $Action } catch {
        $threw = $true
        if ($_.Exception.Message -notmatch $Pattern) { throw }
    }
    if (-not $threw) { throw "Expected failure matching $Pattern" }
}

$localhostCfg = Copy-Config $cfg
$localhostCfg['NOTIFICATION_SERVICE_BASE_URL'] = 'http://localhost:8081/'
Assert-LocalRuntimeConfiguration -Values $localhostCfg

$legacy = Copy-Config $cfg
$null = $legacy.Remove('RABBITMQ_MANAGEMENT_PORT')
$null = $legacy.Remove('VITE_AUTH_VERIFY_MODE')
$directory = Join-Path (Get-Location) ('.tmp/bluealbum-legacy-runtime-' + [Guid]::NewGuid().ToString('N'))
$path = Join-Path $directory 'runtime.env'
try {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $legacyContent = @($legacy.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join [Environment]::NewLine
    [IO.File]::WriteAllText($path, $legacyContent)
    $before = [IO.File]::ReadAllText($path)
    $legacyRead = Read-LocalRuntimeEnvironment -Path $path
    $compat = Add-LocalRuntimeCompatibilityDefaults -Values $legacyRead
    Assert-LocalRuntimeConfiguration -Values $compat
    if ($compat['RABBITMQ_MANAGEMENT_PORT'] -ne '25673' -or $compat['VITE_AUTH_VERIFY_MODE'] -ne 'off') { throw 'legacy defaults were not added in memory' }
    if ([IO.File]::ReadAllText($path) -ne $before) { throw 'legacy runtime.env was rewritten' }
} finally {
    if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
}

$bad = Copy-Config $cfg
$bad['DB_NAME'] = 'bad-name'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'DB_NAME'
$bad = Copy-Config $cfg
$null = $bad.Remove('NOTIFICATION_DB_NAME')
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'NOTIFICATION_DB_NAME'
$bad = Copy-Config $cfg
$bad['INTERNAL_SERVICE_TOKEN'] = 'change-me'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'INTERNAL_SERVICE_TOKEN'
$bad = Copy-Config $cfg
$bad['NOTIFICATION_SERVICE_BASE_URL'] = 'http://127.0.0.1:9099'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'NOTIFICATION_SERVICE_BASE_URL'
$bad = Copy-Config $cfg
$bad['DB_HOST'] = '10.0.0.8'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'DB_HOST'
$bad = Copy-Config $cfg
$bad['NOTIFICATION_SERVICE_BASE_URL'] = 'http://127.0.0.1:8081/api'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'NOTIFICATION_SERVICE_BASE_URL'
$bad = Copy-Config $cfg
$bad['NOTIFICATION_SERVICE_BASE_URL'] = 'http://user@127.0.0.1:8081/'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'NOTIFICATION_SERVICE_BASE_URL'
$bad = Copy-Config $cfg
$bad['DB_PORT'] = ' 13306'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'DB_PORT'
$bad = Copy-Config $cfg
$bad['DB_PORT'] = '8080'
Assert-Throws { Assert-LocalRuntimeConfiguration -Values $bad } 'duplicate local host ports'
`))
})

test('isolates inherited binding and override environment variables', () => {
  runPowerShell(runtimeScript(`
$namesToSet = @(
    'NOTIFICATION_DATASOURCE_URL', 'SPRING_DATASOURCE_URL', 'SPRING_FLYWAY_ENABLED',
    'SPRING_CONFIG_ADDITIONAL_LOCATION', 'APP_SECURITY_JWT_SECRET', 'SPRING_PROFILES_ACTIVE',
    'APP_NOTIFICATIONS_CLIENT_ENABLED', 'APP_ADMIN_EMAIL', 'GATEWAY_PORT', 'JAVA_TOOL_OPTIONS'
)
try {
    foreach ($name in $namesToSet) { [Environment]::SetEnvironmentVariable($name, 'sentinel', 'Process') }
    $names = @(Get-LocalEnvironmentNamesToIsolate)
    foreach ($name in $namesToSet) {
        if ($name -notin $names) { throw "environment variable was not isolated: $name" }
    }
} finally {
    foreach ($name in $namesToSet) { Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue }
}
`))
})

test('deletes isolated process variables and restores missing, empty and sentinel states exactly', () => {
  runPowerShell(runtimeScript(`
$name = 'BLUEALBUM_LOCALRUNTIME_ENV_TEST'
try {
    [Environment]::SetEnvironmentVariable($name, 'sentinel', 'Process')
    Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    if ([Environment]::GetEnvironmentVariables('Process').Contains($name)) {
        throw 'Remove-Item did not delete the process environment variable'
    }

    $snapshot = Get-LocalEnvironmentSnapshot -Names @($name)
    [Environment]::SetEnvironmentVariable($name, 'sentinel', 'Process')
    Restore-LocalEnvironmentSnapshot -Snapshot $snapshot
    $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
    if ($processEnvironment.Contains($name)) { throw 'missing variable was not removed during restore' }

    [Environment]::SetEnvironmentVariable($name, '', 'Process')
    if (-not [Environment]::GetEnvironmentVariables('Process').Contains($name)) {
        throw 'empty process environment variable was not present before snapshot'
    }
    $snapshot = Get-LocalEnvironmentSnapshot -Names @($name)
    [Environment]::SetEnvironmentVariable($name, 'changed', 'Process')
    Restore-LocalEnvironmentSnapshot -Snapshot $snapshot
    $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
    if (-not $processEnvironment.Contains($name) -or [Environment]::GetEnvironmentVariable($name, 'Process') -ne '') {
        throw 'empty variable was not restored with presence and value intact'
    }

    [Environment]::SetEnvironmentVariable($name, 'sentinel', 'Process')
    $snapshot = Get-LocalEnvironmentSnapshot -Names @($name)
    [Environment]::SetEnvironmentVariable($name, 'changed', 'Process')
    Restore-LocalEnvironmentSnapshot -Snapshot $snapshot
    $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
    if (-not $processEnvironment.Contains($name) -or [Environment]::GetEnvironmentVariable($name, 'Process') -ne 'sentinel') {
        throw 'sentinel variable was not restored with presence and value intact'
    }
} finally {
    Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
}
`))
})

test('writes and reads an atomic process snapshot without starting a process', () => {
  runPowerShell(runtimeScript(`
$directory = Join-Path (Get-Location) ('.tmp/bluealbum-localruntime-' + [Guid]::NewGuid().ToString('N'))
$path = Join-Path $directory 'processes.json'
try {
    $entry = [pscustomobject]@{ Name = 'backend'; ProcessId = 2147483647; StartTime = [DateTime]::UtcNow.ToString('o') }
    Write-LocalProcessSnapshot -Path $path -Entries @($entry)
    $read = @(Read-LocalProcessSnapshot -Path $path)
    if ($read.Count -ne 1 -or $read[0].Name -ne 'backend' -or [int]$read[0].ProcessId -ne 2147483647) {
        throw 'process snapshot round trip failed'
    }
} finally {
    if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
}
`))
})

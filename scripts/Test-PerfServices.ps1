#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$RunId = ([Guid]::NewGuid().ToString('N').Substring(0, 10)),
    [string]$EnvironmentFile = 'perf/.env.perf'
)

$ErrorActionPreference = 'Stop'

$runtimeRoot = Split-Path -Parent $PSScriptRoot
$RunId = $RunId.Trim()
if ($RunId -notmatch '^[0-9a-fA-F]{10}$') {
    throw 'RunId must contain exactly ten hexadecimal characters'
}
$RunId = $RunId.ToLowerInvariant()

if (-not [IO.Path]::IsPathRooted($EnvironmentFile)) {
    $EnvironmentFile = Join-Path $runtimeRoot $EnvironmentFile
}
$EnvironmentFile = [IO.Path]::GetFullPath($EnvironmentFile)
$artifactDirectory = Join-Path $runtimeRoot "outputs/perf-services/$RunId"
$checksFile = Join-Path $artifactDirectory 'checks.json'
$overrideFile = Join-Path $artifactDirectory 'compose.override.yml'
$composeFile = Join-Path $runtimeRoot 'perf/docker-compose.perf.yml'
$project = "bluealbum-perf-check-$RunId"
$minimumFreeCommitKiB = [long](1.5GB / 1KB)
$metricAttempts = 30
$metricDelaySeconds = 2

New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

$checks = [System.Collections.Generic.List[object]]::new()
$environmentSnapshot = [ordered]@{}
$environmentKeys = @()
$executionSucceeded = $false
$cleanupSucceeded = $false
$environmentRestoreSucceeded = $true
$composeTouched = $false
$phase = 'preflight'
$succeeded = $false

function Add-Check([string]$Name, [bool]$Passed) {
    $checks.Add([ordered]@{ Name = $Name; Passed = [bool]$Passed })
    Write-Host ("{0}={1}" -f $Name, ([bool]$Passed).ToString().ToLowerInvariant())
}

function Assert-Check([string]$Name, [bool]$Passed) {
    Add-Check $Name $Passed
    if (-not $Passed) { throw "Check failed: $Name" }
}

function Save-Checks([bool]$Completed) {
    $json = ConvertTo-Json -InputObject ([ordered]@{
        RunId = $RunId
        Succeeded = [bool]$Completed
        Checks = @($checks.ToArray())
    }) -Depth 6
    [IO.File]::WriteAllText($checksFile, $json, [Text.UTF8Encoding]::new($false))
}

function Get-EnvironmentKeys([string]$Path) {
    $keys = [System.Collections.Generic.List[string]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($rawLine in (Get-Content -LiteralPath $Path -ErrorAction Stop)) {
        $line = ([string]$rawLine).Trim().TrimStart([char]0xFEFF)
        if (-not $line -or $line.StartsWith('#')) { continue }
        if ($line -match '^(?:export\s+)?(?<Key>[A-Za-z_][A-Za-z0-9_]*)\s*=') {
            if ($seen.Add($Matches.Key)) { $keys.Add($Matches.Key) }
        }
    }
    return $keys.ToArray()
}

function Invoke-NativeCapture([string]$FilePath, [string[]]$Arguments) {
    $lines = @(& $FilePath @Arguments 2>&1 | ForEach-Object { [string]$_ })
    [pscustomobject]@{
        ExitCode = [int]$LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

$composePrefix = @(
    'compose', '--project-directory', (Join-Path $runtimeRoot 'perf'), '-p', $project,
    '--env-file', $EnvironmentFile, '-f', $composeFile, '-f', $overrideFile
)

function Invoke-ComposeCapture([string[]]$Arguments) {
    return Invoke-NativeCapture 'docker' ($script:composePrefix + $Arguments)
}

function Get-ProjectIds([ValidateSet('containers', 'volumes')][string]$Kind) {
    if ($Kind -eq 'containers') {
        $result = Invoke-NativeCapture 'docker' @('ps', '-aq', '--filter', "label=com.docker.compose.project=$project")
    } else {
        $result = Invoke-NativeCapture 'docker' @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$project")
    }
    if ($result.ExitCode -ne 0) {
        return [pscustomobject]@{ Success = $false; Ids = @() }
    }
    $ids = @($result.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    return [pscustomobject]@{ Success = $true; Ids = $ids }
}

function Test-ProjectUnused {
    $containers = Get-ProjectIds containers
    $volumes = Get-ProjectIds volumes
    return ($containers.Success -and $volumes.Success -and $containers.Ids.Count -eq 0 -and $volumes.Ids.Count -eq 0)
}

function Get-FreeTcpPort([int]$Avoid = 0) {
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $candidate = Get-Random -Minimum 40000 -Maximum 60000
        if ($candidate -eq $Avoid) { continue }
        if (-not (Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue)) {
            return $candidate
        }
    }
    throw 'Unable to allocate a local metrics port'
}

function Test-MetricEndpoint([string]$Uri, [string]$Metric) {
    $metricPattern = "(?m)^\s*$([regex]::Escape($Metric))\s+1(?:\.0+)?(?:\s|$)"
    for ($attempt = 1; $attempt -le $metricAttempts; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -Method Get -TimeoutSec 5 -SkipHttpErrorCheck -ErrorAction Stop
            if ([int]$response.StatusCode -eq 200 -and ([string]$response.Content -match $metricPattern)) {
                return $true
            }
        } catch {
            # Keep response and transport details out of the console and checks.json.
        }
        if ($attempt -lt $metricAttempts) { Start-Sleep -Seconds $metricDelaySeconds }
    }
    return $false
}

function Test-NoPublishedPort([string]$Service, [string]$PrivatePort) {
    $idResult = Invoke-ComposeCapture @('ps', '-q', $Service)
    if ($idResult.ExitCode -ne 0) { return $false }
    $id = @($idResult.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ }) | Select-Object -First 1
    if (-not $id) { return $false }
    $inspect = Invoke-NativeCapture 'docker' @('inspect', $id, '--format', '{{json .NetworkSettings.Ports}}')
    if ($inspect.ExitCode -ne 0) { return $false }
    try {
        $ports = $inspect.Output.Trim() | ConvertFrom-Json
        $property = $ports.PSObject.Properties["$PrivatePort/tcp"]
        return ($null -eq $property -or $null -eq $property.Value -or @($property.Value).Count -eq 0)
    } catch {
        return $false
    }
}

try {
    Save-Checks $false

    Assert-Check 'Environment file exists' (Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)
    Assert-Check 'Compose file exists' (Test-Path -LiteralPath $composeFile -PathType Leaf)
    Assert-Check 'Docker command is available' ($null -ne (Get-Command docker -ErrorAction SilentlyContinue))

    $environmentKeys = @(Get-EnvironmentKeys $EnvironmentFile)
    $requiredKeys = @(
        'PERF_DB_PASSWORD', 'PERF_DB_ROOT_PASSWORD', 'PERF_MYSQL_EXPORTER_PASSWORD',
        'PERF_REDIS_PASSWORD', 'PERF_RABBITMQ_PASSWORD', 'PERF_GRAFANA_PASSWORD',
        'PERF_JWT_SECRET', 'PERF_TEST_PASSWORD'
    )
    $keySet = [System.Collections.Generic.HashSet[string]]::new([string[]]$environmentKeys, [StringComparer]::OrdinalIgnoreCase)
    $allRequiredKeysPresent = $true
    foreach ($key in $requiredKeys) {
        if (-not $keySet.Contains($key)) { $allRequiredKeysPresent = $false }
    }
    Assert-Check 'Private environment keys parsed' ($environmentKeys.Count -gt 0 -and $allRequiredKeysPresent)

    foreach ($key in $environmentKeys) {
        $environmentSnapshot[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue
    }

    $hostMemory = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    $freeCommitKiB = [long]$hostMemory.FreeVirtualMemory
    [IO.File]::WriteAllText(
        (Join-Path $artifactDirectory 'host-memory-before.json'),
        (ConvertTo-Json -InputObject ([ordered]@{
            FreeCommitKiB = $freeCommitKiB
            MinimumFreeCommitKiB = $minimumFreeCommitKiB
        }) -Depth 4),
        [Text.UTF8Encoding]::new($false)
    )
    Assert-Check 'Available commit memory is at least 1.5 GiB' ($freeCommitKiB -ge $minimumFreeCommitKiB)
    Assert-Check 'Compose project has no existing containers or volumes' (Test-ProjectUnused)

    $mysqlMetricPort = Get-FreeTcpPort
    $redisMetricPort = Get-FreeTcpPort $mysqlMetricPort
    $override = @"
services:
  mysql_perf:
    mem_limit: 512m
    cpus: 1.0
    command:
      - "--default-authentication-plugin=mysql_native_password"
      - "--character-set-server=utf8mb4"
      - "--collation-server=utf8mb4_unicode_ci"
      - "--innodb_buffer_pool_size=64M"
      - "--performance_schema=OFF"
    ports: !override []
  redis_perf:
    mem_limit: 64m
    ports: !override []
  mysqld_exporter:
    mem_limit: 64m
    ports: !override
      - "127.0.0.1:${mysqlMetricPort}:9104"
  redis_exporter:
    mem_limit: 64m
    ports: !override
      - "127.0.0.1:${redisMetricPort}:9121"
"@
    [IO.File]::WriteAllText($overrideFile, $override, [Text.UTF8Encoding]::new($false))
    Assert-Check 'Metrics ports allocated on loopback' ($mysqlMetricPort -ne $redisMetricPort)

    $phase = 'compose-config'
    $config = Invoke-ComposeCapture @('config', '--quiet')
    Assert-Check 'Dependency Compose configuration parses' ($config.ExitCode -eq 0)

    $phase = 'compose-up'
    $composeTouched = $true
    $up = Invoke-ComposeCapture @(
        'up', '-d', '--wait', '--wait-timeout', '180', '--no-build',
        'mysql_perf', 'redis_perf', 'mysqld_exporter', 'redis_exporter'
    )
    if ($up.ExitCode -ne 0) {
        $safeDiagnostic = $up.Output
        foreach ($line in (Get-Content -LiteralPath $EnvironmentFile)) {
            if ($line -match '^[A-Za-z_][A-Za-z0-9_]*=(.+)$') {
                $secret = $Matches[1].Trim().Trim('"', "'")
                if ($secret.Length -ge 8) { $safeDiagnostic = $safeDiagnostic.Replace($secret, '[REDACTED]') }
            }
        }
        [IO.File]::WriteAllText((Join-Path $artifactDirectory 'compose-up-error.log'), $safeDiagnostic)
    }
    Assert-Check 'Only dependency services start without source image build' ($up.ExitCode -eq 0)

    $servicesResult = Invoke-NativeCapture 'docker' @(
        'ps', '-a', '--filter', "label=com.docker.compose.project=$project",
        '--format', '{{.Label "com.docker.compose.service"}}'
    )
    $actualServices = @($servicesResult.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object -Unique)
    $expectedServices = @('mysql_perf', 'redis_perf', 'mysqld_exporter', 'redis_exporter')
    $onlyExpectedServices = $servicesResult.ExitCode -eq 0 -and $actualServices.Count -eq $expectedServices.Count
    foreach ($service in $expectedServices) {
        if ($service -notin $actualServices) { $onlyExpectedServices = $false }
    }
    Assert-Check 'Project contains only the four dependency services' $onlyExpectedServices
    Assert-Check 'Database host ports are unpublished' (
        (Test-NoPublishedPort 'mysql_perf' '3306') -and (Test-NoPublishedPort 'redis_perf' '6379')
    )

    $phase = 'redis-authentication'
    $redisNoAuth = Invoke-ComposeCapture @('exec', '-T', 'redis_perf', 'redis-cli', '-e', '-h', '127.0.0.1', 'ping')
    Assert-Check 'Redis rejects unauthenticated ping with NOAUTH' (
        $redisNoAuth.ExitCode -ne 0 -and $redisNoAuth.Output -match '(?i)NOAUTH'
    )
    $redisAuth = Invoke-ComposeCapture @(
        'exec', '-T', 'redis_perf', 'sh', '-c',
        'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli -h 127.0.0.1 ping'
    )
    Assert-Check 'Redis authenticated ping returns PONG' (
        $redisAuth.ExitCode -eq 0 -and $redisAuth.Output.Trim() -match '^PONG$'
    )

    $mysqlExporterAuth = Invoke-ComposeCapture @(
        'exec', '-T', 'mysql_perf', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_EXPORTER_PASSWORD" mysql -h 127.0.0.1 -u exporter -Nse ''SELECT 1'''
    )
    Assert-Check 'MySQL exporter account can SELECT 1' (
        $mysqlExporterAuth.ExitCode -eq 0 -and $mysqlExporterAuth.Output.Trim() -match '(?m)^1$'
    )

    $phase = 'exporter-metrics'
    Assert-Check 'MySQL exporter reports mysql_up 1' (
        (Test-MetricEndpoint "http://127.0.0.1:${mysqlMetricPort}/metrics" 'mysql_up')
    )
    Assert-Check 'Redis exporter reports redis_up 1' (
        (Test-MetricEndpoint "http://127.0.0.1:${redisMetricPort}/metrics" 'redis_up')
    )
    Add-Check 'Dependency service checks completed' $true
    $executionSucceeded = $true
} catch {
    $failureType = if ($null -ne $_.Exception) { $_.Exception.GetType().Name } else { 'UnknownException' }
    Add-Check ("Failure:{0}:{1}" -f $phase, $failureType) $false
    Add-Check 'Dependency service checks completed' $false
} finally {
    if ($composeTouched) {
        try {
            $down = Invoke-ComposeCapture @('down', '--volumes', '--remove-orphans')
            $remainingContainers = Get-ProjectIds containers
            $remainingVolumes = Get-ProjectIds volumes
            $cleanupSucceeded = (
                $down.ExitCode -eq 0 -and $remainingContainers.Success -and $remainingVolumes.Success -and
                $remainingContainers.Ids.Count -eq 0 -and $remainingVolumes.Ids.Count -eq 0
            )
        } catch {
            $cleanupSucceeded = $false
        }
        Add-Check 'Cleanup removes all project containers and volumes' $cleanupSucceeded
    } else {
        # A pre-existing project is outside this run's ownership boundary.
        $cleanupSucceeded = $true
        Add-Check 'Cleanup skipped before project ownership' $true
    }

    try {
        foreach ($entry in $environmentSnapshot.GetEnumerator()) {
            if ($null -eq $entry.Value) {
                Remove-Item -LiteralPath "Env:$($entry.Key)" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
            }
        }
    } catch {
        $environmentRestoreSucceeded = $false
    }
    Add-Check 'Inherited environment variables are restored' $environmentRestoreSucceeded

    $allChecksPassed = @($checks | Where-Object { -not $_.Passed }).Count -eq 0
    $succeeded = [bool]($executionSucceeded -and $cleanupSucceeded -and $environmentRestoreSucceeded -and $allChecksPassed)
    Save-Checks $succeeded
}

if (-not $succeeded) {
    throw 'Perf dependency service verification failed'
}

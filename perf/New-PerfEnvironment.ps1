#Requires -Version 7.4
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '.env.perf'
$keys = @('PERF_DB_PASSWORD', 'PERF_DB_ROOT_PASSWORD', 'PERF_MYSQL_EXPORTER_PASSWORD',
    'PERF_REDIS_PASSWORD', 'PERF_RABBITMQ_PASSWORD', 'PERF_GRAFANA_PASSWORD', 'PERF_JWT_SECRET')
$lines = foreach ($key in $keys) {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    "$key=$([Convert]::ToHexString($bytes).ToLowerInvariant())"
}
$bytes = [byte[]]::new(32)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$lines += "PERF_TEST_PASSWORD=Aa1!$([Convert]::ToHexString($bytes).ToLowerInvariant())"
# CreateNew protects an existing environment from accidental credential rotation.
$stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
    try { $writer.Write(($lines -join "`n") + "`n") } finally { $writer.Dispose() }
} finally { $stream.Dispose() }
Write-Host 'Created perf/.env.perf with independent random credentials; keep this file private.'

[CmdletBinding()]
param([switch]$KeepDependencies)

$ErrorActionPreference = 'Stop'
$blueAlbumRoot = Split-Path -Parent $PSScriptRoot
$blueAlbumRunDir = Join-Path $blueAlbumRoot 'test-artifacts/local-runtime'
$blueAlbumStateFile = Join-Path $blueAlbumRunDir 'processes.json'
if (Test-Path $blueAlbumStateFile) {
    $state = Get-Content -LiteralPath $blueAlbumStateFile -Raw | ConvertFrom-Json
    foreach ($entry in $state) {
        $process = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.StartTime).ToUniversalTime().Ticks) {
            $commandLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $($entry.ProcessId)").CommandLine
            if ($commandLine -and $commandLine.Contains($blueAlbumRoot)) {
                Stop-Process -Id $entry.ProcessId
                Write-Host "Stopped $($entry.Name)."
            } else {
                throw "Process $($entry.ProcessId) does not match this workspace; left running."
            }
        }
    }
}
$envFile = Join-Path $blueAlbumRunDir 'runtime.env'
if ((Test-Path $envFile) -and -not $KeepDependencies) {
    Push-Location $blueAlbumRoot
    try {
        & docker compose --env-file $envFile -f docker-compose.local.yml stop
        if ($LASTEXITCODE -ne 0) { throw 'Local dependency shutdown failed.' }
    } finally { Pop-Location }
}
Write-Host 'Local run stopped. Database volumes and local credentials were retained.'

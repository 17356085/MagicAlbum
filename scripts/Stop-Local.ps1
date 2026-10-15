[CmdletBinding()]
param([switch]$KeepDependencies)

$ErrorActionPreference = 'Stop'
$blueAlbumRoot = Split-Path -Parent $PSScriptRoot
$blueAlbumRunDir = Join-Path $blueAlbumRoot 'test-artifacts/local-runtime'
$blueAlbumStateFile = Join-Path $blueAlbumRunDir 'processes.json'
$blueAlbumEnvFile = Join-Path $blueAlbumRunDir 'runtime.env'
$blueAlbumComposeFile = Join-Path $blueAlbumRoot 'docker-compose.local.yml'
Import-Module (Join-Path $PSScriptRoot 'lib/LocalRuntime.psm1') -Force

$errors = [System.Collections.Generic.List[string]]::new()
$state = @()
try {
    $state = @(Read-LocalProcessSnapshot -Path $blueAlbumStateFile)
} catch {
    $null = $errors.Add('managed process snapshot is invalid')
}

for ($index = $state.Count - 1; $index -ge 0; $index--) {
    $entry = $state[$index]
    $result = Stop-LocalManagedProcess -Entry $entry -WorkspaceRoot $blueAlbumRoot
    if ($result.State -eq 'stopped') {
        Write-Host "Stopped $($result.Name)."
    } elseif ($result.State -in @('mismatch', 'failed')) {
        $null = $errors.Add("$($result.Name): $($result.Error)")
    }
}

if (-not $KeepDependencies) {
    if (-not (Test-Path -LiteralPath $blueAlbumEnvFile -PathType Leaf)) {
        $null = $errors.Add('runtime.env is missing; dependency containers were not stopped')
    } else {
        Push-Location $blueAlbumRoot
        try {
            & docker compose --env-file $blueAlbumEnvFile -f $blueAlbumComposeFile stop
            if ($LASTEXITCODE -ne 0) {
                $null = $errors.Add('local dependency shutdown failed')
            }
        } catch {
            $null = $errors.Add('local dependency shutdown could not be started')
        } finally {
            Pop-Location
        }
    }
}

if ($errors.Count -gt 0) {
    throw "Local stop incomplete: $($errors -join '; '). Review the managed process snapshot and retry."
}

if (Test-Path -LiteralPath $blueAlbumStateFile -PathType Leaf) {
    try {
        Remove-Item -LiteralPath $blueAlbumStateFile -Force -ErrorAction Stop
    } catch {
        throw 'Local applications stopped, but the managed process snapshot could not be removed.'
    }
}

if ($KeepDependencies) {
    Write-Host 'Local applications stopped. Dependency containers and local credentials were retained.'
} else {
    Write-Host 'Local run stopped. Database volumes and local credentials were retained.'
}

Set-StrictMode -Version 3.0

$script:RuntimePngFixtureBase64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII='

function Get-RuntimeBrowserTempEnvironment {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Directory, [bool]$WindowsHost = $IsWindows)
    # Linux Chromium's temporary singleton socket must retain a short native path.
    if (-not $WindowsHost) { return @{} }
    return @{ TEMP = $Directory; TMP = $Directory; TMPDIR = $Directory }
}

function Get-RuntimeDefaultPythonExecutable {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepositoryRoot
    )

    $relativePath = if ($IsWindows) { 'tests/.venv/Scripts/python.exe' } else { 'tests/.venv/bin/python' }
    return (Join-Path $RepositoryRoot $relativePath)
}

function Test-RuntimeTcpPortAvailable {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidateRange(1, 65535)]
        [int]$Port
    )

    $listener = $null
    try {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
        $listener.ExclusiveAddressUse = $true
        $listener.Start()
        return $true
    } catch [Net.Sockets.SocketException] {
        return $false
    } catch {
        return $false
    } finally {
        if ($null -ne $listener) {
            $listener.Stop()
        }
    }
}

function Assert-RuntimePortsAvailable {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [int[]]$Ports
    )

    foreach ($port in $Ports) {
        if (-not (Test-RuntimeTcpPortAvailable -Port $port)) {
            throw "Verification port $port is occupied"
        }
    }
}

function Read-RuntimeProcMemInfo {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Linux memory information file is missing: $Path"
    }

    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*(?<Name>[A-Za-z_]+):\s+(?<Value>[0-9]+)\s+kB\s*$') {
            $values[$Matches.Name] = [long]$Matches.Value
        }
    }
    foreach ($name in @('MemAvailable', 'SwapFree')) {
        if (-not $values.ContainsKey($name)) {
            throw "Linux memory information is missing $name"
        }
    }
    return $values
}

function Get-RuntimeLinuxHostMemorySnapshot {
    [CmdletBinding()]
    param(
        [string]$Path = '/proc/meminfo'
    )

    $values = Read-RuntimeProcMemInfo -Path $Path
    $memAvailableKiB = [long]$values['MemAvailable']
    $swapFreeKiB = [long]$values['SwapFree']
    return [pscustomobject]@{
        Platform = 'Linux'
        MemoryMetric = 'MemAvailablePlusSwapFreeKiB'
        MemoryMetricKiB = $memAvailableKiB + $swapFreeKiB
        FreePhysicalKiB = $memAvailableKiB
        FreeCommitKiB = $null
        TotalCommitKiB = $null
        MemAvailableKiB = $memAvailableKiB
        SwapFreeKiB = $swapFreeKiB
    }
}

function Get-RuntimeHostMemorySnapshot {
    [CmdletBinding()]
    param(
        [string]$LinuxMemInfoPath = '/proc/meminfo'
    )

    if ($IsWindows) {
        $hostMemory = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
        $freeCommitKiB = [long]$hostMemory.FreeVirtualMemory
        return [pscustomobject]@{
            Platform = 'Windows'
            MemoryMetric = 'FreeCommitKiB'
            MemoryMetricKiB = $freeCommitKiB
            FreePhysicalKiB = [long]$hostMemory.FreePhysicalMemory
            FreeCommitKiB = $freeCommitKiB
            TotalCommitKiB = [long]$hostMemory.TotalVirtualMemorySize
            MemAvailableKiB = $null
            SwapFreeKiB = $null
        }
    }
    if ($IsLinux) {
        return Get-RuntimeLinuxHostMemorySnapshot -Path $LinuxMemInfoPath
    }
    throw 'Host memory preflight supports Windows CIM or Linux /proc/meminfo only.'
}

function Test-RuntimeHostMemoryThreshold {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$Snapshot,
        [Parameter(Mandatory = $true)]
        [long]$MinimumKiB
    )

    return [long]$Snapshot.MemoryMetricKiB -ge $MinimumKiB
}

function Get-RuntimePngFixtureBytes {
    [CmdletBinding()]
    param()

    return [byte[]][Convert]::FromBase64String($script:RuntimePngFixtureBase64)
}

Export-ModuleMember -Function @(
    'Get-RuntimeBrowserTempEnvironment',
    'Get-RuntimeDefaultPythonExecutable',
    'Test-RuntimeTcpPortAvailable',
    'Assert-RuntimePortsAvailable',
    'Read-RuntimeProcMemInfo',
    'Get-RuntimeLinuxHostMemorySnapshot',
    'Get-RuntimeHostMemorySnapshot',
    'Test-RuntimeHostMemoryThreshold',
    'Get-RuntimePngFixtureBytes'
)

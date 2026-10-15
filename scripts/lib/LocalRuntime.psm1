Set-StrictMode -Version Latest

$script:LocalRuntimeRequiredKeys = @(
    'SPRING_PROFILES_ACTIVE',
    'DB_HOST',
    'DB_PORT',
    'DB_NAME',
    'DB_USER',
    'DB_PASSWORD',
    'DB_ROOT_PASSWORD',
    'NOTIFICATION_DB_NAME',
    'REDIS_HOST',
    'REDIS_PORT',
    'REDIS_PASSWORD',
    'SPRING_RABBITMQ_HOST',
    'SPRING_RABBITMQ_PORT',
    'SPRING_RABBITMQ_USERNAME',
    'SPRING_RABBITMQ_PASSWORD',
    'RABBITMQ_MANAGEMENT_PORT',
    'APP_RABBIT_ENABLED',
    'JWT_ISSUER',
    'JWT_SECRET',
    'INTERNAL_SERVICE_TOKEN',
    'NOTIFICATION_SERVICE_MODE',
    'NOTIFICATION_SERVICE_BASE_URL',
    'APP_AUTH_VERIFY_MODE',
    'AI_PROVIDER',
    'VITE_AUTH_VERIFY_MODE',
    'VITE_API_BASE_URL',
    'VITE_USE_API_MOCK',
    'VITE_AUTH_OAUTH_GITHUB_ENABLED',
    'VITE_AUTH_OAUTH_GOOGLE_ENABLED',
    'VITE_AUTH_OAUTH_APPLE_ENABLED',
    'VITE_AUTH_OAUTH_WECHAT_ENABLED'
)

function Get-LocalRuntimeRequiredKeys {
    return @($script:LocalRuntimeRequiredKeys)
}

function Read-LocalRuntimeEnvironment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Local runtime environment file is missing: $Path"
    }

    $values = [ordered]@{}
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $lineNumber++
        if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) {
            continue
        }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') {
            throw "Invalid runtime.env syntax at line $lineNumber."
        }
        $key = $Matches[1]
        if ($values.Contains($key)) {
            throw "Duplicate runtime.env key '$key'."
        }
        $values[$key] = $Matches[2]
    }
    return $values
}

function Add-LocalRuntimeCompatibilityDefaults {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [System.Collections.IDictionary]$Values
    )

    if (-not $Values.Contains('RABBITMQ_MANAGEMENT_PORT')) {
        $Values['RABBITMQ_MANAGEMENT_PORT'] = '25673'
    }
    if (-not $Values.Contains('VITE_AUTH_VERIFY_MODE')) {
        $Values['VITE_AUTH_VERIFY_MODE'] = 'off'
    }
    return $Values
}

function Test-LocalIdentifier {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [string]$Value
    )

    return $null -ne $Value -and $Value -match '^[A-Za-z0-9_]{1,64}$'
}

function Test-LocalCredential {
    [CmdletBinding()]
    param(
        [AllowNull()]
        [string]$Value,
        [int]$MinimumLength = 8,
        [switch]$RequireStrong
    )

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Trim() -ne $Value) {
        return $false
    }
    if ($Value.Length -lt $MinimumLength -or $Value.Length -gt 256) {
        return $false
    }
    if ($Value -match '[^\x21-\x7e]') {
        return $false
    }

    $lower = $Value.ToLowerInvariant()
    foreach ($marker in @(
            'change-me', 'change_me', 'changeme', 'replace-me', 'replace_me',
            'replaceme', 'replace-with', 'replace_with', 'placeholder',
            'devsecret', 'your_secret', 'your-secret', 'password', 'secret', 'token'
        )) {
        if ($lower.Contains($marker)) {
            return $false
        }
    }

    if ($RequireStrong -and @($Value.ToCharArray() | Select-Object -Unique).Count -lt 8) {
        return $false
    }
    return $true
}

function ConvertTo-LocalPort {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [System.Collections.IDictionary]$Values,
        [Parameter(Mandatory)]
        [string]$Key
    )

    $raw = [string]$Values[$Key]
    $port = 0
    $parsed = $raw -match '^[0-9]{1,5}$' -and [int]::TryParse($raw, [Globalization.NumberStyles]::None, [Globalization.CultureInfo]::InvariantCulture, [ref]$port)
    if (-not $parsed -or $port -lt 1 -or $port -gt 65535) {
        throw "runtime.env key '$Key' must be a TCP port between 1 and 65535."
    }
    return $port
}

function Get-LocalPortConfiguration {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [System.Collections.IDictionary]$Values
    )

    $ports = [ordered]@{
        MySql = ConvertTo-LocalPort -Values $Values -Key 'DB_PORT'
        Redis = ConvertTo-LocalPort -Values $Values -Key 'REDIS_PORT'
        RabbitMq = ConvertTo-LocalPort -Values $Values -Key 'SPRING_RABBITMQ_PORT'
        RabbitMqManagement = ConvertTo-LocalPort -Values $Values -Key 'RABBITMQ_MANAGEMENT_PORT'
        Backend = 8080
        Notification = 8081
        Gateway = 9000
        Frontend = 5173
    }

    $duplicates = @($ports.GetEnumerator() | Group-Object Value | Where-Object Count -gt 1)
    if ($duplicates.Count -gt 0) {
        throw 'runtime.env contains duplicate local host ports.'
    }
    return $ports
}

function Assert-LocalRuntimeConfiguration {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [System.Collections.IDictionary]$Values
    )

    $missing = @($script:LocalRuntimeRequiredKeys | Where-Object {
            -not $Values.Contains($_) -or [string]::IsNullOrWhiteSpace([string]$Values[$_])
        })
    if ($missing.Count -gt 0) {
        throw "runtime.env is missing required key(s): $($missing -join ', ')."
    }

    foreach ($key in @('DB_NAME', 'NOTIFICATION_DB_NAME', 'DB_USER')) {
        if (-not (Test-LocalIdentifier -Value ([string]$Values[$key]))) {
            throw "runtime.env key '$key' must be an identifier using only A-Za-z0-9_ and at most 64 characters."
        }
    }
    if ($Values['DB_NAME'] -eq $Values['NOTIFICATION_DB_NAME']) {
        throw 'DB_NAME and NOTIFICATION_DB_NAME must identify separate databases.'
    }

    foreach ($key in @('DB_PASSWORD', 'DB_ROOT_PASSWORD', 'REDIS_PASSWORD', 'SPRING_RABBITMQ_PASSWORD')) {
        if (-not (Test-LocalCredential -Value ([string]$Values[$key]))) {
            throw "runtime.env credential '$key' is missing or obviously invalid."
        }
    }
    foreach ($key in @('JWT_SECRET', 'INTERNAL_SERVICE_TOKEN')) {
        if (-not (Test-LocalCredential -Value ([string]$Values[$key]) -MinimumLength 32 -RequireStrong)) {
            throw "runtime.env credential '$key' is missing or obviously invalid."
        }
    }

    foreach ($key in @('DB_HOST', 'REDIS_HOST', 'SPRING_RABBITMQ_HOST')) {
        if ([string]$Values[$key] -notin @('localhost', '127.0.0.1')) {
            throw "runtime.env key '$key' must use localhost or 127.0.0.1 for isolated local startup."
        }
    }

    if ($Values['SPRING_PROFILES_ACTIVE'] -ne 'local') {
        throw 'SPRING_PROFILES_ACTIVE must be local for scripts/Start-Local.ps1.'
    }
    if ($Values['NOTIFICATION_SERVICE_MODE'] -ne 'remote') {
        throw 'NOTIFICATION_SERVICE_MODE must be remote for reliable local notification delivery.'
    }
    try {
        $notificationUrl = [Uri]$Values['NOTIFICATION_SERVICE_BASE_URL']
    } catch {
        throw 'NOTIFICATION_SERVICE_BASE_URL must be a valid HTTP URL on the local notification port.'
    }
    if ($notificationUrl.Scheme -ne 'http' -or
        -not $notificationUrl.IsLoopback -or
        $notificationUrl.Host -notin @('localhost', '127.0.0.1') -or
        $notificationUrl.Port -ne 8081 -or
        -not [string]::IsNullOrEmpty($notificationUrl.UserInfo) -or
        $notificationUrl.AbsolutePath -notin @('', '/') -or
        -not [string]::IsNullOrEmpty($notificationUrl.Query) -or
        -not [string]::IsNullOrEmpty($notificationUrl.Fragment)) {
        throw 'NOTIFICATION_SERVICE_BASE_URL must be an HTTP loopback URL on port 8081 with no userinfo or path override.'
    }

    $null = Get-LocalPortConfiguration -Values $Values
}

function Get-LocalEnvironmentNamesToIsolate {
    [CmdletBinding()]
    param()

    $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($key in [Environment]::GetEnvironmentVariables('Process').Keys) {
        $name = [string]$key
        if ($name -match '^(NOTIFICATION_|SPRING_DATASOURCE_|SPRING_FLYWAY_|SPRING_CONFIG_|APP_SECURITY_|SPRING_PROFILES_|APP_NOTIFICATIONS_|APP_ADMIN_)' -or $name -in @(
                'SERVER_PORT', 'SERVER_ADDRESS', 'SPRING_APPLICATION_JSON',
                'GATEWAY_PORT', 'MANAGEMENT_PORT', 'PORT', 'VITE_PORT',
                'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'
            )) {
            $null = $names.Add($name)
        }
    }
    return @($names)
}

function Get-LocalEnvironmentSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string[]]$Names
    )

    $processEnvironment = [Environment]::GetEnvironmentVariables('Process')
    $snapshot = [ordered]@{}
    foreach ($name in $Names | Select-Object -Unique) {
        $exists = $processEnvironment.Contains($name)
        $snapshot[$name] = [pscustomobject]@{
            Exists = $exists
            Value = if ($exists) { [string]$processEnvironment[$name] } else { $null }
        }
    }
    return $snapshot
}

function Restore-LocalEnvironmentSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [System.Collections.IDictionary]$Snapshot
    )

    foreach ($entry in $Snapshot.GetEnumerator()) {
        if ($null -eq $entry.Value -or -not [bool]$entry.Value.Exists) {
            Remove-Item -LiteralPath "Env:$($entry.Key)" -ErrorAction SilentlyContinue
            continue
        }
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value.Value, 'Process')
    }
}

function Write-LocalProcessSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [object[]]$Entries
    )

    $directory = Split-Path -Parent $Path
    if ($directory) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $temporaryPath = "$Path.$([Guid]::NewGuid().ToString('N')).tmp"
    $encoding = New-Object Text.UTF8Encoding($false)
    try {
        $json = ConvertTo-Json -InputObject ([object[]]$Entries) -Depth 5
        [IO.File]::WriteAllText($temporaryPath, $json, $encoding)
        [IO.File]::Move($temporaryPath, $Path, $true)
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Read-LocalProcessSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return @()
    }
    try {
        $parsed = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
        $entries = @($parsed)
        foreach ($entry in $entries) {
            if ($null -eq $entry -or $null -eq $entry.ProcessId -or $null -eq $entry.StartTime -or [string]::IsNullOrWhiteSpace([string]$entry.Name)) {
                throw 'missing process snapshot fields'
            }
            $null = [int]$entry.ProcessId
            $null = [DateTime]$entry.StartTime
        }
        return $entries
    } catch {
        throw 'Managed process snapshot is invalid; local processes were not assumed safe to stop.'
    }
}

function Get-LocalProcessCommandLine {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [int]$ProcessId
    )

    try {
        return (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop).CommandLine
    } catch {
        return $null
    }
}

function Stop-LocalManagedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [object]$Entry,
        [Parameter(Mandatory)]
        [string]$WorkspaceRoot,
        [int]$TimeoutSeconds = 15
    )

    $result = [ordered]@{ Name = [string]$Entry.Name; State = 'missing'; Error = $null }
    $process = Get-Process -Id ([int]$Entry.ProcessId) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return [pscustomobject]$result
    }
    try {
        $actualStart = $process.StartTime.ToUniversalTime().Ticks
        $expectedStart = ([DateTime]$Entry.StartTime).ToUniversalTime().Ticks
        if ($actualStart -ne $expectedStart) {
            $result.State = 'mismatch'
            $result.Error = 'process start time does not match'
            return [pscustomobject]$result
        }
    } catch {
        $result.State = 'mismatch'
        $result.Error = 'process start time could not be verified'
        return [pscustomobject]$result
    }

    $commandLine = Get-LocalProcessCommandLine -ProcessId ([int]$Entry.ProcessId)
    if ([string]::IsNullOrWhiteSpace($commandLine) -or -not $commandLine.Contains($WorkspaceRoot, [StringComparison]::OrdinalIgnoreCase)) {
        $result.State = 'mismatch'
        $result.Error = 'process command line does not match this workspace'
        return [pscustomobject]$result
    }

    try {
        Stop-Process -Id ([int]$Entry.ProcessId) -ErrorAction Stop
    } catch {
        $result.State = 'failed'
        $result.Error = 'process termination failed'
        return [pscustomobject]$result
    }

    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Process -Id ([int]$Entry.ProcessId) -ErrorAction SilentlyContinue)) {
            $result.State = 'stopped'
            return [pscustomobject]$result
        }
        Start-Sleep -Milliseconds 250
    }
    $result.State = 'failed'
    $result.Error = 'process did not exit before the bounded wait expired'
    return [pscustomobject]$result
}

Export-ModuleMember -Function @(
    'Get-LocalRuntimeRequiredKeys',
    'Read-LocalRuntimeEnvironment',
    'Add-LocalRuntimeCompatibilityDefaults',
    'Test-LocalIdentifier',
    'Test-LocalCredential',
    'Get-LocalPortConfiguration',
    'Assert-LocalRuntimeConfiguration',
    'Get-LocalEnvironmentNamesToIsolate',
    'Get-LocalEnvironmentSnapshot',
    'Restore-LocalEnvironmentSnapshot',
    'Write-LocalProcessSnapshot',
    'Read-LocalProcessSnapshot',
    'Get-LocalProcessCommandLine',
    'Stop-LocalManagedProcess'
)

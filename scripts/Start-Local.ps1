[CmdletBinding()]
param(
    [string]$JavaHome,
    [switch]$NoBuild,
    [switch]$DependenciesOnly
)

$ErrorActionPreference = 'Stop'
$blueAlbumRoot = Split-Path -Parent $PSScriptRoot
$blueAlbumRunDir = Join-Path $blueAlbumRoot 'test-artifacts/local-runtime'
$blueAlbumEnvFile = Join-Path $blueAlbumRunDir 'runtime.env'
$blueAlbumStateFile = Join-Path $blueAlbumRunDir 'processes.json'
$blueAlbumComposeFile = Join-Path $blueAlbumRoot 'docker-compose.local.yml'
Import-Module (Join-Path $PSScriptRoot 'lib/LocalRuntime.psm1') -Force
New-Item -ItemType Directory -Path $blueAlbumRunDir -Force | Out-Null

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Wait-LocalHealth([string]$Name, [string]$Url, [int]$ProcessId, [switch]$Actuator, [int]$TimeoutSeconds = 150) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "$Name exited. See $blueAlbumRunDir/$Name.err.log and $Name.log."
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) {
                if (-not $Actuator) { return }
                try {
                    # Windows PowerShell 7 returns Content as byte[] when the response
                    # has no charset, so decode before parsing instead of failing silently.
                    $raw = $response.Content
                    if ($raw -is [byte[]]) { $raw = [System.Text.Encoding]::UTF8.GetString($raw) }
                    $health = ConvertFrom-Json -InputObject ([string]$raw)
                    if ($health.status -eq 'UP') { return }
                } catch { }
            }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not become healthy. See $blueAlbumRunDir/$Name.log."
}

if (-not $JavaHome) {
    $JavaHome = $env:JAVA_HOME
    if (-not $JavaHome -and (Test-Path 'C:/Program Files/Java/jdk-22/bin/java.exe')) {
        $JavaHome = 'C:/Program Files/Java/jdk-22'
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Provide a working JDK 21 or newer with -JavaHome.'
}
$blueAlbumJava = Join-Path $JavaHome 'bin/java.exe'
Get-Command docker,mvn,node -ErrorAction Stop | Out-Null

if (-not (Test-Path $blueAlbumEnvFile)) {
    $settings = @(
        'COMPOSE_PROJECT_NAME=insync-local',
        'SPRING_PROFILES_ACTIVE=local',
        'DB_HOST=127.0.0.1', 'DB_PORT=13306', 'DB_NAME=insync_local', 'DB_USER=insync',
        "DB_PASSWORD=$(New-LocalSecret)", "DB_ROOT_PASSWORD=$(New-LocalSecret)",
        'NOTIFICATION_DB_NAME=insync_local_notifications',
        'NOTIFICATION_DB_USER=insync_notify', "NOTIFICATION_DB_PASSWORD=$(New-LocalSecret)",
        'NOTIFICATION_DB_MIGRATION_USER=insync_notify_ddl', "NOTIFICATION_DB_MIGRATION_PASSWORD=$(New-LocalSecret)",
        'REDIS_HOST=127.0.0.1', 'REDIS_PORT=16379', "REDIS_PASSWORD=$(New-LocalSecret)",
        'SPRING_RABBITMQ_HOST=127.0.0.1', 'SPRING_RABBITMQ_PORT=15673',
        'SPRING_RABBITMQ_USERNAME=insync', "SPRING_RABBITMQ_PASSWORD=$(New-LocalSecret)",
        'RABBITMQ_MANAGEMENT_PORT=25673',
        'APP_RABBIT_ENABLED=true', 'JWT_ISSUER=MagicAlbum', "JWT_SECRET=$(New-LocalSecret)",
        "INTERNAL_SERVICE_TOKEN=$(New-LocalSecret)",
        'NOTIFICATION_SERVICE_MODE=remote', 'NOTIFICATION_SERVICE_BASE_URL=http://127.0.0.1:8081',
        'APP_AUTH_VERIFY_MODE=off', 'AI_PROVIDER=mock', 'VITE_AUTH_VERIFY_MODE=off',
        'VITE_API_BASE_URL=/api/v1', 'VITE_USE_API_MOCK=false',
        'VITE_AUTH_OAUTH_GITHUB_ENABLED=false', 'VITE_AUTH_OAUTH_GOOGLE_ENABLED=false',
        'VITE_AUTH_OAUTH_APPLE_ENABLED=false', 'VITE_AUTH_OAUTH_WECHAT_ENABLED=false'
    )
    [IO.File]::WriteAllLines($blueAlbumEnvFile, $settings, (New-Object Text.UTF8Encoding $false))
}

$runtimeValues = Read-LocalRuntimeEnvironment -Path $blueAlbumEnvFile
$runtimeValues = Add-LocalRuntimeCompatibilityDefaults -Values $runtimeValues
Assert-LocalRuntimeConfiguration -Values $runtimeValues
$portConfiguration = Get-LocalPortConfiguration -Values $runtimeValues

if (Test-Path $blueAlbumStateFile) {
    $oldState = Read-LocalProcessSnapshot -Path $blueAlbumStateFile
    foreach ($entry in @($oldState)) {
        $running = Get-Process -Id ([int]$entry.ProcessId) -ErrorAction SilentlyContinue
        if ($running) {
            try {
                $sameStart = $running.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.StartTime).ToUniversalTime().Ticks
            } catch {
                throw 'A managed local process could not be checked safely; run scripts/Stop-Local.ps1 manually.'
            }
            if ($sameStart) {
                throw 'A managed local run is active. Run scripts/Stop-Local.ps1 before restarting.'
            }
        }
    }
}

# Only application ports are preflighted here. Compose owns dependency-container
# reuse; it reports a dependency-port conflict without stopping an existing resource.
$portsToCheck = @(
    [pscustomobject]@{ Name = 'backend'; Port = $portConfiguration.Backend },
    [pscustomobject]@{ Name = 'notification-service'; Port = $portConfiguration.Notification },
    [pscustomobject]@{ Name = 'gateway'; Port = $portConfiguration.Gateway },
    [pscustomobject]@{ Name = 'frontend'; Port = $portConfiguration.Frontend }
)
foreach ($port in $portsToCheck) {
    $listener = Get-NetTCPConnection -LocalPort $port.Port -State Listen -ErrorAction SilentlyContinue
    if ($listener) {
        throw "Port $($port.Port) for $($port.Name) is already in use. No existing process was stopped."
    }
}

$isolatedNames = @(Get-LocalEnvironmentNamesToIsolate)
$environmentNames = @($isolatedNames + @($runtimeValues.Keys) + @('JAVA_HOME', 'Path')) | Select-Object -Unique
$environmentSnapshot = Get-LocalEnvironmentSnapshot -Names $environmentNames
$started = [System.Collections.Generic.List[object]]::new()

try {
    foreach ($name in $isolatedNames) {
        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
    foreach ($entry in $runtimeValues.GetEnumerator()) {
        if ($entry.Key -eq 'CHAT_INTERNAL_SERVICE_TOKEN') { continue }
        if ($entry.Key -in @('NOTIFICATION_DB_USER', 'NOTIFICATION_DB_PASSWORD') -and $entry.Value -eq '') {
            continue
        }
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'Process')
    [Environment]::SetEnvironmentVariable('Path', "$(Join-Path $JavaHome 'bin');$([Environment]::GetEnvironmentVariable('Path', 'Process'))", 'Process')

    Push-Location $blueAlbumRoot
    try {
        & docker info --format '{{.ServerVersion}}'
        if ($LASTEXITCODE -ne 0) { throw 'The current shell could not access the Docker engine. See the docker info error above.' }

        & docker compose --env-file $blueAlbumEnvFile -f $blueAlbumComposeFile up -d --wait --wait-timeout 180
        if ($LASTEXITCODE -ne 0) { throw 'Local dependency startup failed.' }

        & docker compose --env-file $blueAlbumEnvFile -f $blueAlbumComposeFile exec -T mysql sh /usr/local/bin/bluealbum-init-notification-db.sh
        if ($LASTEXITCODE -ne 0) { throw 'Notification database initialization failed.' }

        if ($DependenciesOnly) {
            Write-Host 'Isolated MySQL, Redis and RabbitMQ dependencies are healthy and were retained.'
            return
        }
        $databaseEnvironmentNames = @(
            'DB_USER', 'DB_PASSWORD', 'DB_ROOT_PASSWORD',
            'NOTIFICATION_DB_USER', 'NOTIFICATION_DB_PASSWORD',
            'NOTIFICATION_DB_MIGRATION_USER', 'NOTIFICATION_DB_MIGRATION_PASSWORD'
        )
        foreach ($name in $databaseEnvironmentNames) {
            Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
        }
        if (-not $NoBuild) {
            Write-Host 'Building all Java modules and running their tests...'
            & mvn -B package *> (Join-Path $blueAlbumRunDir 'build.log')
            if ($LASTEXITCODE -ne 0) { throw "Java build failed. See $blueAlbumRunDir/build.log." }
        }

        # V3 must complete before backend starts and can run V20261014 or dispatch outbox rows.
        $applications = @(
            @{ Name = 'notification-service'; Jar = 'notification-service/target/insync-notification-service-0.0.1-SNAPSHOT.jar'; LegacyJar = 'notification-service/target/bluealbum-notification-service-0.0.1-SNAPSHOT.jar'; Port = 8081 },
            @{ Name = 'backend'; Jar = 'backend/target/insync-core-0.0.1-SNAPSHOT.jar'; LegacyJar = 'backend/target/demo1-0.0.1-SNAPSHOT.jar'; Port = 8080 },
            @{ Name = 'gateway'; Jar = 'gateway/target/insync-gateway-0.0.1-SNAPSHOT.jar'; LegacyJar = 'gateway/target/bluealbum-gateway-0.0.1-SNAPSHOT.jar'; Port = 9000 }
        )
        foreach ($app in $applications) {
            $jarCandidates = @($app.Jar)
            if ($NoBuild -and $app.LegacyJar) { $jarCandidates += $app.LegacyJar }
            $jar = $null
            foreach ($candidate in $jarCandidates) {
                $candidatePath = Join-Path $blueAlbumRoot $candidate
                if (Test-Path -LiteralPath $candidatePath) {
                    $jar = $candidatePath
                    break
                }
            }
            if (-not $jar) {
                $hint = if ($NoBuild) { 'Run without -NoBuild to build the canonical artifact.' } else { 'Check the Maven build output.' }
                throw "Missing canonical artifact $($app.Jar). $hint"
            }
            if ($jar -ne (Join-Path $blueAlbumRoot $app.Jar)) {
                Write-Warning "Using legacy -NoBuild artifact $($app.LegacyJar); run without -NoBuild to migrate to $($app.Jar)."
            }
            $listener = Get-NetTCPConnection -LocalPort $app.Port -State Listen -ErrorAction SilentlyContinue
            if ($listener) { throw "Port $($app.Port) became occupied. No existing process was stopped." }
            # Windows locks a running JAR. Run a snapshot so future builds remain possible.
            $runJar = Join-Path $blueAlbumRunDir "$($app.Name).jar"
            Copy-Item -LiteralPath $jar -Destination $runJar -Force
            $arguments = @('-jar', "`"$runJar`"", '--server.address=127.0.0.1', "--server.port=$($app.Port)")
            if ($app.Name -eq 'backend') {
                $uploads = Join-Path $blueAlbumRunDir 'uploads'
                $arguments += "`"--app.storage.local.baseDir=$uploads`""
            }
            $appCredentials = switch ($app.Name) {
                'backend' { @{
                    DB_USER = $runtimeValues['DB_USER']
                    DB_PASSWORD = $runtimeValues['DB_PASSWORD']
                    CHAT_INTERNAL_SERVICE_TOKEN = $runtimeValues['CHAT_INTERNAL_SERVICE_TOKEN']
                } }
                'notification-service' { @{
                    NOTIFICATION_DB_USER = if ($runtimeValues['NOTIFICATION_DB_USER']) { $runtimeValues['NOTIFICATION_DB_USER'] } else { $runtimeValues['DB_USER'] }
                    NOTIFICATION_DB_PASSWORD = if ($runtimeValues['NOTIFICATION_DB_PASSWORD']) { $runtimeValues['NOTIFICATION_DB_PASSWORD'] } else { $runtimeValues['DB_PASSWORD'] }
                    SPRING_FLYWAY_USER = $runtimeValues['NOTIFICATION_DB_MIGRATION_USER']
                    SPRING_FLYWAY_PASSWORD = $runtimeValues['NOTIFICATION_DB_MIGRATION_PASSWORD']
                } }
                default { @{} }
            }
            try {
                foreach ($entry in $appCredentials.GetEnumerator()) {
                    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
                }
                $process = Start-Process -FilePath $blueAlbumJava -ArgumentList $arguments -WorkingDirectory $blueAlbumRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $blueAlbumRunDir "$($app.Name).log") -RedirectStandardError (Join-Path $blueAlbumRunDir "$($app.Name).err.log")
            } finally {
                foreach ($name in $appCredentials.Keys) {
                    Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
                }
            }
            $entry = @{ Name = $app.Name; ProcessId = $process.Id; StartTime = $process.StartTime.ToUniversalTime().ToString('o') }
            $started.Add($entry)
            Write-LocalProcessSnapshot -Path $blueAlbumStateFile -Entries $started.ToArray()
            Wait-LocalHealth $app.Name "http://127.0.0.1:$($app.Port)/actuator/health" $process.Id -Actuator
            Write-Host "$($app.Name) is healthy."
        }

        $realtimeBinary = Join-Path $blueAlbumRunDir 'chat-realtime.exe'
        $goTool = Get-Command go -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        $realtimeBuilt = $NoBuild -and (Test-Path -LiteralPath $realtimeBinary)
        if ($goTool -and -not $NoBuild) {
            Push-Location (Join-Path $blueAlbumRoot 'services/chat-realtime')
            try {
                & $goTool.Source build -o $realtimeBinary . *> (Join-Path $blueAlbumRunDir 'chat-realtime-build.log')
                $realtimeBuilt = $LASTEXITCODE -eq 0
            } finally {
                Pop-Location
            }
        }
        $realtimePort = Get-NetTCPConnection -LocalPort 8092 -State Listen -ErrorAction SilentlyContinue
        if ($realtimePort) {
            Write-Warning 'Port 8092 is already in use. Chat can use HTTP polling if realtime is unavailable.'
        } elseif (-not $realtimeBuilt) {
            Write-Warning 'Chat realtime executable is unavailable. Run without -NoBuild with Go installed and check chat-realtime-build.log. Chat can use HTTP polling.'
        } else {
            $realtimeProcess = $null
            try {
                $realtimeProcess = Start-Process -FilePath $realtimeBinary -WorkingDirectory $blueAlbumRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $blueAlbumRunDir 'chat-realtime.log') -RedirectStandardError (Join-Path $blueAlbumRunDir 'chat-realtime.err.log')
            } catch {
                Write-Warning "Chat realtime could not start. $($_.Exception.Message) Chat can use HTTP polling."
            }
            if ($realtimeProcess) {
                $started.Add(@{ Name = 'chat-realtime'; ProcessId = $realtimeProcess.Id; StartTime = $realtimeProcess.StartTime.ToUniversalTime().ToString('o') })
                Write-LocalProcessSnapshot -Path $blueAlbumStateFile -Entries $started.ToArray()
                try {
                    Wait-LocalHealth 'chat-realtime' 'http://127.0.0.1:8092/health' $realtimeProcess.Id -TimeoutSeconds 15
                    Write-Host 'chat-realtime is healthy.'
                } catch {
                    Write-Warning "$($_.Exception.Message) Chat can use HTTP polling while realtime is unavailable."
                }
            }
        }

        $vite = Join-Path $blueAlbumRoot 'frontend/node_modules/vite/bin/vite.js'
        if (-not (Test-Path $vite)) { throw 'Install frontend dependencies with pnpm install first.' }
        $node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
        $process = Start-Process -FilePath $node -ArgumentList @("`"$vite`"", '--host', '127.0.0.1', '--port', '5173', '--strictPort') -WorkingDirectory (Join-Path $blueAlbumRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $blueAlbumRunDir 'frontend.log') -RedirectStandardError (Join-Path $blueAlbumRunDir 'frontend.err.log')
        $started.Add(@{ Name = 'frontend'; ProcessId = $process.Id; StartTime = $process.StartTime.ToUniversalTime().ToString('o') })
        Write-LocalProcessSnapshot -Path $blueAlbumStateFile -Entries $started.ToArray()
        Wait-LocalHealth 'frontend' 'http://127.0.0.1:5173/' $process.Id
        Write-Host 'BlueAlbum is running at http://127.0.0.1:5173 (local AI mock; isolated MySQL/Redis/RabbitMQ).'
    } finally {
        Pop-Location
    }
} catch {
    $cleanupFailures = [System.Collections.Generic.List[string]]::new()
    for ($index = $started.Count - 1; $index -ge 0; $index--) {
        $cleanup = Stop-LocalManagedProcess -Entry $started[$index] -WorkspaceRoot $blueAlbumRoot
        if ($cleanup.State -in @('mismatch', 'failed')) {
            $cleanupFailures.Add([string]$cleanup.Name)
        }
    }
    if ($cleanupFailures.Count -gt 0) {
        Write-Warning "Startup cleanup could not stop application process(es): $($cleanupFailures -join ', ')."
    }
    Write-Warning 'Startup failed. Application processes started by this attempt were cleaned where ownership was verified; dependency containers were retained.'
    throw
} finally {
    Restore-LocalEnvironmentSnapshot -Snapshot $environmentSnapshot
}

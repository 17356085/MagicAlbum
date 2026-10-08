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
New-Item -ItemType Directory -Path $blueAlbumRunDir -Force | Out-Null

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function Wait-LocalHealth([string]$Name, [string]$Url, [int]$ProcessId) {
    $deadline = [DateTime]::UtcNow.AddSeconds(150)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) {
            throw "$Name exited. See $blueAlbumRunDir/$Name.err.log and $Name.log."
        }
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return }
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
$env:JAVA_HOME = $JavaHome
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
Get-Command docker,mvn,node -ErrorAction Stop | Out-Null
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop must be running.' }

if (Test-Path $blueAlbumStateFile) {
    $oldState = Get-Content -LiteralPath $blueAlbumStateFile -Raw | ConvertFrom-Json
    foreach ($entry in $oldState) {
        $running = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
        if ($running -and $running.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.StartTime).ToUniversalTime().Ticks) {
            throw 'A managed local run is active. Run scripts/Stop-Local.ps1 before restarting.'
        }
    }
}
foreach ($port in @(8080,8081,9000,5173)) {
    $listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($listener) { throw "Port $port is already in use. No existing process was stopped." }
}

if (-not (Test-Path $blueAlbumEnvFile)) {
    $settings = @(
        'SPRING_PROFILES_ACTIVE=local',
        'DB_HOST=127.0.0.1', 'DB_PORT=13306', 'DB_NAME=bluealbum_local', 'DB_USER=bluealbum',
        "DB_PASSWORD=$(New-LocalSecret)", "DB_ROOT_PASSWORD=$(New-LocalSecret)",
        'NOTIFICATION_DB_NAME=bluealbum_local_notifications',
        'REDIS_HOST=127.0.0.1', 'REDIS_PORT=16379', "REDIS_PASSWORD=$(New-LocalSecret)",
        'SPRING_RABBITMQ_HOST=127.0.0.1', 'SPRING_RABBITMQ_PORT=15673',
        'SPRING_RABBITMQ_USERNAME=bluealbum', "SPRING_RABBITMQ_PASSWORD=$(New-LocalSecret)",
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
$originalEnv = @{}
$started = [System.Collections.Generic.List[object]]::new()
try {
    foreach ($line in Get-Content -LiteralPath $blueAlbumEnvFile) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
            $key = $Matches[1]
            $originalEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $Matches[2], 'Process')
        }
    }
    if (-not $originalEnv.ContainsKey('VITE_AUTH_VERIFY_MODE')) {
        $originalEnv['VITE_AUTH_VERIFY_MODE'] = [Environment]::GetEnvironmentVariable('VITE_AUTH_VERIFY_MODE', 'Process')
    }
    $env:VITE_AUTH_VERIFY_MODE = 'off'
    Push-Location $blueAlbumRoot
    try {
        & docker compose --env-file $blueAlbumEnvFile -f docker-compose.local.yml up -d --wait --wait-timeout 180
        if ($LASTEXITCODE -ne 0) { throw 'Local dependency startup failed.' }
        # Separate histories and schemas: neither service needs automatic Flyway baselining.
        $sql = "CREATE DATABASE IF NOT EXISTS bluealbum_local_notifications CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL PRIVILEGES ON bluealbum_local_notifications.* TO 'bluealbum'@'%';"
        $sql | & docker compose --env-file $blueAlbumEnvFile -f docker-compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root'
        if ($LASTEXITCODE -ne 0) { throw 'Notification database initialization failed.' }
        if ($DependenciesOnly) {
            Write-Host 'Isolated MySQL, Redis and RabbitMQ dependencies are healthy.'
            return
        }
        if (-not $NoBuild) {
            Write-Host 'Building all Java modules and running their tests...'
            & mvn -B package *> (Join-Path $blueAlbumRunDir 'build.log')
            if ($LASTEXITCODE -ne 0) { throw "Java build failed. See $blueAlbumRunDir/build.log." }
        }
        $applications = @(
            @{ Name='backend'; Jar='backend/target/demo1-0.0.1-SNAPSHOT.jar'; Port=8080 },
            @{ Name='notification-service'; Jar='notification-service/target/bluealbum-notification-service-0.0.1-SNAPSHOT.jar'; Port=8081 },
            @{ Name='gateway'; Jar='gateway/target/bluealbum-gateway-0.0.1-SNAPSHOT.jar'; Port=9000 }
        )
        foreach ($app in $applications) {
            $jar = Join-Path $blueAlbumRoot $app.Jar
            if (-not (Test-Path $jar)) { throw "Missing $jar. Run without -NoBuild." }
            $listener = Get-NetTCPConnection -LocalPort $app.Port -State Listen -ErrorAction SilentlyContinue
            if ($listener) { throw "Port $($app.Port) became occupied by PID $($listener.OwningProcess)." }
            # Windows locks a running JAR. Run a snapshot so future builds remain possible.
            $runJar = Join-Path $blueAlbumRunDir "$($app.Name).jar"
            Copy-Item -LiteralPath $jar -Destination $runJar -Force
            $arguments = @('-jar', "`"$runJar`"", '--server.address=127.0.0.1')
            if ($app.Name -eq 'backend') {
                $uploads = Join-Path $blueAlbumRunDir 'uploads'
                $arguments += "`"--app.storage.local.baseDir=$uploads`""
            }
            $process = Start-Process -FilePath $blueAlbumJava -ArgumentList $arguments -WorkingDirectory $blueAlbumRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $blueAlbumRunDir "$($app.Name).log") -RedirectStandardError (Join-Path $blueAlbumRunDir "$($app.Name).err.log")
            $entry = @{ Name=$app.Name; ProcessId=$process.Id; StartTime=$process.StartTime.ToUniversalTime().ToString('o') }
            $started.Add($entry)
            Wait-LocalHealth $app.Name "http://127.0.0.1:$($app.Port)/actuator/health" $process.Id
            Write-Host "$($app.Name) is healthy."
        }
        $vite = Join-Path $blueAlbumRoot 'frontend/node_modules/vite/bin/vite.js'
        if (-not (Test-Path $vite)) { throw 'Install frontend dependencies with pnpm install first.' }
        $node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
        $process = Start-Process -FilePath $node -ArgumentList @("`"$vite`"", '--host', '127.0.0.1', '--port', '5173', '--strictPort') -WorkingDirectory (Join-Path $blueAlbumRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $blueAlbumRunDir 'frontend.log') -RedirectStandardError (Join-Path $blueAlbumRunDir 'frontend.err.log')
        $started.Add(@{ Name='frontend'; ProcessId=$process.Id; StartTime=$process.StartTime.ToUniversalTime().ToString('o') })
        Wait-LocalHealth 'frontend' 'http://127.0.0.1:5173/' $process.Id
        [IO.File]::WriteAllText($blueAlbumStateFile, (ConvertTo-Json -InputObject $started.ToArray()), (New-Object Text.UTF8Encoding $false))
        Write-Host 'BlueAlbum is running at http://127.0.0.1:5173 (local AI mock; isolated MySQL/Redis/RabbitMQ).'
    } finally { Pop-Location }
} catch {
    foreach ($entry in $started) {
        $process = Get-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$entry.StartTime).ToUniversalTime().Ticks) {
            Stop-Process -Id $entry.ProcessId -ErrorAction SilentlyContinue
        }
    }
    throw
} finally {
    foreach ($key in $originalEnv.Keys) {
        [Environment]::SetEnvironmentVariable($key, $originalEnv[$key], 'Process')
    }
}

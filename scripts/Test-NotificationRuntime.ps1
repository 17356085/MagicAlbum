#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:/Program Files/Java/jdk-22',
    [string]$ArtifactDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs/notification-runtime'),
    [int]$CorePort = 19080,
    [int]$NotificationPort = 19081,
    [int]$GatewayPort = 19090
)

# Creates disposable containers and accounts; never uses local-runtime credentials or volumes.
$ErrorActionPreference = 'Stop'
$verificationRoot = Split-Path -Parent $PSScriptRoot
$ArtifactDirectory = [IO.Path]::GetFullPath($ArtifactDirectory)
$verificationOutputRoot = [IO.Path]::GetFullPath((Join-Path $verificationRoot 'outputs'))
if (-not $ArtifactDirectory.StartsWith($verificationOutputRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Verification artifacts must be under this workspace outputs directory'
}
foreach ($port in @($CorePort,$NotificationPort,$GatewayPort)) {
    if ($port -lt 1024 -or $port -gt 65535 -or (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
        throw "Verification port $port is invalid or occupied"
    }
}
if (@($CorePort,$NotificationPort,$GatewayPort | Select-Object -Unique).Count -ne 3) { throw 'Verification ports must be distinct' }
New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null
$verificationId = [Guid]::NewGuid().ToString('N').Substring(0,10)
$mysqlContainer = "bluealbum-verify-mysql-$verificationId"
$redisContainer = "bluealbum-verify-redis-$verificationId"
$ownedContainers = [System.Collections.Generic.List[string]]::new()
$ownedProcesses = [System.Collections.Generic.List[object]]::new()
$results = [System.Collections.Generic.List[object]]::new()
$runtimeSucceeded = $false
$java = Join-Path $JavaHome 'bin/java.exe'
$mysqlEnvFile = Join-Path $ArtifactDirectory "mysql-$verificationId.env"

function New-VerificationSecret {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}
function Check([bool]$Passed, [string]$Name) {
    $results.Add(@{Name=$Name;Passed=$Passed})
    if (-not $Passed) { throw "Verification failed: $Name" }
    Write-Host "PASS $Name"
}
function Sql([string]$Query) {
    $answer = $Query | & docker exec -i $mysqlContainer sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -h 127.0.0.1 -u root -N -B' 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Temporary database query failed' }
    return (($answer | ForEach-Object { [string]$_ }) -join "`n").Trim()
}
function Request([string]$Method, [string]$Path, $Body, [string]$Token, [int]$Expected = 200) {
    $options = @{Uri="http://127.0.0.1:$GatewayPort/api/v1$Path";Method=$Method;TimeoutSec=15;SkipHttpErrorCheck=$true}
    if ($Token) { $options.Headers = @{Authorization="Bearer $Token"} }
    if ($null -ne $Body) { $options.ContentType='application/json'; $options.Body=ConvertTo-Json $Body -Depth 8 -Compress }
    $response = Invoke-WebRequest @options
    if ($response.StatusCode -ne $Expected) { throw "$Method $Path returned $($response.StatusCode), expected $Expected" }
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}
function Stop-OwnedProcess($Entry) {
    $running = Get-Process -Id $Entry.Id -ErrorAction SilentlyContinue
    if ($running -and $running.StartTime.ToUniversalTime().Ticks -eq $Entry.StartTicks) {
        Stop-Process -Id $running.Id
        Wait-Process -Id $running.Id -Timeout 15 -ErrorAction SilentlyContinue
        $remaining = Get-Process -Id $Entry.Id -ErrorAction SilentlyContinue
        if ($remaining -and $remaining.StartTime.ToUniversalTime().Ticks -eq $Entry.StartTicks) {
            throw "Owned process $($Entry.Name) did not exit"
        }
    }
}
function Start-App([string]$Name, [string]$Jar, [int]$Port, [string]$Database, [string[]]$Extra = @()) {
    $source = Join-Path $verificationRoot $Jar
    if (-not (Test-Path -LiteralPath $source)) { throw "Build $Jar before verification" }
    $snapshot = Join-Path $ArtifactDirectory "$Name-$verificationId.jar"
    Copy-Item -LiteralPath $source -Destination $snapshot -Force
    $databaseUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/$Database`?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8"
    $childEnvironment = @{
        SPRING_DATASOURCE_URL=$databaseUrl; SPRING_DATASOURCE_USERNAME='root'; SPRING_DATASOURCE_PASSWORD=$databasePassword
        NOTIFICATION_DATASOURCE_URL=$databaseUrl; NOTIFICATION_DATASOURCE_USERNAME='root'; NOTIFICATION_DATASOURCE_PASSWORD=$databasePassword
        JWT_SECRET=$jwtSecret; NOTIFICATION_JWT_SECRET=$jwtSecret; APP_SECURITY_JWT_SECRET=$jwtSecret
        JWT_ISSUER='MagicAlbum'; NOTIFICATION_JWT_ISSUER='MagicAlbum'; APP_SECURITY_JWT_ISSUER='MagicAlbum'
        INTERNAL_SERVICE_TOKEN=$internalSecret; NOTIFICATION_INTERNAL_SERVICE_TOKEN=$internalSecret; APP_SECURITY_INTERNAL_SERVICE_TOKEN=$internalSecret
        APP_NOTIFICATIONS_CLIENT_INTERNAL_SERVICE_TOKEN=$internalSecret
    }
    $arguments = @('-Xms32m','-Xmx384m','-XX:ActiveProcessorCount=2','-jar',"`"$snapshot`"",
        '--spring.profiles.active=local','--spring.config.import=',"--server.port=$Port",'--server.address=127.0.0.1',
        '--spring.cloud.nacos.config.enabled=false','--spring.cloud.nacos.discovery.enabled=false',
        "--spring.datasource.url=$databaseUrl",'--spring.datasource.username=root') + $Extra
    $process = Start-Process -FilePath $java -ArgumentList $arguments -Environment $childEnvironment -WorkingDirectory $ArtifactDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $ArtifactDirectory "$Name.log") -RedirectStandardError (Join-Path $ArtifactDirectory "$Name.err.log")
    $entry = @{Name=$Name;Id=$process.Id;StartTicks=$process.StartTime.ToUniversalTime().Ticks}
    $ownedProcesses.Add($entry)
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Process -Id $entry.Id -ErrorAction SilentlyContinue)) { throw "$Name exited; inspect verification logs" }
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { Write-Host "$Name ready"; return $entry }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name readiness timeout"
}

try {
    $databasePassword = New-VerificationSecret
    $jwtSecret = New-VerificationSecret
    $internalSecret = New-VerificationSecret
    [IO.File]::WriteAllLines($mysqlEnvFile, @("MYSQL_ROOT_PASSWORD=$databasePassword",'MYSQL_ROOT_HOST=%'))
    $null = & docker run -d --name $mysqlContainer --env-file $mysqlEnvFile --publish '127.0.0.1::3306' mysql:8.0
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary MySQL container' }
    $ownedContainers.Add($mysqlContainer)
    $mysqlPort = [int]((& docker port $mysqlContainer 3306/tcp).Trim().Split(':')[-1])
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    $databaseReady = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        try { if ((Sql 'SELECT 1;') -eq '1') { $databaseReady=$true; break } } catch { }
        Start-Sleep -Seconds 1
    }
    Check $databaseReady 'Temporary MySQL is ready'
    $null = Sql 'CREATE DATABASE ba_verify_core CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE ba_verify_notifications CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;'
    $null = & docker run -d --name $redisContainer --publish '127.0.0.1::6379' redis:7-alpine
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary Redis container' }
    $ownedContainers.Add($redisContainer)
    $redisPort = [int]((& docker port $redisContainer 6379/tcp).Trim().Split(':')[-1])
    $notificationJar='notification-service/target/bluealbum-notification-service-0.0.1-SNAPSHOT.jar'
    $notification = Start-App 'notification' $notificationJar $NotificationPort 'ba_verify_notifications'
    Check ((Sql "SELECT COUNT(*) FROM ba_verify_notifications.notification_flyway_schema_history WHERE version='3' AND success=1;") -eq '1') 'Notification V3 migration committed in MySQL'
    $coreExtra = @('--app.rabbit.enabled=false','--management.health.rabbit.enabled=false','--app.auth.verify.mode=off',
        '--app.rate-limit.fail-closed=false','--app.ai.rate-limit.fail-closed=false','--app.admin.email=',
        '--app.notifications.client.mode=remote','--app.notifications.outbox.poll-interval-ms=250',
        "--app.notifications.client.base-url=http://127.0.0.1:$NotificationPort",'--feature.mp.read.threads=false',
        '--app.storage.type=local',"`"--app.storage.local.baseDir=$(Join-Path $ArtifactDirectory 'uploads')`"",
        '--spring.data.redis.host=127.0.0.1',"--spring.data.redis.port=$redisPort",'--spring.data.redis.password=')
    $null = Start-App 'core' 'backend/target/demo1-0.0.1-SNAPSHOT.jar' $CorePort 'ba_verify_core' $coreExtra
    Check ((Sql "SELECT COUNT(*) FROM ba_verify_core.flyway_schema_history WHERE version='20261014' AND success=1;") -eq '1') 'Core Outbox migration committed and Hibernate validate passed'
    # Spring replaces a collection when command-line entries override it; provide full routes.
    $gatewayExtra = @('--spring.cloud.gateway.server.webflux.routes[0].id=verification-notifications',
        "--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:$NotificationPort",
        '--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/v1/notifications/**',
        '--spring.cloud.gateway.server.webflux.routes[1].id=verification-ai',
        "--spring.cloud.gateway.server.webflux.routes[1].uri=http://127.0.0.1:$CorePort",
        '--spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/v1/ai/**',
        '--spring.cloud.gateway.server.webflux.routes[1].metadata.response-timeout=120000',
        '--spring.cloud.gateway.server.webflux.routes[2].id=verification-core',
        "--spring.cloud.gateway.server.webflux.routes[2].uri=http://127.0.0.1:$CorePort",
        '--spring.cloud.gateway.server.webflux.routes[2].predicates[0]=Path=/api/v1/**,/uploads/**')
    $null = Start-App 'gateway' 'gateway/target/bluealbum-gateway-0.0.1-SNAPSHOT.jar' $GatewayPort 'ba_verify_core' $gatewayExtra
    $accounts=@()
    foreach ($role in @('owner','reader')) {
        $password = New-VerificationSecret
        $email="$role-$verificationId@example.test"
        $registered=Request POST '/users/register' @{username="$role-$verificationId";email=$email;phone=('139'+(Get-Random -Minimum 10000000 -Maximum 99999999));password=$password} '' 201
        $login=Request POST '/auth/login' @{email=$email;password=$password} ''
        Check ($login.user.id -eq $registered.id) "$role authenticated through gateway"
        $accounts += @{Id=[long]$registered.id;Token=$login.accessToken}
    }
    $owner=$accounts[0]; $reader=$accounts[1]
    $sections=Request GET '/sections' $null ''
    $thread=Request POST '/threads' @{sectionId=$sections.items[0].id;title='Isolated notification verification';content='Temporary test data'} $owner.Token 201
    Stop-OwnedProcess $notification
    $post=Request POST "/threads/$($thread.id)/posts" @{contentMd='Reply while receiver is offline'} $reader.Token 201
    $targetId=[long]$post.id
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {
        $state=Sql "SELECT status FROM ba_verify_core.notification_outbox WHERE target_type='post' AND target_id=$targetId;"
        if ($state -eq 'PENDING') { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    Check ($state -eq 'PENDING') 'Receiver downtime preserves committed comment and retryable Outbox row'
    $null=Start-App 'notification' $notificationJar $NotificationPort 'ba_verify_notifications'
    $deadline=[DateTime]::UtcNow.AddSeconds(25)
    do {
        $page=Request GET '/notifications?unread=true' $null $owner.Token
        $notice=@($page.items | Where-Object {$_.targetType -eq 'post' -and $_.targetId -eq $targetId -and $_.threadId -eq $thread.id}) | Select-Object -First 1
        if ($notice) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    Check ($null -ne $notice) 'Receiver recovery delivers the queued notification through actual HTTP'
    $row=Sql "SELECT event_id,status,attempts FROM ba_verify_core.notification_outbox WHERE target_type='post' AND target_id=$targetId;"
    $fields=$row.Split("`t")
    $eventId=[Guid]::Parse($fields[0]).ToString()
    Check ($fields[1] -eq 'DELIVERED' -and [int]$fields[2] -ge 2) 'MySQL Outbox read-back confirms retry and delivery'
    Check ((Sql "SELECT COUNT(*) FROM ba_verify_notifications.notifications WHERE idempotency_key='$eventId';") -eq '1') 'Receiver MySQL contains exactly one event record'
    # mysql batch mode escapes line breaks and backslashes; HEX preserves exact payload bytes.
    $stored=Sql "SELECT user_id,HEX(type),HEX(title),HEX(content),thread_id,target_id,HEX(target_type) FROM ba_verify_notifications.notifications WHERE idempotency_key='$eventId';"
    $values=$stored.Split("`t")
    $payload = @{
        eventId = $eventId
        userId = [long]$values[0]
        type = [Text.Encoding]::UTF8.GetString([Convert]::FromHexString($values[1]))
        title = [Text.Encoding]::UTF8.GetString([Convert]::FromHexString($values[2]))
        content = [Text.Encoding]::UTF8.GetString([Convert]::FromHexString($values[3]))
        threadId = [long]$values[4]
        targetId = [long]$values[5]
        targetType = [Text.Encoding]::UTF8.GetString([Convert]::FromHexString($values[6]))
    }
    $replay=Invoke-WebRequest -Uri "http://127.0.0.1:$NotificationPort/api/v1/notifications/internal" -Method POST -Headers @{'X-Internal-Service-Token'=$internalSecret} -ContentType 'application/json' -Body (ConvertTo-Json $payload -Compress) -SkipHttpErrorCheck -TimeoutSec 10
    Check ($replay.StatusCode -eq 201 -and (($replay.Content | ConvertFrom-Json).id -eq $notice.id)) 'Replayed accepted event returns the same persisted notification'
    Check ((Sql "SELECT COUNT(*) FROM ba_verify_notifications.notifications WHERE idempotency_key='$eventId';") -eq '1') 'Replay does not duplicate the notification in MySQL'
    $payload.title='Conflicting event payload'
    $conflict=Invoke-WebRequest -Uri "http://127.0.0.1:$NotificationPort/api/v1/notifications/internal" -Method POST -Headers @{'X-Internal-Service-Token'=$internalSecret} -ContentType 'application/json' -Body (ConvertTo-Json $payload -Compress) -SkipHttpErrorCheck -TimeoutSec 10
    Check ($conflict.StatusCode -eq 409) 'Conflicting event payload is rejected'
    $null=Request PATCH "/notifications/$($notice.id)/read" $null $reader.Token 404
    $read=Request PATCH "/notifications/$($notice.id)/read" $null $owner.Token
    Check ($read.read) 'Only the authenticated recipient can mark the notification read'
    $runtimeSucceeded = $true
} finally {
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    for ($index=$ownedProcesses.Count-1;$index -ge 0;$index--) {
        try { Stop-OwnedProcess $ownedProcesses[$index] } catch { $cleanupErrors.Add("Process cleanup failed: $($ownedProcesses[$index].Name)") }
    }
    foreach ($container in $ownedContainers) {
        try {
            if ($container -notin @($mysqlContainer,$redisContainer)) { throw 'Unexpected container cleanup target' }
            $null=& docker rm -f -v $container
            if ($LASTEXITCODE -ne 0) { throw 'Container removal failed' }
        } catch { $cleanupErrors.Add("Container cleanup failed: $container") }
    }
    try {
        if (Test-Path -LiteralPath $mysqlEnvFile) { Remove-Item -LiteralPath $mysqlEnvFile }
    } catch { $cleanupErrors.Add('Temporary credential file cleanup failed') }
    $runtimeSucceeded = $runtimeSucceeded -and $cleanupErrors.Count -eq 0
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'checks.json'), (ConvertTo-Json @{RunId=$verificationId;Succeeded=$runtimeSucceeded;Checks=$results.ToArray()} -Depth 5))
    if ($cleanupErrors.Count -gt 0) { throw ($cleanupErrors -join '; ') }
}
Write-Host "Completed $($results.Count) isolated notification runtime checks; temporary services and containers removed."

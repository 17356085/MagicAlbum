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
Import-Module (Join-Path $PSScriptRoot 'lib/LocalRuntime.psm1') -Force
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
$notificationInitializer = '/usr/local/bin/bluealbum-init-notification-db.sh'
$coreDatabase = 'ba_verify_core'
$notificationDatabase = 'ba_verify_notifications'
$coreDatabaseUser = 'ba_verify_core_app'
$notificationDatabaseUser = 'ba_verify_notification_app'
$notificationMigrationUser = 'ba_verify_notification_ddl'

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
function Sql([string]$Query, [ValidateSet('root','core','notification','migration')][string]$Account = 'root', [string]$Database, [int]$ExpectedError) {
    $command = @'
case "$1" in
    root) user=root; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" ;;
    core) user="$MYSQL_USER"; MYSQL_PWD="$MYSQL_PASSWORD" ;;
    notification) user="$NOTIFICATION_DB_USER"; MYSQL_PWD="$NOTIFICATION_DB_PASSWORD" ;;
    migration) user="$NOTIFICATION_DB_MIGRATION_USER"; MYSQL_PWD="$NOTIFICATION_DB_MIGRATION_PASSWORD" ;;
esac
export MYSQL_PWD
shift
exec mysql --protocol=tcp --host=127.0.0.1 --user="$user" -N -B "$@"
'@
    $arguments = @('exec','-i',$mysqlContainer,'sh','-c',$command,'mysql',$Account)
    if ($Database) { $arguments += "--database=$Database" }
    $answer = $Query | & docker @arguments 2>&1
    if ($ExpectedError) {
        return ($LASTEXITCODE -ne 0 -and ($answer -join ' ') -match "\bERROR $ExpectedError\b")
    }
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
function Read-NotificationEvent([IO.StreamReader]$Reader, [Threading.CancellationToken]$CancellationToken) {
    $event = @{}
    $data = [System.Collections.Generic.List[string]]::new()
    while ($null -ne ($line = $Reader.ReadLineAsync($CancellationToken).AsTask().GetAwaiter().GetResult())) {
        if ($line -eq '') {
            if ($data.Count -gt 0) {
                $event.Payload = ($data -join "`n") | ConvertFrom-Json
                return $event
            }
            $event.Clear()
            continue
        }
        if ($line -match '^(id|event|data): ?(.*)$') {
            if ($Matches[1] -eq 'data') { $data.Add($Matches[2]) }
            else { $event[$Matches[1]] = $Matches[2] }
        }
    }
    throw 'SSE closed before replaying a notification event'
}
function Read-ReplayedNotificationEvent([string]$Token, [string]$Cursor) {
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(10))
    $client = [Net.Http.HttpClient]::new()
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get, "http://127.0.0.1:$GatewayPort/api/v1/notifications/stream")
    $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    $request.Headers.Accept.ParseAdd('text/event-stream')
    $request.Headers.Add('Last-Event-ID', $Cursor)
    $response = $null
    $reader = $null
    try {
        $response = $client.SendAsync($request, [Net.Http.HttpCompletionOption]::ResponseHeadersRead, $timeout.Token).GetAwaiter().GetResult()
        $null = $response.EnsureSuccessStatusCode()
        if ($response.Content.Headers.ContentType.MediaType -ne 'text/event-stream') { throw 'Expected an SSE response' }
        $reader = [IO.StreamReader]::new($response.Content.ReadAsStream($timeout.Token))
        return Read-NotificationEvent $reader $timeout.Token
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
        $timeout.Dispose()
    }
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
function Start-App([string]$Name, [string]$Jar, [int]$Port, [string[]]$Extra = @(),
    [string]$Database, [string]$DatabaseUser, [string]$DatabasePassword, [hashtable]$FlywayEnvironment = @{}) {
    $source = Join-Path $verificationRoot $Jar
    if (-not (Test-Path -LiteralPath $source)) { throw "Build $Jar before verification" }
    $snapshot = Join-Path $ArtifactDirectory "$Name-$verificationId.jar"
    Copy-Item -LiteralPath $source -Destination $snapshot -Force
    $childEnvironment = @{
        JWT_SECRET=$jwtSecret; NOTIFICATION_JWT_SECRET=$jwtSecret; APP_SECURITY_JWT_SECRET=$jwtSecret
        JWT_ISSUER='MagicAlbum'; NOTIFICATION_JWT_ISSUER='MagicAlbum'; APP_SECURITY_JWT_ISSUER='MagicAlbum'
        INTERNAL_SERVICE_TOKEN=$internalSecret; NOTIFICATION_INTERNAL_SERVICE_TOKEN=$internalSecret; APP_SECURITY_INTERNAL_SERVICE_TOKEN=$internalSecret
        APP_NOTIFICATIONS_CLIENT_INTERNAL_SERVICE_TOKEN=$internalSecret
    }
    $childEnvironment += $FlywayEnvironment
    foreach ($variable in Get-LocalEnvironmentNamesToIsolate) {
        if (-not $childEnvironment.ContainsKey($variable)) { $childEnvironment[$variable] = $null }
    }
    $arguments = @('-Xms32m','-Xmx384m','-XX:ActiveProcessorCount=2','-jar',"`"$snapshot`"",
        '--spring.profiles.active=local','--spring.config.import=',"--server.port=$Port",'--server.address=127.0.0.1',
        '--spring.cloud.nacos.config.enabled=false','--spring.cloud.nacos.discovery.enabled=false') + $Extra
    if ($Database) {
        $databaseUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/$Database`?useSSL=false&serverTimezone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&characterEncoding=utf8"
        $childEnvironment.SPRING_DATASOURCE_PASSWORD = $DatabasePassword
        $arguments += @("--spring.datasource.url=$databaseUrl", "--spring.datasource.username=$DatabaseUser")
    }
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
    $rootDatabasePassword = New-VerificationSecret
    $coreDatabasePassword = New-VerificationSecret
    $notificationDatabasePassword = New-VerificationSecret
    $notificationMigrationPassword = New-VerificationSecret
    $notificationFlyway = @{ SPRING_FLYWAY_USER=$notificationMigrationUser; SPRING_FLYWAY_PASSWORD=$notificationMigrationPassword }
    $jwtSecret = New-VerificationSecret
    $internalSecret = New-VerificationSecret
    [IO.File]::WriteAllLines($mysqlEnvFile, @(
        "MYSQL_ROOT_PASSWORD=$rootDatabasePassword",'MYSQL_ROOT_HOST=%',"MYSQL_DATABASE=$coreDatabase",
        "MYSQL_USER=$coreDatabaseUser","MYSQL_PASSWORD=$coreDatabasePassword","NOTIFICATION_DB_NAME=$notificationDatabase",
        "NOTIFICATION_DB_USER=$notificationDatabaseUser","NOTIFICATION_DB_PASSWORD=$notificationDatabasePassword",
        "NOTIFICATION_DB_MIGRATION_USER=$notificationMigrationUser","NOTIFICATION_DB_MIGRATION_PASSWORD=$notificationMigrationPassword"
    ))
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
    & docker cp (Join-Path $verificationRoot 'scripts/init-notification-db.sh') "${mysqlContainer}:$notificationInitializer"
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the notification database initializer' }
    $withoutMigration = @('-e','NOTIFICATION_DB_MIGRATION_USER=','-e','NOTIFICATION_DB_MIGRATION_PASSWORD=')
    & docker exec @withoutMigration -e 'NOTIFICATION_DB_USER=' -e 'NOTIFICATION_DB_PASSWORD=' $mysqlContainer sh $notificationInitializer
    Check ($LASTEXITCODE -eq 0) 'Notification database initialized with the legacy shared account'

    $notificationJar='notification-service/target/insync-notification-service-0.0.1-SNAPSHOT.jar'
    $legacyNotification = Start-App 'notification-legacy' $notificationJar $NotificationPort -Extra @('--spring.flyway.target=3') -Database $notificationDatabase -DatabaseUser $coreDatabaseUser -DatabasePassword $coreDatabasePassword
    Check ((Sql "SELECT COUNT(*) FROM notification_flyway_schema_history WHERE version IN ('1','2','3') AND success=1;" -Account core -Database $notificationDatabase) -eq '3') 'The legacy application created the V3 notification database'
    Check ((Sql "SELECT COUNT(*) FROM notification_flyway_schema_history WHERE version='4';" -Account core -Database $notificationDatabase) -eq '0') 'Notification V4 is pending before the account switch'
    $null = Sql "INSERT INTO notifications (user_id,type,title,content,created_at) VALUES (900001,'comment','Legacy notification','Database isolation fixture',CURRENT_TIMESTAMP);" -Account core -Database $notificationDatabase
    Stop-OwnedProcess $legacyNotification
    & docker exec @withoutMigration $mysqlContainer sh $notificationInitializer
    Check ($LASTEXITCODE -eq 0) 'Previously supported dedicated runtime account can be initialized without migration credentials'
    $null = Sql 'CREATE TABLE verification_old_grant (id INT);' -Account notification -Database $notificationDatabase
    $null = Sql 'DROP TABLE verification_old_grant;' -Account notification -Database $notificationDatabase
    foreach ($attempt in 1,2) {
        & docker exec $mysqlContainer sh $notificationInitializer
        Check ($LASTEXITCODE -eq 0) "Notification account isolation initializer succeeds (run $attempt)"
    }
    Check ((Sql 'SELECT DATABASE();' -Account core -Database $coreDatabase) -eq $coreDatabase) 'Core account can access its own database'
    Check (Sql 'SELECT 1;' -Account core -Database $notificationDatabase -ExpectedError 1044) 'MySQL denies core account access to notifications'
    Check (Sql 'SELECT 1;' -Account notification -Database $coreDatabase -ExpectedError 1044) 'MySQL denies notification account access to core'
    Check ((Sql "SELECT COUNT(*) FROM notifications WHERE title='Legacy notification';" -Account notification -Database $notificationDatabase) -eq '1') 'The dedicated notification account can read data written before the switch'

    Check (Sql 'SELECT 1;' -Account migration -Database $coreDatabase -ExpectedError 1044) 'MySQL denies notification migration account access to core'
    Check (Sql 'CREATE TABLE verification_runtime_ddl (id INT);' -Account notification -Database $notificationDatabase -ExpectedError 1142) 'MySQL denies DDL for the notification runtime account'

    $null = & docker run -d --name $redisContainer --publish '127.0.0.1::6379' redis:7-alpine
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary Redis container' }
    $ownedContainers.Add($redisContainer)
    $redisPort = [int]((& docker port $redisContainer 6379/tcp).Trim().Split(':')[-1])
    $notification = Start-App 'notification' $notificationJar $NotificationPort -Database $notificationDatabase -DatabaseUser $notificationDatabaseUser -DatabasePassword $notificationDatabasePassword -FlywayEnvironment $notificationFlyway
    Check ((Sql "SELECT COUNT(*) FROM notification_flyway_schema_history WHERE version IN ('1','2','3') AND success=1;" -Account migration -Database $notificationDatabase) -eq '3') 'V1–V3 notification migration history is preserved'
    Check ((Sql "SELECT installed_by FROM notification_flyway_schema_history WHERE version='4' AND success=1;" -Account migration -Database $notificationDatabase) -eq $notificationMigrationUser) 'Only the dedicated Flyway account applied V4'
    $backfill = "SELECT COUNT(*) FROM notification_stream_event e JOIN notifications n ON e.notification_id=n.id WHERE e.user_id=900001 AND e.event_kind='created' AND n.title='Legacy notification';"
    Check ((Sql $backfill -Account notification -Database $notificationDatabase) -eq '1') 'V4 migrated the existing notification into the event stream'
    $coreExtra = @('--app.rabbit.enabled=false','--management.health.rabbit.enabled=false','--app.auth.verify.mode=off',
        '--app.rate-limit.fail-closed=false','--app.ai.rate-limit.fail-closed=false','--app.admin.email=','--app.admin.user-id=0',
        '--app.notifications.client.mode=remote','--app.notifications.outbox.poll-interval-ms=250',
        "--app.notifications.client.base-url=http://127.0.0.1:$NotificationPort",'--feature.mp.read.threads=false',
        '--app.storage.type=local',"`"--app.storage.local.baseDir=$(Join-Path $ArtifactDirectory 'uploads')`"",
        '--spring.data.redis.host=127.0.0.1',"--spring.data.redis.port=$redisPort",'--spring.data.redis.password=')
    $null = Start-App 'core' 'backend/target/insync-core-0.0.1-SNAPSHOT.jar' $CorePort -Extra $coreExtra -Database $coreDatabase -DatabaseUser $coreDatabaseUser -DatabasePassword $coreDatabasePassword
    Check ((Sql "SELECT COUNT(*) FROM ba_verify_core.flyway_schema_history WHERE version='20261014' AND success=1;") -eq '1') 'Core Outbox migration committed and Hibernate validate passed'
    # Spring replaces a collection when command-line entries override it; provide full routes.
    $gatewayExtra = @('--spring.cloud.gateway.server.webflux.routes[0].id=verification-notification-stream',
        "--spring.cloud.gateway.server.webflux.routes[0].uri=http://127.0.0.1:$NotificationPort",
        '--spring.cloud.gateway.server.webflux.routes[0].predicates[0]=Path=/api/v1/notifications/stream',
        '--spring.cloud.gateway.server.webflux.routes[0].predicates[1]=Method=GET,HEAD',
        '--spring.cloud.gateway.server.webflux.routes[0].metadata.response-timeout=65000',
        '--spring.cloud.gateway.server.webflux.routes[1].id=verification-notifications',
        "--spring.cloud.gateway.server.webflux.routes[1].uri=http://127.0.0.1:$NotificationPort",
        '--spring.cloud.gateway.server.webflux.routes[1].predicates[0]=Path=/api/v1/notifications,/api/v1/notifications/events',
        '--spring.cloud.gateway.server.webflux.routes[1].predicates[1]=Method=GET,HEAD',
        '--spring.cloud.gateway.server.webflux.routes[2].id=verification-ai',
        "--spring.cloud.gateway.server.webflux.routes[2].uri=http://127.0.0.1:$CorePort",
        '--spring.cloud.gateway.server.webflux.routes[2].predicates[0]=Path=/api/v1/ai/**',
        '--spring.cloud.gateway.server.webflux.routes[2].metadata.response-timeout=120000',
        '--spring.cloud.gateway.server.webflux.routes[3].id=verification-core',
        "--spring.cloud.gateway.server.webflux.routes[3].uri=http://127.0.0.1:$CorePort",
        '--spring.cloud.gateway.server.webflux.routes[3].predicates[0]=Path=/api/v1/**,/uploads/**',
        '--spring.cloud.gateway.server.webflux.routes[4].id=verification-notification-read',
        "--spring.cloud.gateway.server.webflux.routes[4].uri=http://127.0.0.1:$NotificationPort",
        '--spring.cloud.gateway.server.webflux.routes[4].order=-1',
        '--spring.cloud.gateway.server.webflux.routes[4].predicates[0]=Path=/api/v1/notifications/{id}/read',
        '--spring.cloud.gateway.server.webflux.routes[4].predicates[1]=Method=PATCH')
    $null = Start-App 'gateway' 'gateway/target/insync-gateway-0.0.1-SNAPSHOT.jar' $GatewayPort -Extra $gatewayExtra
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
    $null=Request GET '/chat/realtime/session' $null $owner.Token 404
    $directSession=Invoke-RestMethod -Uri "http://127.0.0.1:$CorePort/api/v1/chat/realtime/session" -Headers @{Authorization="Bearer $($owner.Token)"} -TimeoutSec 10
    Check ($directSession.userId -eq $owner.Id) 'Chat session verification is private to the Go-to-core hop'
    $sections=Request GET '/sections' $null ''
    $thread=Request POST '/threads' @{sectionId=$sections.items[0].id;title='Isolated notification verification';content='Temporary test data'} $owner.Token 201
    $priorEvents = @(Request GET '/notifications/events' $null $owner.Token)
    $priorCursor = if ($priorEvents.Count -gt 0) { [string]$priorEvents[-1].cursor } else { '0' }
    Stop-OwnedProcess $notification
    $post=Request POST "/threads/$($thread.id)/posts" @{contentMd='Reply while receiver is offline'} $reader.Token 201
    $targetId=[long]$post.id
    $deadline=[DateTime]::UtcNow.AddSeconds(10)
    do {
        $failedAttempts=Sql "SELECT attempts FROM ba_verify_core.notification_outbox WHERE target_type='post' AND target_id=$targetId AND status='PENDING';"
        if ([int]$failedAttempts -ge 1) { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
    Check ([int]$failedAttempts -ge 1) 'Receiver downtime preserves committed comment and records a retryable delivery failure'
    $null=Start-App 'notification' $notificationJar $NotificationPort -Database $notificationDatabase -DatabaseUser $notificationDatabaseUser -DatabasePassword $notificationDatabasePassword -FlywayEnvironment $notificationFlyway
    $deadline=[DateTime]::UtcNow.AddSeconds(25)
    do {
        $page=Request GET '/notifications?unread=true' $null $owner.Token
        $notice=@($page.items | Where-Object {$_.targetType -eq 'post' -and $_.targetId -eq $targetId -and $_.threadId -eq $thread.id}) | Select-Object -First 1
        if ($notice) { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    Check ($null -ne $notice) 'Receiver recovery delivers the queued notification through actual HTTP'
    $deadline=[DateTime]::UtcNow.AddSeconds(5)
    do {
        $row=Sql "SELECT event_id,status,attempts FROM ba_verify_core.notification_outbox WHERE target_type='post' AND target_id=$targetId;"
        $fields=$row.Split("`t")
        if ($fields[1] -eq 'DELIVERED') { break }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $deadline)
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
    $blocked = Invoke-WebRequest -Uri "http://127.0.0.1:$GatewayPort/api/v1/notifications/internal" -Method POST -Headers @{'X-Internal-Service-Token'=$internalSecret} -ContentType 'application/json' -Body (ConvertTo-Json $payload -Compress) -SkipHttpErrorCheck -TimeoutSec 10
    Check ($blocked.StatusCode -eq 404) 'Gateway rejects the internal notification route even with a valid service credential'
    $replay=Invoke-WebRequest -Uri "http://127.0.0.1:$NotificationPort/api/v1/notifications/internal" -Method POST -Headers @{'X-Internal-Service-Token'=$internalSecret} -ContentType 'application/json' -Body (ConvertTo-Json $payload -Compress) -SkipHttpErrorCheck -TimeoutSec 10
    Check ($replay.StatusCode -eq 201 -and (($replay.Content | ConvertFrom-Json).id -eq $notice.id)) 'Replayed accepted event returns the same persisted notification'
    Check ((Sql "SELECT COUNT(*) FROM ba_verify_notifications.notifications WHERE idempotency_key='$eventId';") -eq '1') 'Replay does not duplicate the notification in MySQL'
    $payload.title='Conflicting event payload'
    $conflict=Invoke-WebRequest -Uri "http://127.0.0.1:$NotificationPort/api/v1/notifications/internal" -Method POST -Headers @{'X-Internal-Service-Token'=$internalSecret} -ContentType 'application/json' -Body (ConvertTo-Json $payload -Compress) -SkipHttpErrorCheck -TimeoutSec 10
    Check ($conflict.StatusCode -eq 409) 'Conflicting event payload is rejected'
    $otherAccountEvents = @(Request GET '/notifications/events' $null $reader.Token | Where-Object { $_.notificationId -eq [string]$notice.id })
    Check ($otherAccountEvents.Count -eq 0) 'Another account cannot read the recipient event'
    $streamEvent = Read-ReplayedNotificationEvent $owner.Token $priorCursor
    Check ($streamEvent.event -eq 'notification' -and $streamEvent.Payload.kind -eq 'created' -and
        $streamEvent.Payload.notificationId -eq [string]$notice.id -and $streamEvent.Payload.cursor -eq $streamEvent.id -and
        [long]$streamEvent.id -gt [long]$priorCursor) 'Gateway SSE replays the missed notification from Last-Event-ID'
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

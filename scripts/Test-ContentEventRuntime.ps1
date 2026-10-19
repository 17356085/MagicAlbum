#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$RunId = '',
    [string]$ArtifactDirectory = '',
    [string]$MavenExecutable = '',
    [ValidateRange(128, 4096)]
    [int]$MySqlMemoryMiB = 512,
    [ValidateRange(512, 16384)]
    [int]$MinimumFreeCommitMiB = 2048
)

# Disposable MySQL 8 acceptance for the content-event M2.1 transaction boundary.
# It never uses host volumes or local credentials and never stops Docker or other apps.
$ErrorActionPreference = 'Stop'
$verificationRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$verificationOutputRoot = [IO.Path]::GetFullPath((Join-Path $verificationRoot 'outputs'))

function New-RandomHex([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

if ([string]::IsNullOrWhiteSpace($RunId)) { $RunId = New-RandomHex 5 }
if ($RunId -notmatch '^[0-9a-f]{10,32}$') { throw 'RunId must be 10-32 lowercase hexadecimal characters' }
if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) {
    $ArtifactDirectory = Join-Path $verificationOutputRoot "m2-mysql-20261019/$RunId"
}
$ArtifactDirectory = [IO.Path]::GetFullPath($ArtifactDirectory)
if (-not $ArtifactDirectory.StartsWith($verificationOutputRoot + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Verification artifacts must be under this workspace outputs directory'
}
if (Test-Path -LiteralPath $ArtifactDirectory) {
    if (@(Get-ChildItem -LiteralPath $ArtifactDirectory -Force).Count -gt 0) {
        throw 'ArtifactDirectory must be empty so stale reports cannot be counted'
    }
} else {
    New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null
}

$containerName = "bluealbum-content-event-$RunId"
$labelKey = 'com.bluealbum.verification'
$labelValue = 'content-event-m2'
$runLabelKey = 'com.bluealbum.run'
$runtimeDatabase = "ba_content_runtime_$RunId"
$upgradeDatabase = "ba_content_upgrade_$RunId"
foreach ($databaseName in @($runtimeDatabase, $upgradeDatabase)) {
    if ($databaseName -notmatch '^ba_content_(runtime|upgrade)_[0-9a-f]{10,32}$') {
        throw 'Generated database name is invalid'
    }
}

$mysqlEnvFile = Join-Path $ArtifactDirectory "mysql-$RunId.env"
$containerId = $null
$containerStarted = $false
$anonymousVolumes = @()
$mysqlPort = $null
$resolvedMaven = $null
$results = [System.Collections.Generic.List[object]]::new()
$environmentKeys = @(
    'CONTENT_EVENT_TEST_DATASOURCE_URL',
    'CONTENT_EVENT_TEST_DATASOURCE_DRIVER',
    'CONTENT_EVENT_TEST_DATASOURCE_USERNAME',
    'CONTENT_EVENT_TEST_DATASOURCE_PASSWORD',
    'CONTENT_EVENT_TEST_JPA_DDL_AUTO',
    'CONTENT_EVENT_TEST_HIBERNATE_DIALECT',
    'CONTENT_EVENT_TEST_FLYWAY_ENABLED',
    'CONTENT_EVENT_TEST_EXPECT_MYSQL',
    'CONTENT_EVENT_TEST_EXPECTED_DATABASE',
    'CONTENT_EVENT_TEST_UPGRADE_JDBC_URL',
    'CONTENT_EVENT_TEST_UPGRADE_JDBC_USERNAME',
    'CONTENT_EVENT_TEST_UPGRADE_JDBC_PASSWORD',
    'CONTENT_EVENT_TEST_UPGRADE_EXPECTED_DATABASE',
    'MAVEN_OPTS',
    'JAVA_TOOL_OPTIONS',
    'SPRING_CONFIG_IMPORT'
)
$environmentSnapshot = @{}
foreach ($key in $environmentKeys) {
    $environmentSnapshot[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$failureMessage = $null
$runtimeSucceeded = $false
$cleanupSucceeded = $false
$containerRemoved = $false
$volumesRemoved = $false
$credentialFileRemaining = $false
$readback = [ordered]@{
    FlywayVersions = [ordered]@{}
    MysqlVersions = [ordered]@{}
    RuntimeThread = $null
    Events = @()
    TagCount = $null
    SensitivePayloadColumnCount = $null
}

function Check([bool]$Passed, [string]$Name) {
    $script:results.Add([pscustomobject]@{ Name = $Name; Passed = $Passed })
    if (-not $Passed) { throw "Verification failed: $Name" }
    Write-Host "PASS $Name"
}

function Save-Json([string]$Path, $Value) {
    [IO.File]::WriteAllText($Path, (ConvertTo-Json $Value -Depth 8))
}

function Set-ProcessEnvironment([hashtable]$Values) {
    foreach ($entry in $Values.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, 'Process')
    }
}

function Restore-ProcessEnvironment {
    foreach ($key in $environmentKeys) {
        [Environment]::SetEnvironmentVariable($key, $environmentSnapshot[$key], 'Process')
    }
}

function Invoke-Docker([string[]]$Arguments) {
    $output = & docker @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw 'Docker command failed' }
    return (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Invoke-Sql([string]$Database, [string]$Query) {
    if ([string]::IsNullOrWhiteSpace($Database)) {
        $scriptText = $Query
    } else {
        if ($Database -notmatch '^ba_content_(runtime|upgrade)_[0-9a-f]{10,32}$') {
            throw 'SQL database guard rejected the database name'
        }
        $scriptText = "USE $Database;`n$Query"
    }
    $answer = $scriptText | & docker exec -i $containerName sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP -h 127.0.0.1 -u root --batch --raw --skip-column-names' 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw 'Temporary database query failed' }
    return (($answer | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Invoke-SqlScalar([string]$Database, [string]$Query) {
    $value = Invoke-Sql $Database $Query
    return $value.Trim()
}

function Get-DockerObject([string]$Name) {
    $raw = Invoke-Docker @('inspect', $Name)
    return @($raw | ConvertFrom-Json)[0]
}

function Invoke-MavenRound([string]$Name, [string]$TestSelector, [string]$ReportsDirectory) {
    $roundDirectory = Join-Path $ArtifactDirectory $Name
    New-Item -ItemType Directory -Path $roundDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $ReportsDirectory -Force | Out-Null
    $logPath = Join-Path $roundDirectory 'maven.log'
    $sourceReports = Join-Path $verificationRoot 'backend/target/surefire-reports'
    $expectedReportNames = @($TestSelector.Split(',') | ForEach-Object {
        $className = ($_ -split '#', 2)[0]
        if ($className -notmatch '^com\.example\.demo\.threads\.event\.[A-Za-z]+$') {
            throw 'Unexpected content event test selector'
        }
        "TEST-$className.xml"
        "$className.txt"
    })
    foreach ($reportName in $expectedReportNames) {
        $sourceReport = Join-Path $sourceReports $reportName
        if (Test-Path -LiteralPath $sourceReport) { Remove-Item -LiteralPath $sourceReport -Force }
    }
    $arguments = @(
        '-pl', 'backend',
        "-Dtest=$TestSelector",
        '-Dsurefire.failIfNoSpecifiedTests=true',
        'test'
    )
    $roundStarted = [DateTime]::UtcNow
    Push-Location $verificationRoot
    try {
        & $resolvedMaven @arguments *> $logPath
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    foreach ($reportName in $expectedReportNames) {
        $sourceReport = Join-Path $sourceReports $reportName
        if (Test-Path -LiteralPath $sourceReport) {
            Copy-Item -LiteralPath $sourceReport -Destination (Join-Path $ReportsDirectory $reportName)
        }
    }
    [IO.File]::WriteAllText((Join-Path $roundDirectory 'exit-code.txt'), [string]$exitCode)
    if ($exitCode -ne 0) { throw "Maven $Name failed; inspect its saved log" }

    # Surefire 3.5.4 exposes reportsDirectory as a plugin parameter without a
    # user-property binding in this project. Copy only the named reports written
    # by this round from the module's generated directory into our isolated evidence.
    $defaultReports = Join-Path $verificationRoot 'backend/target/surefire-reports'
    $testClasses = @($TestSelector -split ',' | ForEach-Object { ($_ -split '#')[0] })
    foreach ($testClass in $testClasses) {
        $xmlName = "TEST-$testClass.xml"
        $textName = "$testClass.txt"
        foreach ($reportName in @($xmlName, $textName)) {
            $source = Join-Path $defaultReports $reportName
            if (-not (Test-Path -LiteralPath $source)) {
                throw "Maven $Name did not produce the expected report $reportName"
            }
            $sourceItem = Get-Item -LiteralPath $source
            if ($sourceItem.LastWriteTimeUtc -lt $roundStarted.AddSeconds(-2)) {
                throw "Maven $Name report $reportName was stale"
            }
            Copy-Item -LiteralPath $source -Destination (Join-Path $ReportsDirectory $reportName) -Force
        }
    }
}

function Assert-SurefireReports([string]$Name, [string]$ReportsDirectory,
                                [int]$ExpectedTests, [string[]]$ExpectedClassNames) {
    $xmlFiles = @(Get-ChildItem -LiteralPath $ReportsDirectory -Filter 'TEST-*.xml' -File)
    if ($xmlFiles.Count -eq 0) { throw "Maven $Name produced no isolated JUnit XML reports" }
    $totalTests = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    $suiteNames = [System.Collections.Generic.List[string]]::new()
    foreach ($xmlFile in $xmlFiles) {
        [xml]$report = Get-Content -LiteralPath $xmlFile -Raw
        $suite = $report.testsuite
        if ($null -eq $suite) { throw "Invalid JUnit report $($xmlFile.Name)" }
        $suiteNames.Add([string]$suite.name)
        $cases = @($report.SelectNodes('//testcase'))
        $totalTests += $cases.Count
        $failures += @($report.SelectNodes('//testcase/failure')).Count
        $errors += @($report.SelectNodes('//testcase/error')).Count
        $skipped += @($report.SelectNodes('//testcase/skipped')).Count
    }
    foreach ($expectedClass in $ExpectedClassNames) {
        if (-not @($suiteNames | Where-Object { $_ -eq $expectedClass -or $_.EndsWith(".$expectedClass") }).Count) {
            throw "Maven $Name did not report expected test class $expectedClass"
        }
    }
    Check ($totalTests -eq $ExpectedTests) "$Name reports exactly $ExpectedTests test cases"
    Check (($failures -eq 0) -and ($errors -eq 0) -and ($skipped -eq 0)) "$Name reports zero skip/fail/error"
    $matchedDirectory = Join-Path (Join-Path $ArtifactDirectory $Name) 'matched-reports'
    New-Item -ItemType Directory -Path $matchedDirectory -Force | Out-Null
    foreach ($file in @(Get-ChildItem -LiteralPath $ReportsDirectory -File)) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $matchedDirectory $file.Name) -Force
    }
    [IO.File]::WriteAllLines((Join-Path (Join-Path $ArtifactDirectory $Name) 'summary.txt'), @(
        "tests=$totalTests"
        "failures=$failures"
        "errors=$errors"
        "skipped=$skipped"
        "reports=$($xmlFiles.Count)"
    ))
}

function Assert-FinalReadback {
    foreach ($databaseName in @($runtimeDatabase, $upgradeDatabase)) {
        $version = Invoke-SqlScalar $databaseName 'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;'
        $readback.FlywayVersions[$databaseName] = $version.Replace('_', '.')
        Check ($version.Replace('_', '.') -eq '20261019.2') "$databaseName has the latest Flyway migration"
        $mysqlVersion = Invoke-SqlScalar $databaseName 'SELECT VERSION();'
        $readback.MysqlVersions[$databaseName] = $mysqlVersion
        Check ($mysqlVersion -match '^8\.') "$databaseName is MySQL 8"
    }

    $threadRow = Invoke-SqlScalar $runtimeDatabase "SELECT id,content_version,status FROM threads WHERE title='Original title';"
    $threadFields = $threadRow.Split("`t")
    $readback.RuntimeThread = [ordered]@{
        Id = [long]$threadFields[0]
        ContentVersion = [long]$threadFields[1]
        Status = $threadFields[2]
    }
    Check ($threadFields.Count -eq 3 -and $threadFields[1] -eq '5' -and $threadFields[2] -eq 'DELETED') 'Final thread read-back is version 5 and DELETED'
    $threadId = [long]$threadFields[0]

    $eventText = Invoke-Sql $runtimeDatabase "SELECT aggregate_id,aggregate_version,event_type,status,schema_version,event_id FROM content_event_outbox WHERE aggregate_id=$threadId ORDER BY aggregate_version;"
    $eventRows = @([regex]::Split($eventText, '\r?\n') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $expectedTypes = @('THREAD_UPSERTED', 'THREAD_UPSERTED', 'THREAD_UPSERTED', 'THREAD_HIDDEN', 'THREAD_DELETED')
    $eventsValid = $eventRows.Count -eq 5
    $eventMetadata = [System.Collections.Generic.List[object]]::new()
    for ($index = 0; $index -lt $eventRows.Count; $index++) {
        $fields = ([string]$eventRows[$index]).Split("`t")
        $eventsValid = $eventsValid -and $fields.Count -eq 6
        if ($fields.Count -eq 6) {
            $eventMetadata.Add([ordered]@{
                    AggregateId = [long]$fields[0]
                    AggregateVersion = [long]$fields[1]
                    EventType = $fields[2]
                    Status = $fields[3]
                    SchemaVersion = [int]$fields[4]
                    EventId = $fields[5]
                })
            $eventsValid = $eventsValid -and ([long]$fields[0] -eq $threadId)
            $eventsValid = $eventsValid -and ([long]$fields[1] -eq ($index + 1))
            $eventsValid = $eventsValid -and ($fields[2] -eq $expectedTypes[$index])
            $eventsValid = $eventsValid -and ($fields[3] -eq 'PENDING')
            $eventsValid = $eventsValid -and ([int]$fields[4] -eq 1)
            $eventsValid = $eventsValid -and ($fields[5] -match '^[0-9a-fA-F-]{36}$')
        }
    }
    $readback.Events = $eventMetadata.ToArray()
    Check $eventsValid 'Final event read-back has five ordered pending UUID events'
    $readback.TagCount = [int](Invoke-SqlScalar $runtimeDatabase "SELECT COUNT(*) FROM thread_tags tt JOIN tags t ON t.id=tt.tag_id WHERE tt.thread_id=$threadId AND t.name='second-tag';")
    Check ($readback.TagCount -eq 1) 'Final real tag is persisted'
    $readback.SensitivePayloadColumnCount = [int](Invoke-SqlScalar $runtimeDatabase "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='content_event_outbox' AND LOWER(column_name) IN ('content','title','password','password_hash','body','payload');")
    Check ($readback.SensitivePayloadColumnCount -eq 0) 'Event table has no sensitive payload columns'
    Save-Json (Join-Path $ArtifactDirectory 'readback.json') $readback
}

try {
    $dockerVersionRaw = Invoke-Docker @('version', '--format', '{{json .Server}}')
    $dockerInfoRaw = Invoke-Docker @('info', '--format', '{{json .}}')
    $dockerVersion = $dockerVersionRaw | ConvertFrom-Json
    $dockerInfo = $dockerInfoRaw | ConvertFrom-Json
    $dockerMemoryBytes = [int64]$dockerInfo.MemTotal
    $requiredDockerMemoryBytes = [int64]($MySqlMemoryMiB + 256) * 1MB
    Save-Json (Join-Path $ArtifactDirectory 'engine.json') ([pscustomobject]@{
            ServerVersion = if ($dockerVersion.Version) { [string]$dockerVersion.Version } else { [string]$dockerVersion.ServerVersion }
            MemTotalBytes = $dockerMemoryBytes
            RequiredBytes = $requiredDockerMemoryBytes
        })
    Check ($dockerMemoryBytes -ge $requiredDockerMemoryBytes) 'Docker engine memory meets the MySQL limit'

    if ($IsWindows) {
        $hostMemory = Get-CimInstance Win32_OperatingSystem
        $freeCommitBytes = [int64]$hostMemory.FreeVirtualMemory * 1KB
    } else {
        $memMatch = [regex]::Match((Get-Content -LiteralPath '/proc/meminfo' -Raw), '(?m)^MemAvailable:\s+(\d+)\s+kB$')
        if (-not $memMatch.Success) { throw 'Linux MemAvailable was not available' }
        $freeCommitBytes = [int64]$memMatch.Groups[1].Value * 1KB
    }
    Save-Json (Join-Path $ArtifactDirectory 'host-memory-before.json') ([pscustomobject]@{
            FreeVirtualMemoryBytes = $freeCommitBytes
            RequiredBytes = [int64]$MinimumFreeCommitMiB * 1MB
        })
    Check ($freeCommitBytes -ge ([int64]$MinimumFreeCommitMiB * 1MB)) 'Host free commit meets the acceptance threshold'

    if ([string]::IsNullOrWhiteSpace($MavenExecutable)) {
        $MavenExecutable = if ($IsWindows) { 'D:/Environment/apache-maven-3.9.11/bin/mvn.cmd' } else { 'mvn' }
    }
    if (Test-Path -LiteralPath $MavenExecutable) {
        $resolvedMaven = [IO.Path]::GetFullPath($MavenExecutable)
    } else {
        $command = Get-Command $MavenExecutable -ErrorAction SilentlyContinue
        if ($null -eq $command) { throw 'Maven executable was not found' }
        $resolvedMaven = $command.Source
    }
    Check (Test-Path -LiteralPath $resolvedMaven) 'Maven executable is available'

    $databasePassword = New-RandomHex 32
    [IO.File]::WriteAllLines($mysqlEnvFile, @("MYSQL_ROOT_PASSWORD=$databasePassword", 'MYSQL_ROOT_HOST=%'))
    $dockerRunOutput = Invoke-Docker @(
        'run', '-d', '--name', $containerName,
        '--label', "$labelKey=$labelValue",
        '--label', "$runLabelKey=$RunId",
        '--env-file', $mysqlEnvFile,
        '--memory', "${MySqlMemoryMiB}m",
        '--memory-swap', "${MySqlMemoryMiB}m",
        '--publish', '127.0.0.1::3306',
        'mysql:8.0'
    )
    $containerId = ($dockerRunOutput -split "`r?`n" | Where-Object { $_ -match '^[0-9a-f]{12,64}$' } | Select-Object -Last 1)
    if ([string]::IsNullOrWhiteSpace($containerId)) { throw 'Docker did not return the owned container id' }
    $containerStarted = $true
    $portText = Invoke-Docker @('port', $containerName, '3306/tcp')
    if ($portText -notmatch ':(\d+)$') { throw 'Docker did not return a dynamic MySQL port' }
    $mysqlPort = [int]$Matches[1]
    Check ($mysqlPort -ge 1024 -and $mysqlPort -le 65535) 'Temporary MySQL uses a valid loopback port'

    $container = Get-DockerObject $containerName
    $mounts = @($container.Mounts)
    $anonymousVolumes = @($mounts | Where-Object { $_.Type -eq 'volume' } | ForEach-Object { [string]$_.Name })
    $bindMounts = @($mounts | Where-Object { $_.Type -eq 'bind' })
    Check ([int64]$container.HostConfig.Memory -eq ([int64]$MySqlMemoryMiB * 1MB)) 'MySQL container memory limit is exact'
    Check ([int64]$container.HostConfig.MemorySwap -eq ([int64]$MySqlMemoryMiB * 1MB)) 'MySQL container memory-swap limit is exact'
    Check ($bindMounts.Count -eq 0) 'MySQL container has no host bind mount'
    Save-Json (Join-Path $ArtifactDirectory 'container-record.json') ([pscustomobject]@{
            Id = [string]$container.Id
            Name = [string]$container.Name
            Labels = $container.Config.Labels
            RuntimeDatabase = $runtimeDatabase
            UpgradeDatabase = $upgradeDatabase
            Port = $mysqlPort
            AnonymousVolumes = $anonymousVolumes
        })

    $databaseDeadline = [DateTime]::UtcNow.AddSeconds(120)
    $databaseReady = $false
    while ([DateTime]::UtcNow -lt $databaseDeadline) {
        try {
            if ((Invoke-SqlScalar '' 'SELECT 1;') -eq '1') { $databaseReady = $true; break }
        } catch { Start-Sleep -Seconds 1 }
    }
    Check $databaseReady 'Temporary MySQL is ready'
    Check ((Invoke-SqlScalar '' 'SELECT VERSION();') -match '^8\.') 'Temporary database reports MySQL 8'
    Invoke-SqlScalar '' "CREATE DATABASE $runtimeDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE $upgradeDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" | Out-Null

    $runtimeUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/$runtimeDatabase`?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
    $upgradeUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/$upgradeDatabase`?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
    Set-ProcessEnvironment @{
        CONTENT_EVENT_TEST_DATASOURCE_URL = $runtimeUrl
        CONTENT_EVENT_TEST_DATASOURCE_DRIVER = 'com.mysql.cj.jdbc.Driver'
        CONTENT_EVENT_TEST_DATASOURCE_USERNAME = 'root'
        CONTENT_EVENT_TEST_DATASOURCE_PASSWORD = $databasePassword
        CONTENT_EVENT_TEST_JPA_DDL_AUTO = 'validate'
        CONTENT_EVENT_TEST_HIBERNATE_DIALECT = 'org.hibernate.dialect.MySQLDialect'
        CONTENT_EVENT_TEST_FLYWAY_ENABLED = 'true'
        CONTENT_EVENT_TEST_EXPECT_MYSQL = 'true'
        CONTENT_EVENT_TEST_EXPECTED_DATABASE = $runtimeDatabase
        CONTENT_EVENT_TEST_UPGRADE_JDBC_URL = $upgradeUrl
        CONTENT_EVENT_TEST_UPGRADE_JDBC_USERNAME = 'root'
        CONTENT_EVENT_TEST_UPGRADE_JDBC_PASSWORD = $databasePassword
        CONTENT_EVENT_TEST_UPGRADE_EXPECTED_DATABASE = $upgradeDatabase
        MAVEN_OPTS = '-Xmx512m -XX:ActiveProcessorCount=2'
        JAVA_TOOL_OPTIONS = '-Xmx512m -XX:ActiveProcessorCount=2'
        SPRING_CONFIG_IMPORT = ''
    }

    $roundOneReports = Join-Path $ArtifactDirectory 'round-1/reports'
    Invoke-MavenRound 'round-1' 'com.example.demo.threads.event.ContentEventMySqlUpgradeAcceptance,com.example.demo.threads.event.ThreadContentEventIntegrationTest' $roundOneReports
    Assert-SurefireReports 'round-1' $roundOneReports 7 @('ContentEventMySqlUpgradeAcceptance', 'ThreadContentEventIntegrationTest')

    $roundTwoReports = Join-Path $ArtifactDirectory 'round-2/reports'
    Invoke-MavenRound 'round-2' 'com.example.demo.threads.event.ThreadContentEventIntegrationTest#all_content_commands_commit_versions_events_and_real_tags_together' $roundTwoReports
    Assert-SurefireReports 'round-2' $roundTwoReports 1 @('ThreadContentEventIntegrationTest')
    Assert-FinalReadback
    $runtimeSucceeded = $true
} catch {
    $failureMessage = $_.Exception.Message
    Write-Error $failureMessage
} finally {
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    try {
        if ($containerStarted) {
            $existing = Get-DockerObject $containerName
            $labels = $existing.Config.Labels
            if ([string]$existing.Id -ne [string]$containerId -or
                [string]$labels.$labelKey -ne $labelValue -or
                [string]$labels.$runLabelKey -ne $RunId) {
                throw 'Owned container identity or labels changed; refusing unrelated cleanup'
            }
            $null = Invoke-Docker @('rm', '-f', '-v', $containerName)
            $containerStarted = $false
            $remainingContainer = & docker ps -aq --no-trunc --filter "id=$containerId" 2>$null
            $remainingContainerExit = $LASTEXITCODE
            if ($remainingContainerExit -ne 0 -or -not [string]::IsNullOrWhiteSpace(($remainingContainer -join ''))) {
                $cleanupErrors.Add('Owned container remains or Docker removal could not be verified')
            } else {
                $containerRemoved = $true
            }
            $volumesRemoved = $true
            foreach ($volume in $anonymousVolumes) {
                $remainingVolume = & docker volume ls -q --filter "name=^$volume`$" 2>$null
                $remainingVolumeExit = $LASTEXITCODE
                if ($remainingVolumeExit -ne 0 -or -not [string]::IsNullOrWhiteSpace(($remainingVolume -join ''))) {
                    $cleanupErrors.Add("Anonymous volume remains or could not be verified: $volume")
                    $volumesRemoved = $false
                }
            }
            $labelResidue = & docker ps -aq --filter "label=$runLabelKey=$RunId" 2>$null
            if ($LASTEXITCODE -ne 0 -or -not [string]::IsNullOrWhiteSpace(($labelResidue -join ''))) {
                $cleanupErrors.Add('Owned verification label remains on a container')
            }
        }
    } catch { $cleanupErrors.Add('Owned container cleanup failed') }
    try {
        Restore-ProcessEnvironment
    } catch { $cleanupErrors.Add('Process environment restoration failed') }
    try {
        if (Test-Path -LiteralPath $mysqlEnvFile) { Remove-Item -LiteralPath $mysqlEnvFile -Force }
        if (Test-Path -LiteralPath $mysqlEnvFile) { $cleanupErrors.Add('Temporary credential file remains') }
    } catch { $cleanupErrors.Add('Temporary credential file cleanup failed') }
    $credentialFileRemaining = Test-Path -LiteralPath $mysqlEnvFile
    $cleanupSucceeded = $cleanupErrors.Count -eq 0 -and -not $credentialFileRemaining
    if ($cleanupErrors.Count -gt 0) {
        $runtimeSucceeded = $false
        $failureMessage = (@($failureMessage, ($cleanupErrors -join '; ')) | Where-Object { $_ }) -join '; '
    }
    Save-Json (Join-Path $ArtifactDirectory 'checks.json') ([pscustomobject]@{
            RunId = $RunId
            RuntimeSucceeded = $runtimeSucceeded
            RuntimeDatabase = $runtimeDatabase
            UpgradeDatabase = $upgradeDatabase
            Port = $mysqlPort
            CleanupSucceeded = $cleanupSucceeded
            ContainerRemoved = $containerRemoved
            AnonymousVolumesRemoved = $volumesRemoved
            CredentialFileRemaining = $credentialFileRemaining
            Checks = $results.ToArray()
            Failure = $failureMessage
        })
}

if ($failureMessage) { throw $failureMessage }
Write-Host "Completed $($results.Count) isolated content-event MySQL checks; temporary services and credentials removed."

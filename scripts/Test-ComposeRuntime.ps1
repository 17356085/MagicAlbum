#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$RunId = ([Guid]::NewGuid().ToString('N').Substring(0, 10)),
    [string]$ArtifactDirectory = '',
    [string]$PythonExecutable = '',
    [string]$BrowserChannel = 'chrome',
    [ValidateSet('Standard', 'Constrained')]
    [string]$ResourceMode = 'Standard',
    [switch]$NoBuild,
    [switch]$IncludeStageFixtures
)

$ErrorActionPreference = 'Stop'
if ($ResourceMode -eq 'Constrained' -and -not $NoBuild) {
    throw 'Constrained resource mode requires -NoBuild because the bounded memory budget excludes image build peaks'
}
$runtimeRoot = Split-Path -Parent $PSScriptRoot
$runtimeHostModulePath = Join-Path $PSScriptRoot 'lib/RuntimeHost.psm1'
if (-not (Test-Path -LiteralPath $runtimeHostModulePath -PathType Leaf)) { throw 'Runtime host helper module is missing' }
Import-Module -Name $runtimeHostModulePath -Force
if ($IncludeStageFixtures) {
    Import-Module -Name (Join-Path $PSScriptRoot 'lib/StageRestore.psm1') -Force
}
if ([string]::IsNullOrWhiteSpace($PythonExecutable)) {
    $PythonExecutable = Get-RuntimeDefaultPythonExecutable -RepositoryRoot $runtimeRoot
}
if ($RunId -notmatch '^[a-f0-9]{10}$') { throw 'RunId must contain exactly ten lowercase hexadecimal characters' }
if ([string]::IsNullOrWhiteSpace($ArtifactDirectory)) {
    $ArtifactDirectory = Join-Path (Join-Path $runtimeRoot 'outputs/m0-compose') $RunId
}
$ArtifactDirectory = [IO.Path]::GetFullPath($ArtifactDirectory)
$allowedOutput = [IO.Path]::GetFullPath((Join-Path $runtimeRoot 'outputs'))
if (-not $ArtifactDirectory.StartsWith($allowedOutput + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Compose verification artifacts must be under workspace outputs'
}
if (-not (Test-Path -LiteralPath $PythonExecutable -PathType Leaf)) { throw 'Provide the Python executable with tests/requirements.txt installed' }
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'Test-ComposeBackup.ps1'))) { throw 'Compose backup verifier is missing' }
$sourceProject = "bluealbum-m0-$RunId"
$restoreProject = "$sourceProject-restore"
$composeFile = Join-Path $runtimeRoot 'docker-compose.yml'
$overrideFile = Join-Path $ArtifactDirectory 'compose.override.yml'
$sourceEnvironment = Join-Path $ArtifactDirectory 'source.env'
$restoreEnvironment = Join-Path $ArtifactDirectory 'restore.env'
$browserArtifacts = Join-Path $ArtifactDirectory 'browser'
$browserTemporary = Join-Path $browserArtifacts 'tmp'
$sourceUrl = 'http://127.0.0.1:35173'
$restoreUrl = 'http://127.0.0.1:45173'
$checks = [System.Collections.Generic.List[object]]::new()
$ownedProjects = [System.Collections.Generic.List[object]]::new()
$succeeded = $false
$snapshot = @{}
$minimumHostMemoryKiB = if ($ResourceMode -eq 'Constrained') { [long](2816MB / 1KB) } else { [long](4GB / 1KB) }
$containerBudgetMiB = if ($ResourceMode -eq 'Constrained') { 1792 } else { $null }

function New-ComposeSecret {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Confirm-Check([bool]$Passed, [string]$Name) {
    $checks.Add(@{Name = $Name; Passed = $Passed})
    if (-not $Passed) { throw "Compose verification failed: $Name" }
    Write-Host "PASS $Name"
}

function Assert-PytestJunitReport([string]$ReportPath, [string]$SuiteName, [int]$ExpectedTestCases, [int]$ExitCode) {
    if ($ExpectedTestCases -lt 1) { throw "$SuiteName expected testcase count must be positive" }
    if ($ExitCode -ne 0) { throw "$SuiteName pytest run failed with exit code $ExitCode" }
    if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) { throw "$SuiteName pytest run did not produce a JUnit report" }

    try { [xml]$report = Get-Content -LiteralPath $ReportPath -Raw }
    catch { throw "$SuiteName JUnit report is not valid XML" }

    $suites = @($report.SelectNodes('//testsuite'))
    if ($suites.Count -eq 0) { throw "$SuiteName JUnit report contains no test suites" }
    foreach ($suite in $suites) {
        if ([int]$suite.failures -or [int]$suite.errors -or [int]$suite.skipped) {
            throw "$SuiteName JUnit report contains failures, errors, or skipped tests"
        }
    }
    $testCases = @($report.SelectNodes('//testcase'))
    if ($testCases.Count -lt 1) { throw "$SuiteName JUnit report contains no executed testcases" }
    if ($testCases.Count -ne $ExpectedTestCases) {
        throw "$SuiteName JUnit report contains $($testCases.Count) executed testcases; expected $ExpectedTestCases"
    }
    if (@($report.SelectNodes('//testcase/failure')).Count -or
        @($report.SelectNodes('//testcase/error')).Count -or
        @($report.SelectNodes('//testcase/skipped')).Count) {
        throw "$SuiteName JUnit report contains testcase failures, errors, or skipped tests"
    }
    return $testCases.Count
}

function Invoke-VerificationCompose([string]$Project, [string]$EnvironmentFile, [string[]]$Arguments) {
    if ($Project -notin @($sourceProject, $restoreProject)) { throw 'Unexpected Compose project' }
    & docker compose --project-directory $runtimeRoot -p $Project --env-file $EnvironmentFile -f $composeFile -f $overrideFile @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Compose operation failed for owned project $Project" }
}

function Send-VerificationRequest([string]$Base, [string]$Method, [string]$Path, $Body, [string]$Token = '', [int]$Expected = 200) {
    $options = @{
        Uri = "$Base/api/v1$Path"; Method = $Method; TimeoutSec = 20; SkipHttpErrorCheck = $true
        Headers = @{'X-Device-Id' = "m0-$RunId"}
    }
    if ($Token) { $options.Headers.Authorization = "Bearer $Token" }
    if ($null -ne $Body) { $options.ContentType = 'application/json'; $options.Body = ConvertTo-Json $Body -Depth 8 -Compress }
    $response = Invoke-WebRequest @options
    if ($response.StatusCode -ne $Expected) { throw "$Method $Path returned $($response.StatusCode), expected $Expected" }
    if ($response.Content) { return $response.Content | ConvertFrom-Json }
}

function Get-ComposeProjectResources([string]$Project) {
    $containers = @(& docker ps -aq --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw "Docker container inventory failed for $Project" }
    $volumes = @(& docker volume ls -q --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw "Docker volume inventory failed for $Project" }
    $networks = @(& docker network ls -q --filter "label=com.docker.compose.project=$Project")
    if ($LASTEXITCODE -ne 0) { throw "Docker network inventory failed for $Project" }
    return [pscustomobject]@{ Containers = $containers; Volumes = $volumes; Networks = $networks }
}

function Assert-ProjectUnused([string]$Project) {
    $resources = Get-ComposeProjectResources $Project
    if (@($resources.Containers + $resources.Volumes + $resources.Networks | Where-Object { $_ }).Count) {
        throw 'This verification project already has containers, volumes, or networks; use a fresh RunId'
    }
}

New-Item -ItemType Directory -Path $ArtifactDirectory, $browserArtifacts, $browserTemporary -Force | Out-Null
foreach ($project in @($sourceProject, $restoreProject)) { Assert-ProjectUnused $project }
[IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'checks.json'), (ConvertTo-Json @{
    RunId = $RunId; ResourceMode = $ResourceMode; Succeeded = $false; Phase = 'preflight'; Checks = @(); CleanupErrors = @()
}))
$portKeys = @('MYSQL', 'REDIS', 'RABBITMQ', 'RABBITMQ_MANAGEMENT', 'BACKEND', 'NOTIFICATION', 'GATEWAY', 'FRONTEND')
$ports = @(33306, 36379, 35673, 35674, 38080, 38081, 39090, 35173, 43306, 46379, 45673, 45674, 48080, 48081, 49090, 45173)
Assert-RuntimePortsAvailable -Ports $ports
$hostMemory = Get-RuntimeHostMemorySnapshot
[IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'host-memory-before.json'), (ConvertTo-Json @{
    ResourceMode = $ResourceMode
    MinimumFreeCommitKiB = if ($hostMemory.Platform -eq 'Windows') { $minimumHostMemoryKiB } else { $null }
    MinimumMemoryMetricKiB = $minimumHostMemoryKiB
    ContainerBudgetMiB = $containerBudgetMiB
    FreePhysicalKiB = $hostMemory.FreePhysicalKiB
    FreeCommitKiB = $hostMemory.FreeCommitKiB
    TotalCommitKiB = $hostMemory.TotalCommitKiB
    MemoryMetric = $hostMemory.MemoryMetric
    MemoryMetricKiB = $hostMemory.MemoryMetricKiB
    MemAvailableKiB = $hostMemory.MemAvailableKiB
    SwapFreeKiB = $hostMemory.SwapFreeKiB
}))
if (-not (Test-RuntimeHostMemoryThreshold -Snapshot $hostMemory -MinimumKiB $minimumHostMemoryKiB)) {
    $memoryDescription = if ($hostMemory.Platform -eq 'Linux') {
        'available MemAvailable+SwapFree memory'
    } else {
        'available host commit memory'
    }
    if ($ResourceMode -eq 'Constrained') {
        throw "At least 2816 MiB of $memoryDescription is required before starting the constrained isolated stack"
    }
    throw "At least 4 GiB of $memoryDescription is required before starting the isolated stack"
}

try {
    $credentials = @(
        'DB_NAME=bluealbum_m0', 'NOTIFICATION_DB_NAME=bluealbum_m0_notifications', 'DB_USER=bluealbum',
        "DB_PASSWORD=$(New-ComposeSecret)", "DB_ROOT_PASSWORD=$(New-ComposeSecret)", "REDIS_PASSWORD=$(New-ComposeSecret)",
        'RABBITMQ_USER=bluealbum', "RABBITMQ_PASSWORD=$(New-ComposeSecret)", "JWT_SECRET=$(New-ComposeSecret)",
        'JWT_ISSUER=MagicAlbum', "INTERNAL_SERVICE_TOKEN=$(New-ComposeSecret)", 'APP_ADMIN_EMAIL='
    )
    $sourceLines = @($credentials)
    $restoreLines = @($credentials)
    for ($index = 0; $index -lt $portKeys.Count; $index++) {
        $sourceLines += "COMPOSE_$($portKeys[$index])_PORT=$($ports[$index])"
        $restoreLines += "COMPOSE_$($portKeys[$index])_PORT=$($ports[$index + 8])"
    }
    [IO.File]::WriteAllLines($sourceEnvironment, $sourceLines)
    [IO.File]::WriteAllLines($restoreEnvironment, $restoreLines)
    # Shell variables take precedence over --env-file during Compose substitution.
    foreach ($line in @($sourceLines + $restoreLines)) {
        $name = ($line -split '=', 2)[0]
        if (-not $snapshot.ContainsKey($name)) { $snapshot[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
        Remove-Item -LiteralPath "Env:$name" -ErrorAction SilentlyContinue
    }
    $override = @"
services:
  backend:
    image: bluealbum-m0-backend:$RunId
    environment:
      JAVA_OPTS: '-Xms32m -Xmx384m -XX:ActiveProcessorCount=2'
  notification-service:
    image: bluealbum-m0-notification:$RunId
    environment:
      JAVA_OPTS: '-Xms32m -Xmx256m -XX:ActiveProcessorCount=2'
  gateway:
    image: bluealbum-m0-gateway:$RunId
    environment:
      JAVA_OPTS: '-Xms32m -Xmx256m -XX:ActiveProcessorCount=2'
  frontend:
    image: bluealbum-m0-frontend:$RunId
"@
    if ($ResourceMode -eq 'Constrained') {
        # Keep Compose's $$ escape literal; never interpolate a process credential into the override.
        $redisPasswordReference = '$$REDIS_PASSWORD'
        $override = @"
services:
  mysql:
    mem_limit: 320m
    memswap_limit: 320m
    cpus: 2.0
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --innodb-buffer-pool-size=64M
      - --innodb-log-buffer-size=8M
      - --performance-schema=OFF
      - --max-connections=32
      - --thread-cache-size=8
      - --table-open-cache=256
      - --mysqlx=0
  redis:
    mem_limit: 48m
    memswap_limit: 48m
    cpus: 1.0
    command:
      - sh
      - -c
      - exec redis-server --appendonly yes --requirepass "$redisPasswordReference" --maxmemory 16mb --maxmemory-policy noeviction
  rabbitmq:
    mem_limit: 224m
    memswap_limit: 224m
    cpus: 2.0
    environment:
      RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: '+S 2:2'
  backend:
    image: bluealbum-m0-backend:$RunId
    mem_limit: 576m
    memswap_limit: 576m
    cpus: 2.0
    environment:
      JAVA_OPTS: '-Xms32m -Xmx224m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=32m -XX:ActiveProcessorCount=2'
  notification-service:
    image: bluealbum-m0-notification:$RunId
    mem_limit: 288m
    memswap_limit: 288m
    cpus: 2.0
    environment:
      JAVA_OPTS: '-Xms16m -Xmx96m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=16m -XX:ActiveProcessorCount=2'
  gateway:
    image: bluealbum-m0-gateway:$RunId
    mem_limit: 288m
    memswap_limit: 288m
    cpus: 2.0
    environment:
      JAVA_OPTS: '-Xms16m -Xmx80m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:MaxDirectMemorySize=16m -XX:ActiveProcessorCount=2'
  frontend:
    image: bluealbum-m0-frontend:$RunId
    mem_limit: 48m
    memswap_limit: 48m
    cpus: 1.0
    environment:
      NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE: '1'
"@
    }
    [IO.File]::WriteAllText($overrideFile, $override)
    Invoke-VerificationCompose $sourceProject $sourceEnvironment @('config', '--quiet')
    if (-not $NoBuild) {
        foreach ($service in @('backend', 'notification-service', 'gateway', 'frontend')) {
            Invoke-VerificationCompose $sourceProject $sourceEnvironment @('build', $service)
        }
    }
    $ownedProjects.Add(@{Name = $sourceProject; Environment = $sourceEnvironment})
    Invoke-VerificationCompose $sourceProject $sourceEnvironment @('up', '-d', '--wait', '--wait-timeout', '300', '--no-build')
    Confirm-Check $true 'Complete source Compose stack is healthy'

    $environmentValues = @{
        TEST_BASE_URL = $sourceUrl; TEST_API_BASE_URL = 'http://127.0.0.1:39090/api/v1'
        TEST_PLAYWRIGHT_CHANNEL = $BrowserChannel; TEST_ARTIFACT_DIR = $browserArtifacts
        TEST_EMAIL = ''; TEST_PHONE = ''; TEST_PASSWORD = ''
        TEMP = $browserTemporary; TMP = $browserTemporary; TMPDIR = $browserTemporary
        PYTHONIOENCODING = 'utf-8'; PYTHONUTF8 = '1'
    }
    foreach ($entry in $environmentValues.GetEnumerator()) {
        $snapshot[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    Push-Location $runtimeRoot
    try {
        $apiReportPath = Join-Path $ArtifactDirectory 'api-junit.xml'
        & $PythonExecutable -m pytest -c pytest.ini `
            tests/api/test_ci_contract.py tests/api/test_bookmarks.py tests/api/test_comments.py `
            tests/api/test_threads.py tests/api/test_user.py -q --junitxml $apiReportPath
        $apiExitCode = $LASTEXITCODE
        $null = Assert-PytestJunitReport $apiReportPath 'API 14' 14 $apiExitCode
        Confirm-Check $true 'API 14 contract tests execute with API read-back'

        $seleniumReportPath = Join-Path $ArtifactDirectory 'selenium-junit.xml'
        & $PythonExecutable -m pytest -c pytest.ini tests/e2e/selenium/test_ci_smoke.py `
            --selenium-browser=chrome -q --junitxml $seleniumReportPath
        $seleniumExitCode = $LASTEXITCODE
        $null = Assert-PytestJunitReport $seleniumReportPath 'Selenium 1' 1 $seleniumExitCode
        Confirm-Check $true 'Selenium 1 smoke test executes with UI and API read-back'

        $browserReportPath = Join-Path $ArtifactDirectory 'browser-results.xml'
        & $PythonExecutable -m pytest -c pytest.ini tests/e2e/playwright/test_m0_journey.py -q --junitxml $browserReportPath
        $playwrightExitCode = $LASTEXITCODE
        $null = Assert-PytestJunitReport $browserReportPath 'Playwright 1' 1 $playwrightExitCode
        Confirm-Check $true 'Playwright 1 browser journey executes with independent accounts and API read-back'
    } finally { Pop-Location }

    $sourceIds = @(& docker ps -q --filter "label=com.docker.compose.project=$sourceProject")
    if ($LASTEXITCODE -ne 0 -or -not $sourceIds.Count) { throw 'Cannot sample owned containers' }
    & docker stats --no-stream --format '{{json .}}' @sourceIds | Set-Content (Join-Path $ArtifactDirectory 'container-resources.jsonl')
    if ($LASTEXITCODE -ne 0) { throw 'Container resource sampling failed' }
    $latencies = @()
    for ($sampleIndex = 0; $sampleIndex -lt 5; $sampleIndex++) {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $null = Send-VerificationRequest $sourceUrl GET '/threads?page=1&size=10' $null
        $watch.Stop()
        $latencies += $watch.ElapsedMilliseconds
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'latency-samples.json'), (ConvertTo-Json @{
        Endpoint = '/api/v1/threads?page=1&size=10'; SamplesMs = $latencies
        Scope = 'Five sequential local samples; not a capacity benchmark'
    }))

    $accountPassword = 'Aa1!' + (New-ComposeSecret)
    $accountEmail = "backup-$RunId@example.test"
    $account = Send-VerificationRequest $sourceUrl POST '/users/register' @{
        username = "backup-$RunId"; email = $accountEmail; password = $accountPassword
        phone = ('138' + (Get-Random -Minimum 10000000 -Maximum 99999999))
    } '' 201
    $login = Send-VerificationRequest $sourceUrl POST '/auth/login' @{email = $accountEmail; password = $accountPassword}
    $sections = Send-VerificationRequest $sourceUrl GET '/sections' $null
    $thread = Send-VerificationRequest $sourceUrl POST '/threads' @{
        sectionId = $sections.items[0].id; title = "M0 backup $RunId"; content = 'Persisted restore verification'
    } $login.accessToken 201
    $null = Send-VerificationRequest $sourceUrl POST "/threads/$($thread.id)/bookmark" $null $login.accessToken
    $imageBytes = Get-RuntimePngFixtureBytes
    $client = [Net.Http.HttpClient]::new()
    $multipart = [Net.Http.MultipartFormDataContent]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(20)
        $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $login.accessToken)
        $part = [Net.Http.ByteArrayContent]::new($imageBytes)
        $part.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('image/png')
        $multipart.Add($part, 'file', 'm0-image.png')
        $upload = $client.PostAsync("$sourceUrl/api/v1/uploads/images", $multipart).GetAwaiter().GetResult()
        Confirm-Check ([int]$upload.StatusCode -eq 200) 'Image upload succeeds through container frontend and gateway'
        $image = $upload.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    } finally { $multipart.Dispose(); $client.Dispose() }
    if ($image.url -notmatch '^/uploads/[A-Za-z0-9_./-]+$' -or $image.url.Contains('..')) { throw 'Unexpected image URL' }
    $beforeImage = Invoke-WebRequest "$sourceUrl$($image.url)" -TimeoutSec 15
    Confirm-Check (($beforeImage.Headers['Content-Type'] -like 'image/*') -and
        [Linq.Enumerable]::SequenceEqual([byte[]]$beforeImage.Content, $imageBytes)) 'Uploaded image is served as exact image bytes'
    $null = Send-VerificationRequest $sourceUrl PATCH "/threads/$($thread.id)" @{
        title = $thread.title; content = "Persisted restore verification ![M0]($($image.url))"
    } $login.accessToken
    $stageFixture = $null
    if ($IncludeStageFixtures) {
        # Bootstrap only after this isolated fixture account exists. Restart alone
        # cannot apply changed Compose environment values; recreate this backend.
        foreach ($environmentFile in @($sourceEnvironment, $restoreEnvironment)) {
            $lines = [IO.File]::ReadAllLines($environmentFile) | ForEach-Object {
                if ($_.StartsWith('APP_ADMIN_EMAIL=')) { "APP_ADMIN_EMAIL=$accountEmail" } else { $_ }
            }
            [IO.File]::WriteAllLines($environmentFile, [string[]]$lines)
        }
        Invoke-VerificationCompose $sourceProject $sourceEnvironment @('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '240', '--no-build', 'backend')
        $otherEmail = "restore-other-$RunId@example.test"
        $otherPassword = 'Aa1!' + (New-ComposeSecret)
        $other = Send-VerificationRequest $sourceUrl POST '/users/register' @{
            username = "restore-other-$RunId"; email = $otherEmail; password = $otherPassword
            phone = ('139' + (Get-Random -Minimum 10000000 -Maximum 99999999))
        } '' 201
        $otherLogin = Send-VerificationRequest $sourceUrl POST '/auth/login' @{email = $otherEmail; password = $otherPassword}
        $stageFixture = New-StageRestoreFixture -BaseUrl $sourceUrl -Token $login.accessToken -AccountId $account.id `
            -ThreadId $thread.id -SectionId $sections.items[0].id -RunId $RunId `
            -OtherToken $otherLogin.accessToken -OtherUserId $other.id
        Confirm-Check $true 'Stage permissions, summary ledger and notification fixtures persist through APIs'
    }
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'fixture.json'), (ConvertTo-Json @{
        ThreadId = $thread.id; AccountId = $account.id; ImageUrl = $image.url
    }))

    Invoke-VerificationCompose $sourceProject $sourceEnvironment @('restart', 'notification-service', 'backend', 'gateway', 'frontend')
    Invoke-VerificationCompose $sourceProject $sourceEnvironment @('up', '-d', '--wait', '--wait-timeout', '240', '--no-build')
    $restartedThread = Send-VerificationRequest $sourceUrl GET "/threads/$($thread.id)" $null
    Confirm-Check ($restartedThread.title -eq $thread.title -and $restartedThread.content.Contains($image.url)) 'Application restart preserves persisted thread and image reference'
    Start-Sleep -Seconds 3
    if ($ResourceMode -eq 'Constrained') {
        Invoke-VerificationCompose $sourceProject $sourceEnvironment @('stop', 'frontend', 'gateway', 'backend', 'notification-service', 'redis', 'rabbitmq')
    } else {
        Invoke-VerificationCompose $sourceProject $sourceEnvironment @('stop', 'frontend', 'gateway', 'backend', 'notification-service')
    }
    $ownedProjects.Add(@{Name = $restoreProject; Environment = $restoreEnvironment})
    & (Join-Path $PSScriptRoot 'Test-ComposeBackup.ps1') -SourceProject $sourceProject -RestoreProject $restoreProject `
        -SourceEnvironmentFile $sourceEnvironment -RestoreEnvironmentFile $restoreEnvironment `
        -ComposeOverrideFile $overrideFile -ArtifactDirectory (Join-Path $ArtifactDirectory 'backup') -RequireStageFixtures:$IncludeStageFixtures
    Confirm-Check $true 'Independent database and uploads restore verification completed'
    $restoredThread = Send-VerificationRequest $restoreUrl GET "/threads/$($thread.id)" $null
    Confirm-Check ($restoredThread.title -eq $thread.title -and $restoredThread.content.Contains($image.url)) 'Restored application reads original thread and image reference'
    $restoredLogin = Send-VerificationRequest $restoreUrl POST '/auth/login' @{email = $accountEmail; password = $accountPassword}
    Confirm-Check ($restoredLogin.user.id -eq $account.id) 'Restored account authenticates with persisted identity'
    $bookmark = Send-VerificationRequest $restoreUrl GET "/threads/$($thread.id)/bookmark" $null $restoredLogin.accessToken
    Confirm-Check ([bool]$bookmark.bookmarked) 'Restored bookmark retains account ownership'
    $afterImage = Invoke-WebRequest "$restoreUrl$($image.url)" -TimeoutSec 15
    Confirm-Check (($afterImage.Headers['Content-Type'] -like 'image/*') -and
        [Linq.Enumerable]::SequenceEqual([byte[]]$afterImage.Content, $imageBytes)) 'Restored application serves the exact uploaded image bytes'
    if ($IncludeStageFixtures) {
        $restoredOtherLogin = Send-VerificationRequest $restoreUrl POST '/auth/login' @{email = $otherEmail; password = $otherPassword}
        $stageReadback = Assert-StageRestoreFixture -BaseUrl $restoreUrl -Token $restoredLogin.accessToken `
            -Fixture $stageFixture -OtherToken $restoredOtherLogin.accessToken
        [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'stage-restore-readback.json'), (ConvertTo-Json $stageReadback -Depth 10))
        Confirm-Check $true 'Restored permissions, summary ledger and notification fixtures match API readback'
    }
    $succeeded = $true
} finally {
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    for ($index = $ownedProjects.Count - 1; $index -ge 0; $index--) {
        $owned = $ownedProjects[$index]
        try {
            $ownedIds = @(& docker ps -aq --filter "label=com.docker.compose.project=$($owned.Name)")
            if ($LASTEXITCODE -ne 0) {
                $cleanupErrors.Add("Evidence inventory failed: $($owned.Name)")
                $ownedIds = @()
            }
            if ($ownedIds.Count) {
                & docker inspect --format '{{.Name}} {{json .State.Health}}' @ownedIds 2>&1 |
                    Set-Content (Join-Path $ArtifactDirectory "$($owned.Name)-health.log")
                if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add("Health evidence incomplete: $($owned.Name)") }
            }
            $null = & docker compose --project-directory $runtimeRoot -p $owned.Name --env-file $owned.Environment -f $composeFile -f $overrideFile logs --no-color --tail 300 2>&1 |
                Set-Content (Join-Path $ArtifactDirectory "$($owned.Name).log")
            if ($LASTEXITCODE -ne 0) { $cleanupErrors.Add("Log evidence incomplete: $($owned.Name)") }
            Invoke-VerificationCompose $owned.Name $owned.Environment @('down', '--volumes', '--remove-orphans')
            $remaining = Get-ComposeProjectResources $owned.Name
            if (@($remaining.Containers + $remaining.Volumes + $remaining.Networks | Where-Object { $_ }).Count) {
                throw 'Owned containers, volumes, or networks remain'
            }
        } catch { $cleanupErrors.Add("Cleanup incomplete: $($owned.Name)") }
    }
    foreach ($file in @($sourceEnvironment, $restoreEnvironment)) {
        try { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file } } catch { $cleanupErrors.Add('Credential cleanup incomplete') }
    }
    # Keep Compose isolated through cleanup; inherited empty credentials must not
    # override the generated env files before their owned projects are removed.
    foreach ($entry in $snapshot.GetEnumerator()) {
        if ($null -eq $entry.Value) { Remove-Item -LiteralPath "Env:$($entry.Key)" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    }
    $succeeded = $succeeded -and $cleanupErrors.Count -eq 0
    [IO.File]::WriteAllText((Join-Path $ArtifactDirectory 'checks.json'), (ConvertTo-Json @{
        RunId = $RunId; ResourceMode = $ResourceMode; Succeeded = $succeeded; Checks = $checks.ToArray(); CleanupErrors = $cleanupErrors.ToArray()
    } -Depth 6))
    if ($cleanupErrors.Count) { throw ($cleanupErrors -join '; ') }
}
Write-Host "Completed $($checks.Count) Compose verification checks; owned projects and temporary credentials cleaned."

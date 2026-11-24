#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$ArtifactDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs/chat-data-migration')
)

# This rehearsal deliberately uses only a disposable, docker-created MySQL instance.
# It does not start an application, touch the local Compose stack, or claim that
# production routing/Flyway ownership has changed.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputsRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'outputs')).TrimEnd([IO.Path]::DirectorySeparatorChar)
$ArtifactDirectory = [IO.Path]::GetFullPath($ArtifactDirectory)
$artifactPrefix = $outputsRoot + [IO.Path]::DirectorySeparatorChar
if (-not $ArtifactDirectory.StartsWith($artifactPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ArtifactDirectory must be under the repository outputs directory.'
}
New-Item -ItemType Directory -Path $ArtifactDirectory -Force | Out-Null

$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$containerName = "bluealbum-chat-migration-$runId"
$containerLabelKey = 'com.bluealbum.chat-migration-run'
$containerLabelValue = $runId
$sourceDatabase = "ba_chat_core_$runId"
$targetDatabase = "ba_chat_target_$runId"
$sourceUser = "ba_chat_src_$runId"
$targetUser = "ba_chat_target_$runId"
$rootPassword = $null
$sourcePassword = $null
$targetPassword = $null
$ownedContainer = $false
$scriptExitCode = 1
$failureType = $null
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$checks = [System.Collections.Generic.List[object]]::new()

$summary = [ordered]@{
    Status = 'FAILED'
    RunId = $runId
    ArtifactDirectory = $ArtifactDirectory
    SourceDatabase = $sourceDatabase
    TargetDatabase = $targetDatabase
    SourceSchema = 'Current chat migrations applied to the source fixture'
    TargetSchema = 'Independent five-table chat storage baseline; chat_blocks and user_follows remain outside target'
    Checks = $checks
    MigrationFiles = @()
    TargetTables = @()
    InitialSnapshots = [ordered]@{}
    PostWriteTargetSnapshot = $null
    FinalSnapshots = [ordered]@{}
    Cleanup = [ordered]@{ ContainerRemoved = $false; Succeeded = $false }
    FailureType = $null
    Notes = @(
        'No application process or production service was started; the source writers were simulated as stopped.'
        'The target schema uses the independent chat storage baseline directly; this rehearsal does not invoke Flyway.'
        'Application routing, production cutover, and independent target migration history are not claimed.'
        'Checks contain row counts, hashes, watermarks, and booleans only; message content and credentials are excluded.'
    )
}

function New-Secret {
    $bytes = [byte[]]::new(32)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return [Convert]::ToHexString($bytes).ToLowerInvariant()
}

function Add-Check {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Passed
    )

    $checks.Add([ordered]@{ Name = $Name; Passed = $Passed })
    if (-not $Passed) {
        throw "Verification check failed: $Name"
    }
    Write-Host "PASS $Name"
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string]$Purpose,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$InputText
    )

    if ($null -ne $InputText) {
        $output = @($InputText | & docker @Arguments 2>&1)
    } else {
        $output = @(& docker @Arguments 2>&1)
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Purpose failed (exit code $exitCode)."
    }
    return @($output | ForEach-Object { [string]$_ })
}

function Invoke-Sql {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('root', 'source', 'target')][string]$Account,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Sql,
        [switch]$Tcp,
        [switch]$AllowError
    )

    switch ($Account) {
        'root' { $user = 'root'; $password = $rootPassword }
        'source' { $user = $sourceUser; $password = $sourcePassword }
        'target' { $user = $targetUser; $password = $targetPassword }
    }
    $transport = if ($Tcp) { @('--protocol=tcp', '--host=127.0.0.1') } else { @('--protocol=socket') }
    $arguments = @('exec', '-i', '-e', "MYSQL_PWD=$password", $containerName, 'mysql') + $transport + @(
        "--user=$user", "--database=$Database",
        '--batch', '--skip-column-names', '--raw', '--silent'
    )
    $output = @($Sql | & docker @arguments 2>&1 | ForEach-Object { [string]$_ })
    $exitCode = $LASTEXITCODE
    if ($AllowError) {
        return [ordered]@{ ExitCode = $exitCode; Output = $output }
    }
    if ($exitCode -ne 0) {
        throw "SQL operation failed for the $Account account."
    }
    if ($output.Count -eq 0) {
        return ''
    }
    return ($output -join "`n").TrimEnd("`r", "`n")
}

function Test-SqlError {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('source', 'target')][string]$Account,
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Sql,
        [Parameter(Mandatory = $true)][int]$ExpectedError
    )

    $result = Invoke-Sql -Account $Account -Database $Database -Sql $Sql -AllowError
    return ($result.ExitCode -ne 0 -and (($result.Output -join ' ') -match "ERROR $ExpectedError\b"))
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-TableNames {
    param([Parameter(Mandatory = $true)][string]$Database)

    $result = Invoke-Sql -Account root -Database 'information_schema' -Sql @"
SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA='$Database' AND TABLE_TYPE='BASE TABLE'
ORDER BY TABLE_NAME;
"@
    if ([string]::IsNullOrWhiteSpace($result)) { return @() }
    return @($result -split "`r?`n" | Where-Object { $_ })
}

$tableDefinitions = [ordered]@{
    chat_conversations = [ordered]@{
        Json = 'JSON_ARRAY(id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision)'
        Order = 'id'
        PrimaryColumns = @('id')
        AutoIncrement = $true
    }
    chat_members = [ordered]@{
        Json = 'JSON_ARRAY(conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq)'
        Order = 'conversation_id,user_id'
        PrimaryColumns = @('conversation_id', 'user_id')
        AutoIncrement = $false
    }
    chat_messages = [ordered]@{
        Json = 'JSON_ARRAY(id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at,recalled_at)'
        Order = 'id'
        PrimaryColumns = @('id')
        AutoIncrement = $true
    }
    chat_preferences = [ordered]@{
        Json = 'JSON_ARRAY(user_id,allow_requests)'
        Order = 'user_id'
        PrimaryColumns = @('user_id')
        AutoIncrement = $false
    }
    chat_daily_usage = [ordered]@{
        Json = 'JSON_ARRAY(user_id,quota_date,used_count)'
        Order = 'user_id,quota_date'
        PrimaryColumns = @('user_id', 'quota_date')
        AutoIncrement = $false
    }
}

function Get-TableSnapshot {
    param([Parameter(Mandatory = $true)][string]$Database)

    $snapshot = [ordered]@{}
    foreach ($table in $tableDefinitions.Keys) {
        $definition = $tableDefinitions[$table]
        $rowOutput = Invoke-Sql -Account root -Database $Database -Sql @"
SELECT $($definition.Json)
FROM $Database.$table
ORDER BY $($definition.Order);
"@
        $rows = if ([string]::IsNullOrEmpty($rowOutput)) { @() } else { @($rowOutput -split "`r?`n") }
        $primaryMax = [ordered]@{}
        foreach ($column in $definition.PrimaryColumns) {
            $maxOutput = Invoke-Sql -Account root -Database $Database -Sql "SELECT COALESCE(MAX($column),0) FROM $Database.$table;"
            if ($column -eq 'quota_date') {
                $primaryMax[$column] = [string]$maxOutput
            } else {
                $primaryMax[$column] = [Int64]$maxOutput
            }
        }
        $autoIncrement = $null
        if ($definition.AutoIncrement) {
            $autoOutput = Invoke-Sql -Account root -Database 'information_schema' -Sql @"
SELECT COALESCE(AUTO_INCREMENT,0)
FROM information_schema.TABLES
WHERE TABLE_SCHEMA='$Database' AND TABLE_NAME='$table';
"@
            $autoIncrement = [Int64]$autoOutput
        }
        $snapshot[$table] = [ordered]@{
            RowCount = [Int64]$rows.Count
            FullRowSha256 = Get-Sha256Hex -Value ([string]::Join("`n", $rows))
            PrimaryKeyMax = $primaryMax
            AutoIncrement = $autoIncrement
        }
    }
    return $snapshot
}

function Test-SnapshotEqual {
    param(
        [Parameter(Mandatory = $true)]$Left,
        [Parameter(Mandatory = $true)]$Right
    )

    foreach ($table in $tableDefinitions.Keys) {
        $leftTable = $Left[$table]
        $rightTable = $Right[$table]
        if ($leftTable.RowCount -ne $rightTable.RowCount -or
            $leftTable.FullRowSha256 -ne $rightTable.FullRowSha256 -or
            $leftTable.AutoIncrement -ne $rightTable.AutoIncrement) {
            return $false
        }
        foreach ($column in $tableDefinitions[$table].PrimaryColumns) {
            if ($leftTable.PrimaryKeyMax[$column] -ne $rightTable.PrimaryKeyMax[$column]) {
                return $false
            }
        }
    }
    return $true
}

function Test-PrimaryWatermarksEqual {
    param(
        [Parameter(Mandatory = $true)]$Left,
        [Parameter(Mandatory = $true)]$Right
    )

    foreach ($table in $tableDefinitions.Keys) {
        foreach ($column in $tableDefinitions[$table].PrimaryColumns) {
            if ($Left[$table].PrimaryKeyMax[$column] -ne $Right[$table].PrimaryKeyMax[$column]) {
                return $false
            }
        }
        if ($Left[$table].AutoIncrement -ne $Right[$table].AutoIncrement) {
            return $false
        }
    }
    return $true
}

function Get-OrphanCounts {
    param([Parameter(Mandatory = $true)][string]$Database)

    $members = [Int64](Invoke-Sql -Account root -Database $Database -Sql @"
SELECT COUNT(*)
FROM $Database.chat_members m
LEFT JOIN $Database.chat_conversations c ON c.id=m.conversation_id
WHERE c.id IS NULL;
"@)
    $messages = [Int64](Invoke-Sql -Account root -Database $Database -Sql @"
SELECT COUNT(*)
FROM $Database.chat_messages m
LEFT JOIN $Database.chat_conversations c ON c.id=m.conversation_id
WHERE c.id IS NULL;
"@)
    return [ordered]@{ Members = $members; Messages = $messages; Total = $members + $messages }
}

function Write-ChecksFile {
    param([Parameter(Mandatory = $true)][string]$Path)

    $summary.FailureType = $failureType
    $json = $summary | ConvertTo-Json -Depth 20
    $utf8NoBom = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

try {
    $rootPassword = New-Secret
    $sourcePassword = New-Secret
    $targetPassword = New-Secret
    Add-Check -Name 'Synthetic source and target application credentials are distinct' -Passed ($sourcePassword -ne $targetPassword)

    $chatMigration1Path = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V20261120_1__create_chat.sql'
    $chatMigration2Path = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V20261120_2__add_chat_history_revision.sql'
    $sourceRecallMigrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V20261124_1__chat_message_recall.sql'
    $targetStorageMigrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/chat/mysql/V1__create_chat_storage.sql'
    $targetRecallMigrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/chat/mysql/V4__chat_message_recall.sql'
    $usersMigrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V2__create_users.sql'
    $followsMigrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V20__create_user_follows.sql'
    foreach ($migrationPath in @($chatMigration1Path, $chatMigration2Path, $sourceRecallMigrationPath, $targetStorageMigrationPath, $targetRecallMigrationPath, $usersMigrationPath, $followsMigrationPath)) {
        if (-not (Test-Path -LiteralPath $migrationPath -PathType Leaf)) {
            throw 'Required current migration file is missing.'
        }
    }
    $chatMigration1 = [IO.File]::ReadAllText($chatMigration1Path)
    $chatMigration2 = [IO.File]::ReadAllText($chatMigration2Path)
    $sourceRecallMigration = [IO.File]::ReadAllText($sourceRecallMigrationPath)
    $targetStorageMigration = [IO.File]::ReadAllText($targetStorageMigrationPath)
    $targetRecallMigration = [IO.File]::ReadAllText($targetRecallMigrationPath)
    $usersMigration = [IO.File]::ReadAllText($usersMigrationPath)
    $followsMigration = [IO.File]::ReadAllText($followsMigrationPath)
    if ($chatMigration1 -notmatch '(?i)CREATE\s+TABLE\s+chat_conversations' -or
        $chatMigration1 -notmatch '(?i)CREATE\s+TABLE\s+chat_blocks' -or
        $chatMigration2 -notmatch '(?i)history_revision') {
        throw 'Current chat migrations do not contain the expected schema.'
    }
    $summary.MigrationFiles = @(
        [ordered]@{ Name = [IO.Path]::GetFileName($chatMigration1Path); Sha256 = (Get-FileHash -LiteralPath $chatMigration1Path -Algorithm SHA256).Hash.ToLowerInvariant() }
        [ordered]@{ Name = [IO.Path]::GetFileName($chatMigration2Path); Sha256 = (Get-FileHash -LiteralPath $chatMigration2Path -Algorithm SHA256).Hash.ToLowerInvariant() }
        [ordered]@{ Name = [IO.Path]::GetFileName($sourceRecallMigrationPath); Sha256 = (Get-FileHash -LiteralPath $sourceRecallMigrationPath -Algorithm SHA256).Hash.ToLowerInvariant() }
        [ordered]@{ Name = [IO.Path]::GetFileName($targetStorageMigrationPath); Sha256 = (Get-FileHash -LiteralPath $targetStorageMigrationPath -Algorithm SHA256).Hash.ToLowerInvariant() }
        [ordered]@{ Name = [IO.Path]::GetFileName($targetRecallMigrationPath); Sha256 = (Get-FileHash -LiteralPath $targetRecallMigrationPath -Algorithm SHA256).Hash.ToLowerInvariant() }
    )

    if ($targetStorageMigration -match '(?i)chat_blocks|user_follows' -or
        ($targetStorageMigration -notmatch '(?i)CREATE\s+TABLE\s+chat_conversations') -or
        ($targetStorageMigration -notmatch '(?i)CREATE\s+TABLE\s+chat_preferences')) {
        throw 'The independent target storage baseline does not contain the expected five-table schema.'
    }

    $dockerCheck = @(& docker version --format '{{.Server.Version}}' 2>&1)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($dockerCheck -join ' '))) {
        throw 'Docker Engine is unavailable.'
    }
    $existing = @(& docker ps -a --filter "name=^/$containerName$" --format '{{.Names}}' 2>$null)
    if ($LASTEXITCODE -eq 0 -and $existing.Count -gt 0) {
        throw 'The generated verification container name is already occupied.'
    }

    $runArguments = @(
        'run', '-d', '--name', $containerName,
        '--label', "$containerLabelKey=$containerLabelValue",
        '--env', "MYSQL_ROOT_PASSWORD=$rootPassword",
        '--env', "MYSQL_DATABASE=$sourceDatabase",
        'mysql:8.0'
    )
    $null = Invoke-Docker -Purpose 'Create disposable MySQL container' -Arguments $runArguments
    $ownedContainer = $true

    $ready = $false
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        try {
            # The temporary bootstrap server accepts socket connections before restarting.
            if ((Invoke-Sql -Account root -Database $sourceDatabase -Sql 'SELECT 1;' -Tcp) -eq '1') {
                $ready = $true
                break
            }
        } catch {
            # The MySQL entrypoint is still initializing.
        }
        Start-Sleep -Seconds 1
    }
    Add-Check -Name 'Disposable MySQL container is ready' -Passed $ready

    Invoke-Sql -Account root -Database 'mysql' -Sql @"
CREATE DATABASE IF NOT EXISTS $targetDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
"@ | Out-Null

    # Source is the authority for the first copy and receives the unmodified
    # current chat migrations. The target receives the independent five-table
    # storage baseline directly; no target Flyway process is started here.
    Invoke-Sql -Account root -Database $sourceDatabase -Sql $usersMigration | Out-Null
    Invoke-Sql -Account root -Database $sourceDatabase -Sql $followsMigration | Out-Null
    Invoke-Sql -Account root -Database $sourceDatabase -Sql $chatMigration1 | Out-Null
    Invoke-Sql -Account root -Database $sourceDatabase -Sql $chatMigration2 | Out-Null
    Invoke-Sql -Account root -Database $sourceDatabase -Sql $sourceRecallMigration | Out-Null
    Invoke-Sql -Account root -Database $targetDatabase -Sql $targetStorageMigration | Out-Null
    Invoke-Sql -Account root -Database $targetDatabase -Sql $targetRecallMigration | Out-Null

    $targetTables = @(Get-TableNames -Database $targetDatabase)
    $summary.TargetTables = $targetTables
    $expectedTargetTables = @($tableDefinitions.Keys | Sort-Object)
    Add-Check -Name 'Target schema contains exactly five chat-owned tables' -Passed ([string]::Join(',', $targetTables) -eq [string]::Join(',', $expectedTargetTables))
    Add-Check -Name 'Target schema excludes chat_blocks and user_follows' -Passed ($targetTables -notcontains 'chat_blocks' -and $targetTables -notcontains 'user_follows')
    $sourceTables = @(Get-TableNames -Database $sourceDatabase)
    Add-Check -Name 'Source schema contains chat_blocks and user_follows in the core fixture' -Passed ($sourceTables -contains 'chat_blocks' -and $sourceTables -contains 'user_follows')

    $quotaDate = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow, 'China Standard Time').ToString('yyyy-MM-dd')
    # All values below are synthetic fixtures. The script never writes their
    # message bodies to checks.json; they are used only for row-level hashing.
    Invoke-Sql -Account root -Database $sourceDatabase -Sql @"
INSERT INTO users (id,username,email,phone,password_hash,created_at) VALUES
    (100001,'chat-fixture-1','chat-fixture-1@example.test','13800000001','fixture-hash-1','2026-01-01 00:00:00'),
    (100002,'chat-fixture-2','chat-fixture-2@example.test','13800000002','fixture-hash-2','2026-01-01 00:00:00'),
    (100003,'chat-fixture-3','chat-fixture-3@example.test','13800000003','fixture-hash-3','2026-01-01 00:00:00'),
    (100004,'chat-fixture-4','chat-fixture-4@example.test','13800000004','fixture-hash-4','2026-01-01 00:00:00'),
    (100005,'chat-fixture-5','chat-fixture-5@example.test','13800000005','fixture-hash-5','2026-01-01 00:00:00');
INSERT INTO user_follows (follower_id,following_id,created_at) VALUES
    (100001,100002,'2026-01-01 00:00:00'),
    (100002,100001,'2026-01-01 00:00:00');
INSERT INTO chat_conversations
    (id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision)
VALUES
    (700001,100001,100002,100001,100002,'fixture direct','ACTIVE',3,'2026-01-02 00:00:00.000000',0),
    (700002,NULL,NULL,100003,100001,'fixture group','ACTIVE',1,'2026-01-02 00:00:01.000000',0);
INSERT INTO chat_members
    (conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq)
VALUES
    (700001,100001,'ACTIVE','2026-01-02 00:00:00.000000',1,2),
    (700001,100002,'ACTIVE','2026-01-02 00:00:00.000000',1,2),
    (700002,100001,'ACTIVE','2026-01-02 00:00:01.000000',1,1),
    (700002,100003,'INVITED','2026-01-02 00:00:01.000000',1,0);
INSERT INTO chat_messages
    (id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at)
VALUES
    (800001,700001,1,100001,'00000000-0000-4000-8000-000000000001','fixture-message-1',FALSE,'2026-01-02 00:00:00.000000'),
    (800002,700001,2,100002,'00000000-0000-4000-8000-000000000002','fixture-message-2',FALSE,'2026-01-02 00:00:01.000000'),
    (800003,700001,3,100001,'00000000-0000-4000-8000-000000000003','fixture-message-3',FALSE,'2026-01-02 00:00:02.000000'),
    (800004,700002,1,100003,'00000000-0000-4000-8000-000000000004','fixture-message-4',FALSE,'2026-01-02 00:00:03.000000');
INSERT INTO chat_preferences (user_id,allow_requests) VALUES
    (100001,TRUE),(100002,FALSE),(100003,TRUE);
INSERT INTO chat_daily_usage (user_id,quota_date,used_count) VALUES
    (100001,'$quotaDate',2),(100002,'$quotaDate',0);
INSERT INTO chat_blocks (blocker_id,blocked_id) VALUES (100003,100001);
"@ | Out-Null

    Invoke-Sql -Account root -Database 'mysql' -Sql @"
CREATE USER '$sourceUser'@'%' IDENTIFIED BY '$sourcePassword';
CREATE USER '$targetUser'@'%' IDENTIFIED BY '$targetPassword';
GRANT SELECT,INSERT,UPDATE,DELETE ON $sourceDatabase.* TO '$sourceUser'@'%';
GRANT SELECT,INSERT,UPDATE,DELETE ON $targetDatabase.* TO '$targetUser'@'%';
FLUSH PRIVILEGES;
"@ | Out-Null

    Add-Check -Name 'Target application account can read the target schema' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;') -eq '0')
    Add-Check -Name 'Source runtime account can read the source schema' -Passed ((Invoke-Sql -Account source -Database $sourceDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;') -eq '2')
    Add-Check -Name 'Target application account cannot read the core source database' -Passed (Test-SqlError -Account target -Database $sourceDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;' -ExpectedError 1044)
    Add-Check -Name 'Source runtime account cannot read the target database' -Passed (Test-SqlError -Account source -Database $targetDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;' -ExpectedError 1044)

    # No source application was started. This single transaction models a
    # stopped-writer, REPEATABLE READ snapshot and copies only the five target
    # tables. It is intentionally root-owned, not a production cutover path.
    $copySql = @"
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT;
INSERT INTO $targetDatabase.chat_conversations
    (id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision)
SELECT id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision
FROM $sourceDatabase.chat_conversations ORDER BY id;
INSERT INTO $targetDatabase.chat_members
    (conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq)
SELECT conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq
FROM $sourceDatabase.chat_members ORDER BY conversation_id,user_id;
INSERT INTO $targetDatabase.chat_messages
    (id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at,recalled_at)
SELECT id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at,recalled_at
FROM $sourceDatabase.chat_messages ORDER BY id;
INSERT INTO $targetDatabase.chat_preferences (user_id,allow_requests)
SELECT user_id,allow_requests FROM $sourceDatabase.chat_preferences ORDER BY user_id;
INSERT INTO $targetDatabase.chat_daily_usage (user_id,quota_date,used_count)
SELECT user_id,quota_date,used_count FROM $sourceDatabase.chat_daily_usage ORDER BY user_id,quota_date;
COMMIT;
"@
    Invoke-Sql -Account root -Database $targetDatabase -Sql $copySql | Out-Null
    $sourceInitial = Get-TableSnapshot -Database $sourceDatabase
    $targetInitial = Get-TableSnapshot -Database $targetDatabase
    $summary.InitialSnapshots.Source = $sourceInitial
    $summary.InitialSnapshots.Target = $targetInitial
    Add-Check -Name 'Stopped-source snapshot preserves row counts and stable ordered full-row SHA-256' -Passed (Test-SnapshotEqual -Left $sourceInitial -Right $targetInitial)
    Add-Check -Name 'Stopped-source snapshot preserves primary ID watermarks' -Passed (Test-PrimaryWatermarksEqual -Left $sourceInitial -Right $targetInitial)
    $initialOrphans = Get-OrphanCounts -Database $targetDatabase
    Add-Check -Name 'Initial target copy has zero member and message orphans' -Passed ($initialOrphans.Total -eq 0)

    # These writes run under the target application's isolated credential. They
    # cover the rollback/reconciliation cases in the architecture document.
    Invoke-Sql -Account target -Database $targetDatabase -Sql @"
START TRANSACTION;
INSERT INTO chat_conversations
    (id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision)
VALUES
    (700010,100001,100005,100005,100001,'target-only fixture','PENDING',0,'2026-01-03 00:00:00.000000',0);
INSERT INTO chat_members
    (conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq)
VALUES
    (700010,100001,'ACTIVE','2026-01-03 00:00:00.000000',1,0),
    (700010,100005,'INVITED','2026-01-03 00:00:00.000000',1,0);
INSERT INTO chat_messages
    (id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at)
VALUES
    (800010,700001,4,100001,'00000000-0000-4000-8000-000000000010','target-write-fixture',FALSE,'2026-01-03 00:00:01.000000');
UPDATE chat_conversations
SET last_seq=4, updated_at='2026-01-03 00:00:01.000000', history_revision=1
WHERE id=700001;
UPDATE chat_members
SET state='LEFT', visible_from_seq=4, last_read_seq=3
WHERE conversation_id=700001 AND user_id=100002;
UPDATE chat_members
SET last_read_seq=3
WHERE conversation_id=700001 AND user_id=100001;
UPDATE chat_daily_usage
SET used_count=used_count+1
WHERE user_id=100001 AND quota_date='$quotaDate';
UPDATE chat_messages SET hidden=TRUE WHERE id=800002;
UPDATE chat_messages SET recalled_at='2026-01-03 00:00:02.000000' WHERE id=800010;
COMMIT;
"@ | Out-Null
    $replayResult = Invoke-Sql -Account target -Database $targetDatabase -Sql @"
INSERT INTO chat_messages
    (id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at)
VALUES
    (800011,700001,5,100001,'00000000-0000-4000-8000-000000000010','replayed-different-fixture',FALSE,'2026-01-03 00:00:02.000000');
"@ -AllowError
    Add-Check -Name 'Target client request replay is rejected by the unique idempotency key' -Passed ($replayResult.ExitCode -ne 0 -and (($replayResult.Output -join ' ') -match 'ERROR 1062\b'))
    Add-Check -Name 'Target client request replay leaves one message row' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql "SELECT COUNT(*) FROM chat_messages WHERE client_message_id='00000000-0000-4000-8000-000000000010';") -eq '1')
    Add-Check -Name 'Target writes create the new conversation with its supplied ID' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations WHERE id=700010;') -eq '1')
    Add-Check -Name 'Target writes preserve the member leave state and visible sequence' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql 'SELECT state,visible_from_seq,last_read_seq FROM chat_members WHERE conversation_id=700001 AND user_id=100002;') -eq "LEFT`t4`t3")
    Add-Check -Name 'Target writes advance the read cursor' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql 'SELECT last_read_seq FROM chat_members WHERE conversation_id=700001 AND user_id=100001;') -eq '3')
    Add-Check -Name 'Target writes increment the Asia/Shanghai daily quota' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql "SELECT used_count FROM chat_daily_usage WHERE user_id=100001 AND quota_date='$quotaDate';") -eq '3')
    Add-Check -Name 'Target writes hide a message and advance history revision' -Passed ((Invoke-Sql -Account target -Database $targetDatabase -Sql 'SELECT hidden FROM chat_messages WHERE id=800002;') -eq '1' -and (Invoke-Sql -Account target -Database $targetDatabase -Sql 'SELECT history_revision FROM chat_conversations WHERE id=700001;') -eq '1')
    $targetAfterWrites = Get-TableSnapshot -Database $targetDatabase
    $summary.PostWriteTargetSnapshot = $targetAfterWrites
    Add-Check -Name 'Representative target writes changed the target snapshot' -Passed (-not (Test-SnapshotEqual -Left $targetInitial -Right $targetAfterWrites))
    Add-Check -Name 'Target application account still cannot read the core source database' -Passed (Test-SqlError -Account target -Database $sourceDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;' -ExpectedError 1044)

    # Simulate the reverse/rollback path while target writers are stopped. The
    # target rows are authoritative for this transaction; source-only core
    # tables, including chat_blocks and user_follows, are untouched.
    $reverseSql = @"
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT;
INSERT INTO $sourceDatabase.chat_conversations
    (id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision)
SELECT id,direct_low_id,direct_high_id,requester_id,owner_id,title,state,last_seq,updated_at,history_revision
FROM $targetDatabase.chat_conversations
ON DUPLICATE KEY UPDATE
    direct_low_id=VALUES(direct_low_id), direct_high_id=VALUES(direct_high_id), requester_id=VALUES(requester_id),
    owner_id=VALUES(owner_id), title=VALUES(title), state=VALUES(state), last_seq=VALUES(last_seq),
    updated_at=VALUES(updated_at), history_revision=VALUES(history_revision);
INSERT INTO $sourceDatabase.chat_members
    (conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq)
SELECT conversation_id,user_id,state,invited_at,visible_from_seq,last_read_seq
FROM $targetDatabase.chat_members
ON DUPLICATE KEY UPDATE
    state=VALUES(state), invited_at=VALUES(invited_at), visible_from_seq=VALUES(visible_from_seq),
    last_read_seq=VALUES(last_read_seq);
INSERT INTO $sourceDatabase.chat_messages
    (id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at,recalled_at)
SELECT id,conversation_id,seq,sender_id,client_message_id,body,hidden,created_at,recalled_at
FROM $targetDatabase.chat_messages
ON DUPLICATE KEY UPDATE
    conversation_id=VALUES(conversation_id), seq=VALUES(seq), sender_id=VALUES(sender_id),
    client_message_id=VALUES(client_message_id), body=VALUES(body), hidden=VALUES(hidden),
    created_at=VALUES(created_at), recalled_at=VALUES(recalled_at);
INSERT INTO $sourceDatabase.chat_preferences (user_id,allow_requests)
SELECT user_id,allow_requests FROM $targetDatabase.chat_preferences
ON DUPLICATE KEY UPDATE allow_requests=VALUES(allow_requests);
INSERT INTO $sourceDatabase.chat_daily_usage (user_id,quota_date,used_count)
SELECT user_id,quota_date,used_count FROM $targetDatabase.chat_daily_usage
ON DUPLICATE KEY UPDATE used_count=VALUES(used_count);
COMMIT;
"@
    Invoke-Sql -Account root -Database $sourceDatabase -Sql $reverseSql | Out-Null
    $targetWatermarks = $targetAfterWrites
    $sourceConversationNext = [Int64]$targetWatermarks.chat_conversations.AutoIncrement
    $sourceMessageNext = [Int64]$targetWatermarks.chat_messages.AutoIncrement
    Invoke-Sql -Account root -Database $sourceDatabase -Sql @"
ALTER TABLE $sourceDatabase.chat_conversations AUTO_INCREMENT=$sourceConversationNext;
ALTER TABLE $sourceDatabase.chat_messages AUTO_INCREMENT=$sourceMessageNext;
"@ | Out-Null

    $sourceFinal = Get-TableSnapshot -Database $sourceDatabase
    $targetFinal = Get-TableSnapshot -Database $targetDatabase
    $summary.FinalSnapshots.Source = $sourceFinal
    $summary.FinalSnapshots.Target = $targetFinal
    Add-Check -Name 'Reverse reconciliation matches all target rows by count and stable full-row SHA-256' -Passed (Test-SnapshotEqual -Left $sourceFinal -Right $targetFinal)
    Add-Check -Name 'Reverse reconciliation preserves the recalled timestamp' -Passed ((Invoke-Sql -Account source -Database $sourceDatabase -Sql 'SELECT recalled_at IS NOT NULL FROM chat_messages WHERE id=800010;') -eq '1')
    Add-Check -Name 'Reverse reconciliation preserves primary ID watermarks and auto-increment watermarks' -Passed (Test-PrimaryWatermarksEqual -Left $sourceFinal -Right $targetFinal)
    $finalOrphansSource = Get-OrphanCounts -Database $sourceDatabase
    $finalOrphansTarget = Get-OrphanCounts -Database $targetDatabase
    Add-Check -Name 'Reverse reconciliation leaves zero source member and message orphans' -Passed ($finalOrphansSource.Total -eq 0)
    Add-Check -Name 'Reverse reconciliation leaves zero target member and message orphans' -Passed ($finalOrphansTarget.Total -eq 0)
    Add-Check -Name 'Source runtime account remains unable to read the target database after reconciliation' -Passed (Test-SqlError -Account source -Database $targetDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;' -ExpectedError 1044)
    Add-Check -Name 'Target application account remains unable to read the core source database after reconciliation' -Passed (Test-SqlError -Account target -Database $sourceDatabase -Sql 'SELECT COUNT(*) FROM chat_conversations;' -ExpectedError 1044)

    $scriptExitCode = 0
    $summary.Status = 'PASSED'
} catch {
    $failureType = 'verification-failed'
    $summary.Status = 'FAILED'
    Write-Error $_.Exception.Message
} finally {
    if ($ownedContainer) {
        try {
            $inspectFormat = '{{index .Config.Labels "' + $containerLabelKey + '"}}'
            $labelOutput = @(& docker inspect --format $inspectFormat $containerName 2>$null)
            $labelExitCode = $LASTEXITCODE
            $label = ($labelOutput -join '').Trim()
            if ($labelExitCode -ne 0 -or $label -ne $containerLabelValue) {
                throw 'Owned-container label verification failed; refusing cleanup.'
            }
            & docker rm --force --volumes $containerName 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw 'Owned MySQL container removal failed.'
            }
            $summary.Cleanup.ContainerRemoved = $true
        } catch {
            $cleanupErrors.Add('Owned MySQL container cleanup failed.')
        }
    }
    $summary.Cleanup.Succeeded = ($cleanupErrors.Count -eq 0 -and (-not $ownedContainer -or $summary.Cleanup.ContainerRemoved))
    if (-not $summary.Cleanup.Succeeded) {
        $scriptExitCode = 1
        $summary.Status = 'FAILED'
        $failureType = 'cleanup-failed'
    }
    if ($cleanupErrors.Count -gt 0) {
        $summary.Cleanup.Errors = @($cleanupErrors)
    }
    Write-ChecksFile -Path (Join-Path $ArtifactDirectory 'checks.json')
}

if ($scriptExitCode -ne 0) {
    exit $scriptExitCode
}
Write-Host "Completed $($checks.Count) isolated chat data migration checks; owned container removed."

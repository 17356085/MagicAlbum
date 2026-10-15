#Requires -Version 7.4
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SourceProject,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RestoreProject,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SourceEnvironmentFile,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$RestoreEnvironmentFile,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ComposeOverrideFile,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ArtifactDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$script:RepositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:ComposeFile = Join-Path $script:RepositoryRoot 'docker-compose.yml'
$script:RunId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$script:ArtifactRoot = $null
$script:SourceMysqlId = $null
$script:RestoreMysqlId = $null
$script:SourceDumpContainerPath = "/tmp/bluealbum-m0-$($script:RunId)-database.sql"
$script:RestoreDumpContainerPath = "/tmp/bluealbum-m0-$($script:RunId)-restore.sql"
$script:SnapshotTemporaryPaths = [System.Collections.Generic.List[object]]::new()
$failureException = $null
$script:Summary = [ordered]@{
    Status = 'FAILED'
    RunId = $script:RunId
    SourceProject = $SourceProject
    RestoreProject = $RestoreProject
    ArtifactDirectory = $null
    SourceDatabase = $null
    NotificationDatabase = $null
    SourceDumpSha256 = $null
    SourceUploadFileCount = $null
    SourceUploadBytes = $null
    SourceUploadManifestSha256 = $null
    RestoreUploadFileCount = $null
    RestoreUploadBytes = $null
    RestoreUploadManifestSha256 = $null
    UploadComparison = $null
    DatabaseComparisons = @()
    RequiredBusinessTables = @()
    FinalNotificationRuntimeObservation = $null
    Notes = @(
        'Source application services must remain stopped and no business writes may occur during verification.'
        'Flyway metadata and outbox tables are presence-checked but excluded from final content equality because startup may update them.'
        'The notifications table is compared exactly before application startup; final runtime delivery may consume restored outbox rows and add notifications.'
        'The script never runs Compose down or removes a project, volume, or uploaded source artifact.'
    )
    FailureType = $null
    Failure = $null
}

function ConvertTo-AbsolutePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [IO.Path]::GetFullPath($Path)
}

function Assert-PathUnderRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $fullPath = ConvertTo-AbsolutePath $Path
    $fullRoot = (ConvertTo-AbsolutePath $Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $prefix = $fullRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must be under the repository outputs directory."
    }
    return $fullPath
}

function Read-DotEnv {
    param([Parameter(Mandatory = $true)][string]$Path)

    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $trimmed = ([string]$line).Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('#')) {
            continue
        }
        if ($trimmed.StartsWith('export ')) {
            $trimmed = $trimmed.Substring(7).Trim()
        }
        $equals = $trimmed.IndexOf('=')
        if ($equals -le 0) {
            throw "Environment file contains an invalid assignment in $([IO.Path]::GetFileName($Path))."
        }
        $key = $trimmed.Substring(0, $equals).Trim()
        if ($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$') {
            throw "Environment file contains an invalid key in $([IO.Path]::GetFileName($Path))."
        }
        $value = $trimmed.Substring($equals + 1).Trim()
        if (($value.Length -ge 2) -and
            (($value.StartsWith('"') -and $value.EndsWith('"')) -or
             ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

function Get-RequiredDatabaseName {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Values,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$FileDescription
    )

    if (-not $Values.ContainsKey($Key) -or [string]::IsNullOrWhiteSpace([string]$Values[$Key])) {
        throw "$FileDescription must define $Key explicitly."
    }
    $databaseName = ([string]$Values[$Key]).Trim()
    if ($databaseName -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') {
        throw "$FileDescription contains an invalid $Key database identifier."
    }
    if ($databaseName.ToLowerInvariant() -in @('mysql', 'information_schema', 'performance_schema', 'sys')) {
        throw "$FileDescription uses a reserved database name for $Key."
    }
    return $databaseName
}

function ConvertTo-ShellSingleQuoted {
    param([Parameter(Mandatory = $true)][string]$Value)

    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string]$Purpose,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $output = @(& docker @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$Purpose failed (exit code $exitCode)."
    }
    return $output | ForEach-Object { [string]$_ }
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)][string]$Purpose,
        [Parameter(Mandatory = $true)][string]$Project,
        [Parameter(Mandatory = $true)][string]$EnvironmentFile,
        [Parameter(Mandatory = $true)][string[]]$CommandArguments
    )

    $arguments = @(
        'compose',
        '--project-name', $Project,
        '--project-directory', $script:RepositoryRoot,
        '--env-file', $EnvironmentFile,
        '-f', $script:ComposeFile,
        '-f', $script:ComposeOverrideFile
    ) + $CommandArguments
    return Invoke-Docker -Purpose $Purpose -Arguments $arguments
}

function Invoke-ContainerShell {
    param(
        [Parameter(Mandatory = $true)][string]$Purpose,
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$Script,
        [switch]$Interactive
    )

    $arguments = @('exec')
    if ($Interactive) {
        $arguments += '-i'
    }
    $arguments += @($ContainerId, 'sh', '-c', $Script)
    return Invoke-Docker -Purpose $Purpose -Arguments $arguments
}

function Get-ProjectContainers {
    param([Parameter(Mandatory = $true)][string]$Project)

    $ids = @(Invoke-Docker -Purpose "List $Project Compose containers" -Arguments @(
            'ps', '-aq', '--filter', "label=com.docker.compose.project=$Project"
        )) | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ }
    $containers = [System.Collections.Generic.List[object]]::new()
    foreach ($id in $ids) {
        if ($id -notmatch '^[0-9a-f]{12,64}$') {
            throw "Docker returned an invalid container identifier for $Project."
        }
        $inspectFormat = '{{.Id}}' + [char]9 + '{{.State.Status}}' + [char]9 + '{{.State.Running}}' + [char]9 +
            '{{index .Config.Labels "com.docker.compose.project"}}' + [char]9 + '{{index .Config.Labels "com.docker.compose.service"}}'
        $records = @(Invoke-Docker -Purpose "Inspect $Project Compose container" -Arguments @(
                'inspect', '--format', $inspectFormat, $id
            ))
        if ($records.Count -ne 1) {
            throw "Docker returned an unexpected container inspection result for $Project."
        }
        $fields = ([string]$records[0]) -split "`t", 5
        if ($fields.Count -ne 5 -or $fields[0] -notmatch '^[0-9a-f]{12,64}$') {
            throw "Docker returned incomplete Compose labels for $Project."
        }
        if ($fields[3] -ne $Project) {
            throw "A container returned for $Project has a mismatched Compose project label."
        }
        if ([string]::IsNullOrWhiteSpace($fields[4]) -or $fields[4] -eq '<no value>') {
            throw "A container returned for $Project has no Compose service label."
        }
        $containers.Add([pscustomobject]@{
                Id = $fields[0]
                Status = $fields[1]
                Running = ($fields[2] -eq 'true')
                Project = $fields[3]
                Service = $fields[4]
            })
    }
    return @($containers.ToArray())
}

function Get-RequiredProjectContainer {
    param(
        [Parameter(Mandatory = $true)][object[]]$Containers,
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][string]$Project
    )

    $serviceContainers = @($Containers | Where-Object { $_.Service -eq $Service })
    if ($serviceContainers.Count -ne 1) {
        throw "$Project must have exactly one Compose container for service $Service."
    }
    $container = $serviceContainers[0]
    if ($container.Project -ne $Project -or $container.Id -notmatch '^[0-9a-f]{12,64}$') {
        throw "The $Service container did not pass the Compose project label and identifier checks."
    }
    return $container
}

function Get-SqlText {
    param(
        [Parameter(Mandatory = $true)][string]$Purpose,
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$Query
    )

    $script = 'set -eu; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root -N -B --raw --skip-column-names -e ' + (ConvertTo-ShellSingleQuoted $Query)
    $lines = @(Invoke-ContainerShell -Purpose $Purpose -ContainerId $ContainerId -Script $script)
    return (($lines | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Get-DatabaseTableNames {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $databaseLiteral = $DatabaseName.Replace("'", "''")
    $query = "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA='$databaseLiteral' AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME;"
    $text = Get-SqlText -Purpose $Purpose -ContainerId $ContainerId -Query $query
    if ([string]::IsNullOrWhiteSpace($text)) {
        throw "$Purpose returned no application tables."
    }
    $names = [System.Collections.Generic.List[string]]::new()
    foreach ($line in ($text -split "`r?`n")) {
        $name = ([string]$line).Trim()
        if (-not $name) {
            continue
        }
        if ($name -notmatch '^[A-Za-z0-9_]+$') {
            throw "$Purpose returned an unsupported table identifier."
        }
        $names.Add($name)
    }
    if ($names.Count -eq 0) {
        throw "$Purpose returned no application tables."
    }
    return @($names.ToArray())
}

function Get-PrimaryKeyColumns {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$TableName,
        [Parameter(Mandatory = $true)][string]$Purpose
    )

    $databaseLiteral = $DatabaseName.Replace("'", "''")
    $tableLiteral = $TableName.Replace("'", "''")
    $query = "SELECT COLUMN_NAME FROM information_schema.key_column_usage WHERE TABLE_SCHEMA='$databaseLiteral' AND TABLE_NAME='$tableLiteral' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION;"
    $text = Get-SqlText -Purpose $Purpose -ContainerId $ContainerId -Query $query
    $columns = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($text)) {
        foreach ($line in ($text -split "`r?`n")) {
            $column = ([string]$line).Trim()
            if (-not $column) {
                continue
            }
            if ($column -notmatch '^[A-Za-z0-9_]+$') {
                throw "$Purpose returned an unsupported primary-key identifier."
            }
            $columns.Add($column)
        }
    }
    return @($columns.ToArray())
}

function Get-DatabaseSnapshot {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$DatabaseName,
        [Parameter(Mandatory = $true)][string]$SnapshotName
    )

    $tableNames = Get-DatabaseTableNames -ContainerId $ContainerId -DatabaseName $DatabaseName -Purpose "List tables for $SnapshotName"
    $tables = [System.Collections.Generic.List[object]]::new()
    $tableIndex = 0
    foreach ($tableName in $tableNames) {
        $tableIndex++
        $tableLiteral = $tableName.Replace('`', '``')
        $databaseLiteral = $DatabaseName.Replace('`', '``')
        $countQuery = "SELECT COUNT(*) FROM ``$databaseLiteral``.``$tableLiteral``;"
        $countText = Get-SqlText -Purpose "Count $SnapshotName table" -ContainerId $ContainerId -Query $countQuery
        $count = 0L
        if (-not [Int64]::TryParse($countText, [Globalization.NumberStyles]::Integer, [Globalization.CultureInfo]::InvariantCulture, [ref]$count)) {
            throw "Count for a $SnapshotName table was not an integer."
        }
        $primaryKey = Get-PrimaryKeyColumns -ContainerId $ContainerId -DatabaseName $DatabaseName -TableName $tableName -Purpose "Read $SnapshotName primary key"
        $temporaryPath = "/tmp/bluealbum-m0-$($script:RunId)-$($SnapshotName.ToLowerInvariant())-$tableIndex.sql"
        $script:SnapshotTemporaryPaths.Add([pscustomobject]@{ ContainerId = $ContainerId; Path = $temporaryPath })
        $dumpCommand = 'set -eu; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump --single-transaction --quick --skip-comments --skip-dump-date --skip-extended-insert --no-create-info --skip-add-locks --skip-disable-keys --skip-set-charset --skip-triggers --order-by-primary --hex-blob ' +
            (ConvertTo-ShellSingleQuoted $DatabaseName) + ' ' + (ConvertTo-ShellSingleQuoted $tableName) +
            ' > ' + (ConvertTo-ShellSingleQuoted $temporaryPath) + '; sha256sum ' + (ConvertTo-ShellSingleQuoted $temporaryPath) +
            ' | cut -d " " -f1; rm -f ' + (ConvertTo-ShellSingleQuoted $temporaryPath)
        $hashLines = @(Invoke-ContainerShell -Purpose "Hash $SnapshotName table" -ContainerId $ContainerId -Script $dumpCommand)
        $hash = (($hashLines | ForEach-Object { [string]$_ }) -join "`n").Trim().ToLowerInvariant()
        if ($hash -notmatch '^[0-9a-f]{64}$') {
            throw "Hash for a $SnapshotName table was not a SHA-256 digest."
        }
        $tables.Add([pscustomobject]@{
                Name = $tableName
                Count = $count
                Sha256 = $hash
                PrimaryKey = @($primaryKey)
            })
    }
    return [pscustomobject]@{
        Database = $DatabaseName
        SnapshotName = $SnapshotName
        Tables = @($tables.ToArray())
    }
}

function Get-VolatileTableReason {
    param([Parameter(Mandatory = $true)][string]$TableName)

    if ($TableName -eq 'flyway_schema_history') {
        return 'Flyway metadata can change during application startup.'
    }
    if ($TableName -match 'outbox') {
        return 'Outbox scheduling can update rows while the restored stack is running.'
    }
    return $null
}

function Compare-DatabaseSnapshots {
    param(
        [Parameter(Mandatory = $true)][object]$SourceSnapshot,
        [Parameter(Mandatory = $true)][object]$RestoreSnapshot,
        [Parameter(Mandatory = $true)][string]$Phase,
        [string[]]$ExcludedTables = @(),
        [switch]$AllowVolatileChanges
    )

    $sourceTables = @{}
    foreach ($table in @($SourceSnapshot.Tables)) {
        $sourceTables[$table.Name] = $table
    }
    $restoreTables = @{}
    foreach ($table in @($RestoreSnapshot.Tables)) {
        $restoreTables[$table.Name] = $table
    }
    $excluded = [System.Collections.Generic.List[object]]::new()
    $stableCompared = 0
    $mismatchCount = 0

    foreach ($tableName in @($sourceTables.Keys | Sort-Object)) {
        if (-not $restoreTables.ContainsKey($tableName)) {
            $mismatchCount++
            continue
        }
        $reason = if ($AllowVolatileChanges) { Get-VolatileTableReason -TableName $tableName } else { $null }
        if ($ExcludedTables -contains $tableName) {
            $reason = 'Explicitly excluded from final equality because application startup may deliver pending outbox events.'
        }
        if ($reason) {
            $excluded.Add([pscustomobject]@{ Table = $tableName; Reason = $reason })
            continue
        }
        $sourceTable = $sourceTables[$tableName]
        $restoreTable = $restoreTables[$tableName]
        $stableCompared++
        if ($sourceTable.Count -ne $restoreTable.Count -or $sourceTable.Sha256 -ne $restoreTable.Sha256) {
            $mismatchCount++
        }
    }
    foreach ($tableName in @($restoreTables.Keys | Sort-Object)) {
        if (-not $sourceTables.ContainsKey($tableName)) {
            $mismatchCount++
        }
    }
    if ($mismatchCount -gt 0) {
        throw "Database snapshot comparison failed during $Phase ($mismatchCount table mismatch(es))."
    }
    return [pscustomobject]@{
        Phase = $Phase
        StableTablesCompared = $stableCompared
        VolatileTablesExcluded = @($excluded.ToArray())
        Passed = $true
    }
}

function Get-UploadInventory {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw "$Name upload directory was not copied."
    }
    $rootFull = ConvertTo-AbsolutePath $Root
    $files = @(Get-ChildItem -LiteralPath $rootFull -File -Recurse -Force | Sort-Object FullName)
    $entries = [System.Collections.Generic.List[object]]::new()
    $totalBytes = 0L
    foreach ($file in $files) {
        $relative = [IO.Path]::GetRelativePath($rootFull, (ConvertTo-AbsolutePath $file.FullName)).Replace('\', '/')
        if ([string]::IsNullOrWhiteSpace($relative) -or $relative.StartsWith('../') -or $relative -eq '..') {
            throw "$Name upload inventory contained a path outside its root."
        }
        $digest = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $length = [Int64]$file.Length
        $totalBytes += $length
        $entries.Add([pscustomobject]@{ Path = $relative; Bytes = $length; Sha256 = $digest })
    }
    $canonical = ($entries | ForEach-Object { "$($_.Path)`t$($_.Bytes)`t$($_.Sha256)" }) -join "`n"
    $aggregate = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($canonical))).ToLowerInvariant()
    return [pscustomobject]@{
        Name = $Name
        Files = @($entries.ToArray())
        FileCount = $entries.Count
        TotalBytes = $totalBytes
        AggregateSha256 = $aggregate
    }
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )

    $json = ConvertTo-Json -InputObject $Value -Depth 20
    [IO.File]::WriteAllText($Path, $json, (New-Object Text.UTF8Encoding($false)))
}

function Write-UploadManifest {
    param(
        [Parameter(Mandatory = $true)][object]$Inventory,
        [Parameter(Mandatory = $true)][string]$Path
    )

    Write-JsonFile -Path $Path -Value ([ordered]@{
            FileCount = $Inventory.FileCount
            TotalBytes = $Inventory.TotalBytes
            AggregateSha256 = $Inventory.AggregateSha256
            Files = @($Inventory.Files)
        })
}

function Compare-UploadInventories {
    param(
        [Parameter(Mandatory = $true)][object]$SourceInventory,
        [Parameter(Mandatory = $true)][object]$RestoreInventory
    )

    if ($SourceInventory.FileCount -ne $RestoreInventory.FileCount -or
        $SourceInventory.TotalBytes -ne $RestoreInventory.TotalBytes -or
        $SourceInventory.AggregateSha256 -ne $RestoreInventory.AggregateSha256) {
        throw 'Restored upload inventory does not match the source upload inventory.'
    }
    $sourceFiles = @{}
    foreach ($entry in @($SourceInventory.Files)) { $sourceFiles[$entry.Path] = $entry }
    $restoreFiles = @{}
    foreach ($entry in @($RestoreInventory.Files)) {
        $restoreFiles[$entry.Path] = $entry
        if (-not $sourceFiles.ContainsKey($entry.Path)) {
            throw 'Restored upload inventory contains an unexpected file.'
        }
        $sourceEntry = $sourceFiles[$entry.Path]
        if ($sourceEntry.Bytes -ne $entry.Bytes -or $sourceEntry.Sha256 -ne $entry.Sha256) {
            throw 'Restored upload inventory contains a changed file.'
        }
    }
    foreach ($entry in @($SourceInventory.Files)) {
        if (-not $restoreFiles.ContainsKey($entry.Path)) {
            throw 'Restored upload inventory is missing a source file.'
        }
    }
    return [pscustomobject]@{
        Passed = $true
        FileCount = $SourceInventory.FileCount
        TotalBytes = $SourceInventory.TotalBytes
        AggregateSha256 = $SourceInventory.AggregateSha256
    }
}

function Get-RequiredBusinessTableSummary {
    param(
        [Parameter(Mandatory = $true)][object]$SourceCore,
        [Parameter(Mandatory = $true)][object]$RestoreCore,
        [Parameter(Mandatory = $true)][object]$SourceNotifications,
        [Parameter(Mandatory = $true)][object]$RestoreNotifications
    )

    $sourceCoreTables = @{}
    foreach ($table in @($SourceCore.Tables)) { $sourceCoreTables[$table.Name] = $table }
    $restoreCoreTables = @{}
    foreach ($table in @($RestoreCore.Tables)) { $restoreCoreTables[$table.Name] = $table }
    $sourceNotificationTables = @{}
    foreach ($table in @($SourceNotifications.Tables)) { $sourceNotificationTables[$table.Name] = $table }
    $restoreNotificationTables = @{}
    foreach ($table in @($RestoreNotifications.Tables)) { $restoreNotificationTables[$table.Name] = $table }

    $required = @(
        [pscustomobject]@{ Label = 'users'; Database = 'core'; Table = 'users' },
        [pscustomobject]@{ Label = 'threads'; Database = 'core'; Table = 'threads' },
        [pscustomobject]@{ Label = 'posts (comments/replies)'; Database = 'core'; Table = 'posts' },
        [pscustomobject]@{ Label = 'thread_bookmarks (favorites)'; Database = 'core'; Table = 'thread_bookmarks' },
        [pscustomobject]@{ Label = 'thread_likes'; Database = 'core'; Table = 'thread_likes' },
        [pscustomobject]@{ Label = 'post_likes'; Database = 'core'; Table = 'post_likes' },
        [pscustomobject]@{ Label = 'content_reports'; Database = 'core'; Table = 'content_reports' },
        [pscustomobject]@{ Label = 'notifications'; Database = 'notifications'; Table = 'notifications' }
    )
    $summary = [System.Collections.Generic.List[object]]::new()
    foreach ($spec in $required) {
        $sourceTables = if ($spec.Database -eq 'core') { $sourceCoreTables } else { $sourceNotificationTables }
        $restoreTables = if ($spec.Database -eq 'core') { $restoreCoreTables } else { $restoreNotificationTables }
        if (-not $sourceTables.ContainsKey($spec.Table) -or -not $restoreTables.ContainsKey($spec.Table)) {
            throw "Required business table $($spec.Table) was not present in both snapshots."
        }
        $sourceTable = $sourceTables[$spec.Table]
        $restoreTable = $restoreTables[$spec.Table]
        if ($sourceTable.Count -ne $restoreTable.Count -or $sourceTable.Sha256 -ne $restoreTable.Sha256) {
            throw "Required business table $($spec.Table) did not match after restore."
        }
        $summary.Add([pscustomobject]@{
                Label = $spec.Label
                Database = $spec.Database
                Table = $spec.Table
                SourceCount = $sourceTable.Count
                RestoreCount = $restoreTable.Count
                Sha256 = $restoreTable.Sha256
                Match = $true
            })
    }
    return @($summary.ToArray())
}

function Remove-TemporaryContainerFile {
    param(
        [Parameter(Mandatory = $true)][string]$ContainerId,
        [Parameter(Mandatory = $true)][string]$Path
    )

    if ($Path -notmatch '^/tmp/bluealbum-m0-[0-9a-f]{10}-[A-Za-z0-9_.-]+\.sql$') {
        return
    }
    try {
        $null = Invoke-ContainerShell -Purpose 'Remove temporary backup file' -ContainerId $ContainerId -Script ('rm -f ' + (ConvertTo-ShellSingleQuoted $Path))
    } catch {
        # A temporary file left in /tmp is recoverable and must never mask the verification result.
    }
}

try {
    if ($SourceProject -notmatch '^bluealbum-m0-[a-f0-9]{10}(-restore)?$' -or
        $RestoreProject -notmatch '^bluealbum-m0-[a-f0-9]{10}(-restore)?$') {
        throw 'SourceProject and RestoreProject must match the bluealbum-m0 project-name contract.'
    }
    if ($SourceProject -eq $RestoreProject) {
        throw 'SourceProject and RestoreProject must be different.'
    }
    if (-not (Test-Path -LiteralPath $script:ComposeFile -PathType Leaf)) {
        throw 'The fixed repository docker-compose.yml was not found.'
    }

    $sourceEnvPath = ConvertTo-AbsolutePath $SourceEnvironmentFile
    $restoreEnvPath = ConvertTo-AbsolutePath $RestoreEnvironmentFile
    $script:ComposeOverrideFile = ConvertTo-AbsolutePath $ComposeOverrideFile
    if (-not (Test-Path -LiteralPath $sourceEnvPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $restoreEnvPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $script:ComposeOverrideFile -PathType Leaf)) {
        throw 'Source, restore, and Compose override files must exist.'
    }
    $outputsRoot = Join-Path $script:RepositoryRoot 'outputs'
    $artifactPath = Assert-PathUnderRoot -Path $ArtifactDirectory -Root $outputsRoot -Description 'ArtifactDirectory'
    New-Item -ItemType Directory -Path $artifactPath -Force | Out-Null
    $script:ArtifactRoot = Join-Path $artifactPath "m0-compose-backup-$($script:RunId)"
    New-Item -ItemType Directory -Path $script:ArtifactRoot -Force | Out-Null
    $script:Summary.ArtifactDirectory = $script:ArtifactRoot

    $sourceValues = Read-DotEnv -Path $sourceEnvPath
    $restoreValues = Read-DotEnv -Path $restoreEnvPath
    $sourceDatabase = Get-RequiredDatabaseName -Values $sourceValues -Key 'DB_NAME' -FileDescription 'SourceEnvironmentFile'
    $sourceNotificationDatabase = Get-RequiredDatabaseName -Values $sourceValues -Key 'NOTIFICATION_DB_NAME' -FileDescription 'SourceEnvironmentFile'
    $restoreDatabase = Get-RequiredDatabaseName -Values $restoreValues -Key 'DB_NAME' -FileDescription 'RestoreEnvironmentFile'
    $restoreNotificationDatabase = Get-RequiredDatabaseName -Values $restoreValues -Key 'NOTIFICATION_DB_NAME' -FileDescription 'RestoreEnvironmentFile'
    if ($sourceDatabase -eq $sourceNotificationDatabase -or $restoreDatabase -eq $restoreNotificationDatabase) {
        throw 'DB_NAME and NOTIFICATION_DB_NAME must identify different databases.'
    }
    if ($sourceDatabase -ne $restoreDatabase -or $sourceNotificationDatabase -ne $restoreNotificationDatabase) {
        throw 'Source and restore environment files must use the same validated database names for an unmodified dump import.'
    }
    $script:Summary.SourceDatabase = $sourceDatabase
    $script:Summary.NotificationDatabase = $sourceNotificationDatabase

    Get-Command docker -ErrorAction Stop | Out-Null
    $sourceContainers = @(Get-ProjectContainers -Project $SourceProject)
    $restoreContainers = @(Get-ProjectContainers -Project $RestoreProject)
    if ($restoreContainers.Count -ne 0) {
        throw 'RestoreProject already has Compose containers; refusing to reuse an existing restore stack.'
    }
    $restoreVolumes = @(Invoke-Docker -Purpose "List $RestoreProject Compose volumes" -Arguments @(
            'volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$RestoreProject"
        )) | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ }
    if (@($restoreVolumes).Count -ne 0) {
        throw 'RestoreProject already has Compose volumes; refusing to reuse existing restore data.'
    }
    foreach ($service in @('frontend', 'gateway', 'backend', 'notification-service')) {
        $application = Get-RequiredProjectContainer -Containers $sourceContainers -Service $service -Project $SourceProject
        if ($application.Running) {
            throw "Source application service $service is still running; source writes are not frozen."
        }
    }
    $sourceMysql = Get-RequiredProjectContainer -Containers $sourceContainers -Service 'mysql' -Project $SourceProject
    $null = Get-RequiredProjectContainer -Containers $sourceContainers -Service 'redis' -Project $SourceProject
    $null = Get-RequiredProjectContainer -Containers $sourceContainers -Service 'rabbitmq' -Project $SourceProject
    if (-not $sourceMysql.Running) {
        throw 'Source mysql must be running to take a consistent database dump.'
    }
    $script:SourceMysqlId = $sourceMysql.Id

    $sourceCoreSnapshot = Get-DatabaseSnapshot -ContainerId $sourceMysql.Id -DatabaseName $sourceDatabase -SnapshotName 'source-core'
    $sourceNotificationSnapshot = Get-DatabaseSnapshot -ContainerId $sourceMysql.Id -DatabaseName $sourceNotificationDatabase -SnapshotName 'source-notifications'

    $dumpCommand = 'set -eu; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -u root --single-transaction --routines --triggers --databases "$MYSQL_DATABASE" "$NOTIFICATION_DB_NAME" > ' + (ConvertTo-ShellSingleQuoted $script:SourceDumpContainerPath)
    $null = Invoke-Compose -Purpose 'Create source database dump' -Project $SourceProject -EnvironmentFile $sourceEnvPath -CommandArguments @('exec', '-T', 'mysql', 'sh', '-c', $dumpCommand)
    $dumpHostPath = Join-Path $script:ArtifactRoot 'database-dump.sql'
    $null = Invoke-Docker -Purpose 'Copy source database dump to artifacts' -Arguments @('cp', "$($sourceMysql.Id):$($script:SourceDumpContainerPath)", $dumpHostPath)
    if (-not (Test-Path -LiteralPath $dumpHostPath -PathType Leaf) -or (Get-Item -LiteralPath $dumpHostPath).Length -le 0) {
        throw 'The source database dump was empty or was not copied.'
    }
    $script:Summary.SourceDumpSha256 = (Get-FileHash -LiteralPath $dumpHostPath -Algorithm SHA256).Hash.ToLowerInvariant()

    $sourceUploadsPath = Join-Path $script:ArtifactRoot 'source-uploads'
    New-Item -ItemType Directory -Path $sourceUploadsPath -Force | Out-Null
    $sourceBackend = Get-RequiredProjectContainer -Containers $sourceContainers -Service 'backend' -Project $SourceProject
    $null = Invoke-Docker -Purpose 'Copy stopped source uploads to artifacts' -Arguments @('cp', "$($sourceBackend.Id):/app/uploads/.", $sourceUploadsPath)
    $sourceUploads = Get-UploadInventory -Root $sourceUploadsPath -Name 'Source'
    Write-UploadManifest -Inventory $sourceUploads -Path (Join-Path $script:ArtifactRoot 'source-uploads.sha256.json')
    $script:Summary.SourceUploadFileCount = $sourceUploads.FileCount
    $script:Summary.SourceUploadBytes = $sourceUploads.TotalBytes
    $script:Summary.SourceUploadManifestSha256 = $sourceUploads.AggregateSha256

    $sourceContainersAfterCopy = @(Get-ProjectContainers -Project $SourceProject)
    foreach ($service in @('frontend', 'gateway', 'backend', 'notification-service')) {
        if ((Get-RequiredProjectContainer -Containers $sourceContainersAfterCopy -Service $service -Project $SourceProject).Running) {
            throw "Source application service $service started unexpectedly during backup."
        }
    }

    $null = Invoke-Compose -Purpose 'Start restore dependencies' -Project $RestoreProject -EnvironmentFile $restoreEnvPath -CommandArguments @('up', '-d', '--wait', '--wait-timeout', '300', '--no-build', 'mysql', 'redis', 'rabbitmq')
    $restoreContainers = @(Get-ProjectContainers -Project $RestoreProject)
    foreach ($service in @('mysql', 'redis', 'rabbitmq')) {
        $dependency = Get-RequiredProjectContainer -Containers $restoreContainers -Service $service -Project $RestoreProject
        if (-not $dependency.Running) {
            throw "Restore dependency $service did not remain running."
        }
    }
    foreach ($service in @('frontend', 'gateway', 'backend', 'notification-service')) {
        if (@($restoreContainers | Where-Object { $_.Service -eq $service }).Count -ne 0) {
            throw "Restore dependency startup created application service $service unexpectedly."
        }
    }
    $restoreMysql = Get-RequiredProjectContainer -Containers $restoreContainers -Service 'mysql' -Project $RestoreProject
    $script:RestoreMysqlId = $restoreMysql.Id

    $null = Invoke-Docker -Purpose 'Copy database dump to restore mysql' -Arguments @('cp', $dumpHostPath, "$($restoreMysql.Id):$($script:RestoreDumpContainerPath)")
    $importCommand = 'set -eu; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root < ' + (ConvertTo-ShellSingleQuoted $script:RestoreDumpContainerPath)
    $null = Invoke-ContainerShell -Purpose 'Import database dump into restore mysql' -ContainerId $restoreMysql.Id -Script $importCommand

    $restoreCorePreStart = Get-DatabaseSnapshot -ContainerId $restoreMysql.Id -DatabaseName $restoreDatabase -SnapshotName 'restore-core-pre-start'
    $restoreNotificationPreStart = Get-DatabaseSnapshot -ContainerId $restoreMysql.Id -DatabaseName $restoreNotificationDatabase -SnapshotName 'restore-notifications-pre-start'
    $preCoreComparison = Compare-DatabaseSnapshots -SourceSnapshot $sourceCoreSnapshot -RestoreSnapshot $restoreCorePreStart -Phase 'restore pre-start core database'
    $preNotificationComparison = Compare-DatabaseSnapshots -SourceSnapshot $sourceNotificationSnapshot -RestoreSnapshot $restoreNotificationPreStart -Phase 'restore pre-start notification database'
    $script:Summary.DatabaseComparisons = @($preCoreComparison, $preNotificationComparison)
    $script:Summary.RequiredBusinessTables = @(Get-RequiredBusinessTableSummary -SourceCore $sourceCoreSnapshot -RestoreCore $restoreCorePreStart -SourceNotifications $sourceNotificationSnapshot -RestoreNotifications $restoreNotificationPreStart)

    $null = Invoke-Compose -Purpose 'Create stopped restore backend' -Project $RestoreProject -EnvironmentFile $restoreEnvPath -CommandArguments @('create', '--no-build', '--no-recreate', 'backend')
    $restoreContainers = @(Get-ProjectContainers -Project $RestoreProject)
    $restoreBackend = Get-RequiredProjectContainer -Containers $restoreContainers -Service 'backend' -Project $RestoreProject
    if ($restoreBackend.Running) {
        throw 'Restore backend was running before upload restoration.'
    }
    $null = Invoke-Docker -Purpose 'Copy uploads into stopped restore backend' -Arguments @('cp', "$sourceUploadsPath/.", "$($restoreBackend.Id):/app/uploads/")

    $null = Invoke-Compose -Purpose 'Start complete restore stack' -Project $RestoreProject -EnvironmentFile $restoreEnvPath -CommandArguments @('up', '-d', '--wait', '--wait-timeout', '300', '--no-build')
    $restoreContainers = @(Get-ProjectContainers -Project $RestoreProject)
    foreach ($service in @('mysql', 'redis', 'rabbitmq', 'notification-service', 'backend', 'gateway', 'frontend')) {
        $serviceContainer = Get-RequiredProjectContainer -Containers $restoreContainers -Service $service -Project $RestoreProject
        if (-not $serviceContainer.Running) {
            throw "Restore service $service was not running after full-stack startup."
        }
    }

    $restoreCoreFinal = Get-DatabaseSnapshot -ContainerId (Get-RequiredProjectContainer -Containers $restoreContainers -Service 'mysql' -Project $RestoreProject).Id -DatabaseName $restoreDatabase -SnapshotName 'restore-core-final'
    $restoreNotificationFinal = Get-DatabaseSnapshot -ContainerId (Get-RequiredProjectContainer -Containers $restoreContainers -Service 'mysql' -Project $RestoreProject).Id -DatabaseName $restoreNotificationDatabase -SnapshotName 'restore-notifications-final'
    $finalCoreComparison = Compare-DatabaseSnapshots -SourceSnapshot $sourceCoreSnapshot -RestoreSnapshot $restoreCoreFinal -Phase 'restore final core database' -AllowVolatileChanges
    $finalNotificationComparison = Compare-DatabaseSnapshots -SourceSnapshot $sourceNotificationSnapshot -RestoreSnapshot $restoreNotificationFinal -Phase 'restore final notification database' -ExcludedTables @('notifications') -AllowVolatileChanges
    $script:Summary.DatabaseComparisons = @($preCoreComparison, $preNotificationComparison, $finalCoreComparison, $finalNotificationComparison)
    $sourceNotificationTable = @($sourceNotificationSnapshot.Tables | Where-Object { $_.Name -eq 'notifications' })[0]
    $restoreFinalNotificationTable = @($restoreNotificationFinal.Tables | Where-Object { $_.Name -eq 'notifications' })[0]
    $script:Summary.FinalNotificationRuntimeObservation = [ordered]@{
        SourceCount = $sourceNotificationTable.Count
        RestoreFinalCount = $restoreFinalNotificationTable.Count
        ChangedAfterStartup = ($sourceNotificationTable.Count -ne $restoreFinalNotificationTable.Count -or $sourceNotificationTable.Sha256 -ne $restoreFinalNotificationTable.Sha256)
        EqualityEnforced = 'pre-start restore snapshot'
    }

    $restoreBackendFinal = Get-RequiredProjectContainer -Containers $restoreContainers -Service 'backend' -Project $RestoreProject
    $restoreUploadsPath = Join-Path $script:ArtifactRoot 'restore-uploads'
    New-Item -ItemType Directory -Path $restoreUploadsPath -Force | Out-Null
    $null = Invoke-Docker -Purpose 'Copy restored uploads to artifacts for hashing' -Arguments @('cp', "$($restoreBackendFinal.Id):/app/uploads/.", $restoreUploadsPath)
    $restoreUploads = Get-UploadInventory -Root $restoreUploadsPath -Name 'Restored'
    Write-UploadManifest -Inventory $restoreUploads -Path (Join-Path $script:ArtifactRoot 'restore-uploads.sha256.json')
    $uploadComparison = Compare-UploadInventories -SourceInventory $sourceUploads -RestoreInventory $restoreUploads
    $script:Summary.RestoreUploadFileCount = $restoreUploads.FileCount
    $script:Summary.RestoreUploadBytes = $restoreUploads.TotalBytes
    $script:Summary.RestoreUploadManifestSha256 = $restoreUploads.AggregateSha256
    $script:Summary.UploadComparison = $uploadComparison

    $sourceContainersFinal = @(Get-ProjectContainers -Project $SourceProject)
    foreach ($service in @('frontend', 'gateway', 'backend', 'notification-service')) {
        if ((Get-RequiredProjectContainer -Containers $sourceContainersFinal -Service $service -Project $SourceProject).Running) {
            throw "Source application service $service was running at verification completion."
        }
    }
    $script:Summary.Status = 'PASSED'
}
catch {
    $script:Summary.Status = 'FAILED'
    $script:Summary.FailureType = $_.Exception.GetType().Name
    $script:Summary.Failure = $_.Exception.Message
    $failureException = $_.Exception
}
finally {
    if ($script:SourceMysqlId) {
        Remove-TemporaryContainerFile -ContainerId $script:SourceMysqlId -Path $script:SourceDumpContainerPath
    }
    if ($script:RestoreMysqlId) {
        Remove-TemporaryContainerFile -ContainerId $script:RestoreMysqlId -Path $script:RestoreDumpContainerPath
    }
    foreach ($temporary in @($script:SnapshotTemporaryPaths)) {
        Remove-TemporaryContainerFile -ContainerId $temporary.ContainerId -Path $temporary.Path
    }
    if ($script:ArtifactRoot -and (Test-Path -LiteralPath $script:ArtifactRoot -PathType Container)) {
        Write-JsonFile -Path (Join-Path $script:ArtifactRoot 'verification-summary.json') -Value $script:Summary
    }
}

if ($failureException) {
    throw $failureException
}

Write-Output (ConvertTo-Json -InputObject $script:Summary -Depth 20 -Compress)

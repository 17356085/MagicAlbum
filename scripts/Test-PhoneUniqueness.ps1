#Requires -Version 7.4
[CmdletBinding()]
param(
    [ValidateSet('All', 'NewDatabase', 'NoDuplicateLegacy', 'DuplicateLegacy', 'ConcurrentWrites')]
    [string]$Scenario = 'All',

    [ValidatePattern('^[a-f0-9]{10}$')]
    [string]$RunId = ([Guid]::NewGuid().ToString('N').Substring(0, 10)),

    [string]$MySqlImage = 'mysql:8.0'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$v2MigrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V2__create_users.sql'
$migrationPath = Join-Path $repositoryRoot 'backend/src/main/resources/db/migration/mysql/V20261017__add_users_phone_unique.sql'
$artifactDirectory = Join-Path $repositoryRoot "outputs/phone-uniqueness/$RunId"
$mysqlContainer = "bluealbum-phone-unique-$RunId"
$mysqlRootPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24)).ToLowerInvariant()
$checks = [System.Collections.Generic.List[object]]::new()
$containerCreated = $false
$jobs = [System.Collections.Generic.List[object]]::new()
$verificationSucceeded = $false
$cleanupSucceeded = $true
$cleanupErrors = [System.Collections.Generic.List[string]]::new()
$mainError = $null

if (-not (Test-Path -LiteralPath $v2MigrationPath -PathType Leaf)) {
    throw 'Users V2 migration is missing.'
}
if (-not (Test-Path -LiteralPath $migrationPath -PathType Leaf)) {
    throw 'Phone uniqueness migration is missing.'
}
if ($MySqlImage -notmatch '^[A-Za-z0-9][A-Za-z0-9._/:@-]{0,127}$') {
    throw 'MySqlImage contains unsupported characters.'
}
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null

function Assert-Check {
    param(
        [Parameter(Mandatory = $true)][bool]$Passed,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $checks.Add([pscustomobject]@{ Name = $Name; Passed = $Passed })
    if (-not $Passed) {
        throw "Phone uniqueness verification failed: $Name"
    }
    Write-Host "PASS $Name"
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [Parameter(Mandatory = $true)][string]$Sql,
        [switch]$AllowFailure
    )

    if ($Database -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') {
        throw 'Temporary database name failed validation.'
    }
    $mysqlCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -uroot -N -B --database=' + $Database
    $output = @($Sql | & docker exec -i $mysqlContainer sh -c $mysqlCommand 2>$null)
    $exitCode = [int]$LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "Isolated MySQL query failed for database $Database."
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Output = (($output | ForEach-Object { [string]$_ }) -join "`n").Trim()
    }
}

function New-UsersDatabase {
    param([Parameter(Mandatory = $true)][string]$Database)

    $createDatabaseSql = "CREATE DATABASE $Database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    Invoke-MySql -Database 'mysql' -Sql $createDatabaseSql | Out-Null
    $v2Sql = Get-Content -LiteralPath $v2MigrationPath -Raw
    Invoke-MySql -Database $Database -Sql $v2Sql | Out-Null
}

function Get-DuplicatePhoneGroupCount {
    param([Parameter(Mandatory = $true)][string]$Database)

    $sql = @'
SELECT COUNT(*)
FROM (
    SELECT phone
    FROM users
    GROUP BY phone
    HAVING COUNT(*) > 1
) duplicate_phone_groups;
'@
    $result = Invoke-MySql -Database $Database -Sql $sql
    if ($result.Output -notmatch '^\d+$') {
        throw 'The read-only duplicate preflight did not return a count.'
    }
    return [int64]$result.Output
}

function Apply-PhoneMigration {
    param(
        [Parameter(Mandatory = $true)][string]$Database,
        [switch]$AllowFailure
    )

    $migrationSql = Get-Content -LiteralPath $migrationPath -Raw
    return Invoke-MySql -Database $Database -Sql $migrationSql -AllowFailure:$AllowFailure
}

function Get-PhoneUniqueIndexCount {
    param([Parameter(Mandatory = $true)][string]$Database)

    $sql = @'
SELECT COUNT(*)
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'users'
  AND index_name = 'users_phone_uq'
  AND non_unique = 0;
'@
    $result = Invoke-MySql -Database $Database -Sql $sql
    if ($result.Output -notmatch '^\d+$') {
        throw 'The unique-index read-back did not return a count.'
    }
    return [int64]$result.Output
}

function Run-NewDatabaseScenario {
    $database = "phone_new_$RunId"
    New-UsersDatabase -Database $database
    $migration = Apply-PhoneMigration -Database $database
    Assert-Check ($migration.ExitCode -eq 0) 'New database migration succeeds'
    Assert-Check ((Get-PhoneUniqueIndexCount -Database $database) -eq 1) 'New database has users_phone_uq'
}

function Run-NoDuplicateLegacyScenario {
    $database = "phone_legacy_ok_$RunId"
    New-UsersDatabase -Database $database
    $seedSql = @'
INSERT INTO users (username, email, phone, password_hash)
VALUES
    ('legacy-one', 'legacy-one@example.test', '13800000001', 'hash-one'),
    ('legacy-two', 'legacy-two@example.test', '13800000002', 'hash-two');
'@
    Invoke-MySql -Database $database -Sql $seedSql | Out-Null
    $duplicateGroups = Get-DuplicatePhoneGroupCount -Database $database
    Assert-Check ($duplicateGroups -eq 0) 'No-duplicate legacy preflight returns zero groups'
    $migration = Apply-PhoneMigration -Database $database
    Assert-Check ($migration.ExitCode -eq 0) 'No-duplicate legacy migration succeeds'
    Assert-Check ((Get-PhoneUniqueIndexCount -Database $database) -eq 1) 'No-duplicate legacy has users_phone_uq'
}

function Run-DuplicateLegacyScenario {
    $database = "phone_legacy_dup_$RunId"
    New-UsersDatabase -Database $database
    $seedSql = @'
INSERT INTO users (username, email, phone, password_hash)
VALUES
    ('duplicate-one', 'duplicate-one@example.test', '13800000000', 'hash-one'),
    ('duplicate-two', 'duplicate-two@example.test', '13800000000', 'hash-two');
'@
    Invoke-MySql -Database $database -Sql $seedSql | Out-Null
    $duplicateGroups = Get-DuplicatePhoneGroupCount -Database $database
    Assert-Check ($duplicateGroups -eq 1) 'Duplicate legacy preflight reports one group without printing a phone'
    $migration = Apply-PhoneMigration -Database $database -AllowFailure
    Assert-Check ($migration.ExitCode -ne 0) 'Duplicate legacy migration is blocked by the database'
    Assert-Check ((Get-PhoneUniqueIndexCount -Database $database) -eq 0) 'Blocked migration leaves no phone unique index'
    $rowCount = (Invoke-MySql -Database $database -Sql 'SELECT COUNT(*) FROM users;').Output
    Assert-Check ($rowCount -eq '2') 'Blocked migration leaves legacy rows unchanged'
}

function Run-ConcurrentWriteScenario {
    $database = "phone_race_$RunId"
    New-UsersDatabase -Database $database
    $migration = Apply-PhoneMigration -Database $database
    Assert-Check ($migration.ExitCode -eq 0) 'Concurrent-write database migration succeeds'

    $insertOne = @'
INSERT INTO users (username, email, phone, password_hash)
VALUES ('race-one', 'race-one@example.test', '13800000009', 'hash-one');
'@
    $insertTwo = @'
INSERT INTO users (username, email, phone, password_hash)
VALUES ('race-two', 'race-two@example.test', '13800000009', 'hash-two');
'@
    $jobScript = {
        param([string]$Container, [string]$Database, [string]$Statement)
        $mysqlCommand = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -uroot -N -B --database=' + $Database
        $result = @($Statement | & docker exec -i $Container sh -c $mysqlCommand 2>&1)
        $exitCode = if ($null -eq $LASTEXITCODE) { -1 } else { [int]$LASTEXITCODE }
        # Reduce diagnostics to a code; MySQL duplicate messages contain the phone.
        [pscustomobject]@{
            ExitCode = $exitCode
            DuplicateConflict = (($result -join "`n") -match 'ERROR 1062\b')
        }
    }
    $jobs.Add((Start-Job -ScriptBlock $jobScript -ArgumentList $mysqlContainer, $database, $insertOne))
    $jobs.Add((Start-Job -ScriptBlock $jobScript -ArgumentList $mysqlContainer, $database, $insertTwo))
    $jobRecords = @($jobs.ToArray())
    Wait-Job -Job $jobRecords -Timeout 30 | Out-Null
    $allJobsCompleted = $jobRecords.Count -eq 2 -and @($jobRecords | Where-Object { $_.State -ne 'Completed' }).Count -eq 0
    Assert-Check $allJobsCompleted 'Concurrent write jobs complete before result collection'
    $exitCodes = @()
    foreach ($job in $jobRecords) {
        $received = @(Receive-Job -Job $job -ErrorAction SilentlyContinue)
        $parsedExitCode = 0
        if ($received.Count -eq 1 -and [int]::TryParse([string]$received[0].ExitCode, [ref]$parsedExitCode)) {
            if ($parsedExitCode -ne 0 -and -not $received[0].DuplicateConflict) {
                throw 'Concurrent write failed without the expected duplicate-key error.'
            }
            $exitCodes += $parsedExitCode
        } else {
            $exitCodes += -1
        }
    }
    $validExitCodes = $exitCodes.Count -eq 2 -and @($exitCodes | Where-Object { $_ -lt 0 -or $_ -gt 255 }).Count -eq 0
    Assert-Check $validExitCodes 'Concurrent write jobs return valid process exit codes'
    $successfulWrites = @($exitCodes | Where-Object { $_ -eq 0 }).Count
    $failedWrites = @($exitCodes | Where-Object { $_ -ne 0 }).Count
    Assert-Check (($successfulWrites -eq 1) -and ($failedWrites -eq 1)) 'Concurrent same-phone writes have one success and one database conflict'
    $rowCount = (Invoke-MySql -Database $database -Sql "SELECT COUNT(*) FROM users WHERE phone = '13800000009';").Output
    Assert-Check ($rowCount -eq '1') 'Concurrent same-phone writes persist exactly one row'
}

try {
    $null = & docker image inspect $MySqlImage 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Pulling isolated verification image $MySqlImage."
    }
    $null = & docker run -d --name $mysqlContainer --publish '127.0.0.1::3306' `
        --memory '512m' `
        --cpus '1' `
        --env "MYSQL_ROOT_PASSWORD=$mysqlRootPassword" `
        --env 'MYSQL_ROOT_HOST=%' `
        $MySqlImage --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci `
        --innodb-buffer-pool-size=64M --performance-schema=OFF 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create the isolated MySQL verification container.'
    }
    $containerCreated = $true

    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $probe = Invoke-MySql -Database 'mysql' -Sql 'SELECT 1;' -AllowFailure
            if ($probe.ExitCode -eq 0 -and $probe.Output -eq '1') {
                $ready = $true
                break
            }
        } catch {
            # The temporary container can reject connections while MySQL initializes.
        }
        Start-Sleep -Seconds 1
    }
    Assert-Check $ready 'Isolated MySQL is ready'

    if ($Scenario -in @('All', 'NewDatabase')) {
        Run-NewDatabaseScenario
    }
    if ($Scenario -in @('All', 'NoDuplicateLegacy')) {
        Run-NoDuplicateLegacyScenario
    }
    if ($Scenario -in @('All', 'DuplicateLegacy')) {
        Run-DuplicateLegacyScenario
    }
    if ($Scenario -in @('All', 'ConcurrentWrites')) {
        Run-ConcurrentWriteScenario
    }
    $verificationSucceeded = $true
} catch {
    $mainError = $_
} finally {
    foreach ($job in @($jobs.ToArray())) {
        try {
            Stop-Job -Job $job -ErrorAction SilentlyContinue | Out-Null
            Remove-Job -Job $job -Force -ErrorAction Stop
        } catch {
            $cleanupSucceeded = $false
            $cleanupErrors.Add('Job cleanup failed.')
        }
    }
    if ($containerCreated) {
        $null = & docker rm -f -v $mysqlContainer 2>$null
        if ($LASTEXITCODE -ne 0) {
            $cleanupSucceeded = $false
            $cleanupErrors.Add('Owned MySQL container removal failed.')
        }
        $remainingContainers = @(& docker ps -aq --filter "name=^/$mysqlContainer`$" 2>$null)
        if ($LASTEXITCODE -ne 0 -or $remainingContainers.Count -ne 0) {
            $cleanupSucceeded = $false
            $cleanupErrors.Add('Owned MySQL container still exists after cleanup.')
        }
    }
    [IO.File]::WriteAllText(
        (Join-Path $artifactDirectory 'checks.json'),
        (ConvertTo-Json -Depth 5 -InputObject ([ordered]@{
            RunId = $RunId
            Scenario = $Scenario
            Succeeded = ($verificationSucceeded -and $cleanupSucceeded)
            Checks = $checks.ToArray()
            CleanupSucceeded = $cleanupSucceeded
            CleanupErrors = $cleanupErrors.ToArray()
            Notes = @(
                'Only duplicate group counts are read back; phone values are never printed.'
                'The container is disposable, uses only image-managed anonymous storage, and is removed with -f -v by exact name in finally.'
                'ConcurrentWrites validates the database race. Run the targeted Java tests for the HTTP 409 mapping.'
            )
        }))
    )
}

if ($null -ne $mainError) {
    throw $mainError.Exception
}
if (-not $cleanupSucceeded) {
    throw ($cleanupErrors -join ' ')
}

Write-Host "Completed $($checks.Count) isolated phone uniqueness checks."

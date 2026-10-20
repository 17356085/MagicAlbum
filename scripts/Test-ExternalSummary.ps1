[CmdletBinding()]
param(
    [string]$PrivateConfig = 'backend/.env.local',
    [string]$Maven = 'mvn',
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskOutputsRoot = [IO.Path]::GetFullPath((Join-Path $taskRoot 'outputs'))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $taskOutputsRoot ('m4b-external-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
}
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (-not $taskOutput.StartsWith($taskOutputsRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be a new folder under this workspace outputs directory.'
}
if (Test-Path -LiteralPath $taskOutput) { throw 'Refusing to overwrite an existing acceptance directory.' }
if (-not [IO.Path]::IsPathRooted($PrivateConfig)) { $PrivateConfig = Join-Path $taskRoot $PrivateConfig }
if (-not (Test-Path -LiteralPath $PrivateConfig -PathType Leaf)) { throw 'Private model configuration was not found.' }
$taskValues = @{}
foreach ($taskLine in [IO.File]::ReadLines($PrivateConfig)) {
    if ($taskLine -match '^\s*(AI_API_KEY|AI_BASE_URL|AI_MODEL)\s*=\s*(.*)$') {
        $taskValue = $Matches[2].Trim()
        if ($taskValue.Length -ge 2 -and (($taskValue.StartsWith('"') -and $taskValue.EndsWith('"')) -or ($taskValue.StartsWith("'") -and $taskValue.EndsWith("'")))) {
            $taskValue = $taskValue.Substring(1, $taskValue.Length - 2)
        }
        $taskValues[$Matches[1]] = $taskValue
    }
}
foreach ($taskName in @('AI_API_KEY', 'AI_BASE_URL', 'AI_MODEL')) {
    if ([string]::IsNullOrWhiteSpace($taskValues[$taskName])) { throw "Missing required private setting: $taskName" }
}
$null = Get-Command $Maven -ErrorAction Stop
$null = New-Item -ItemType Directory -Path $taskOutput
$taskEnvironment = @{
    REAL_AI_API_KEY = $taskValues['AI_API_KEY']
    REAL_AI_BASE_URL = $taskValues['AI_BASE_URL']
    REAL_AI_MODEL = $taskValues['AI_MODEL']
    REAL_AI_OUTPUT_DIR = $taskOutput
    MAVEN_OPTS = '-Xmx256m -XX:ActiveProcessorCount=2'
    JAVA_TOOL_OPTIONS = '-Xmx512m -XX:ActiveProcessorCount=2'
}
$taskPreviousEnvironment = @{}
$taskChecks = [ordered]@{
    ProviderMode = 'real'
    ControlledFixture = $false
    ExternalCallSucceeded = $false
    SeparateJvmReadbackSucceeded = $false
    DatabaseKind = 'isolated-file-H2'
    FullMysqlRabbitBrowserProven = $false
    CredentialFileCreated = $false
    Failure = $null
}

function Invoke-SummaryAcceptance([string]$TestClass, [string]$LogName) {
    $taskTestStart = [DateTime]::UtcNow
    $taskLogWriter = [IO.StreamWriter]::new((Join-Path $taskOutput $LogName), $false, [Text.UTF8Encoding]::new($false))
    try {
        & $Maven -pl backend "-Dtest=$TestClass" test 2>&1 | ForEach-Object {
            # Sanitize before writing; credentials are never passed as command arguments.
            $taskSafeLine = ([string]$_).Replace($taskValues['AI_API_KEY'], '[REDACTED]')
            $taskLogWriter.WriteLine($taskSafeLine)
        }
        $taskExit = $LASTEXITCODE
        if ($taskExit -ne 0) { throw "Acceptance failed: $TestClass (exit $taskExit). Inspect the sanitized log." }
        $taskJUnitPath = Join-Path $taskRoot "backend/target/surefire-reports/TEST-com.example.demo.ai.summary.$TestClass.xml"
        $taskJUnitFile = Get-Item -LiteralPath $taskJUnitPath
        [xml]$taskJUnit = Get-Content -LiteralPath $taskJUnitPath -Raw
        if ($taskJUnitFile.LastWriteTimeUtc -lt $taskTestStart.AddSeconds(-2) -or [int]$taskJUnit.testsuite.tests -ne 1 -or
            [int]$taskJUnit.testsuite.failures -ne 0 -or [int]$taskJUnit.testsuite.errors -ne 0 -or [int]$taskJUnit.testsuite.skipped -ne 0) {
            throw "Current executed JUnit evidence is incomplete: $TestClass"
        }
    } finally { $taskLogWriter.Dispose() }
}

Push-Location $taskRoot
try {
    foreach ($taskName in $taskEnvironment.Keys) {
        $taskPreviousEnvironment[$taskName] = [Environment]::GetEnvironmentVariable($taskName, 'Process')
        [Environment]::SetEnvironmentVariable($taskName, $taskEnvironment[$taskName], 'Process')
    }
    Invoke-SummaryAcceptance 'SummaryRealModelAcceptance' 'external-call.log'
    $taskExternalEvidence = Get-Content -LiteralPath (Join-Path $taskOutput 'external-model.json') -Raw | ConvertFrom-Json
    if ($taskExternalEvidence.providerMode -ne 'real' -or $taskExternalEvidence.controlledFixture -or
        @($taskExternalEvidence.cases).Count -ne 2 -or @($taskExternalEvidence.cases | Where-Object { $_.status -ne 'COMPLETED' }).Count -ne 0) {
        throw 'External normal/long model evidence is incomplete.'
    }
    $taskChecks.ExternalCallSucceeded = $true
    foreach ($taskName in @('REAL_AI_API_KEY', 'REAL_AI_BASE_URL', 'REAL_AI_MODEL')) {
        [Environment]::SetEnvironmentVariable($taskName, $null, 'Process')
    }
    Invoke-SummaryAcceptance 'SummaryRealModelReadbackAcceptance' 'fresh-process-readback.log'
    $taskReadback = Get-Content -LiteralPath (Join-Path $taskOutput 'fresh-process-readback.json') -Raw | ConvertFrom-Json
    if (-not $taskReadback.passed -or -not $taskReadback.separateJvm -or @($taskReadback.cases).Count -ne 2) {
        throw 'External model readback evidence is incomplete.'
    }
    $taskChecks.SeparateJvmReadbackSucceeded = $true
    Write-Output "External normal/long summaries and separate-process database readback passed. Evidence: $taskOutput"
} catch {
    $taskChecks.Failure = ([string]$_.Exception.Message).Replace($taskValues['AI_API_KEY'], '[REDACTED]')
    throw
} finally {
    foreach ($taskName in $taskPreviousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($taskName, $taskPreviousEnvironment[$taskName], 'Process')
    }
    $taskChecks | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskOutput 'checks.json') -Encoding utf8
    Pop-Location
}

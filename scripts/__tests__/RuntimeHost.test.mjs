import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const powershell = process.env.PWSH_BIN || 'pwsh';
const modulePath = join(repoRoot, 'scripts', 'lib', 'RuntimeHost.psm1');

function makeTempDirectory(prefix = 'bluealbum-runtime-host-') {
  return mkdtempSync(join(tmpdir(), prefix));
}

function removeTempDirectory(directory) {
  const target = resolve(directory);
  assert.equal(dirname(target), resolve(tmpdir()));
  assert.ok(target.split(/[\\/]/).pop().startsWith('bluealbum-runtime-host-'));
  rmSync(target, { recursive: true, force: true });
}

function quotePowerShell(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function runPowerShell(source) {
  const directory = makeTempDirectory();
  const scriptPath = join(directory, 'harness.ps1');
  writeFileSync(scriptPath, source, 'utf8');
  try {
    return spawnSync(powershell, ['-NoProfile', '-NonInteractive', '-File', scriptPath], {
      cwd: repoRoot,
      env: { ...process.env },
      encoding: 'utf8',
      windowsHide: true,
    });
  } finally {
    removeTempDirectory(directory);
  }
}

test('RuntimeHost probes an occupied port, releases its own probe, and accepts the released port', () => {
  const result = runPowerShell(`
$ErrorActionPreference = 'Stop'
Import-Module ${quotePowerShell(modulePath)} -Force
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
if (Test-RuntimeTcpPortAvailable -Port $port) { throw 'occupied port was reported available' }
try {
  Assert-RuntimePortsAvailable -Ports @($port)
  throw 'occupied port was not rejected'
} catch {
  if ($_.Exception.Message -notmatch 'occupied') { throw }
}
$listener.Stop()
if (-not (Test-RuntimeTcpPortAvailable -Port $port)) { throw 'released port was not available' }
Assert-RuntimePortsAvailable -Ports @($port)
`);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
});

test('RuntimeHost reports Linux MemAvailable plus SwapFree and enforces exact thresholds', () => {
  const directory = makeTempDirectory();
  const memInfoPath = join(directory, 'meminfo');
  writeFileSync(memInfoPath, 'MemAvailable:       1024 kB\nSwapFree:           2048 kB\nMemTotal:           4096 kB\n', 'utf8');
  try {
    const result = runPowerShell(`
$ErrorActionPreference = 'Stop'
Import-Module ${quotePowerShell(modulePath)} -Force
$snapshot = Get-RuntimeLinuxHostMemorySnapshot -Path ${quotePowerShell(memInfoPath)}
if ($snapshot.MemoryMetric -ne 'MemAvailablePlusSwapFreeKiB') { throw 'Linux metric name changed' }
if ($snapshot.MemoryMetricKiB -ne 3072) { throw "Unexpected Linux metric: $($snapshot.MemoryMetricKiB)" }
if (-not (Test-RuntimeHostMemoryThreshold -Snapshot $snapshot -MinimumKiB 3072)) { throw 'equal threshold was rejected' }
if (Test-RuntimeHostMemoryThreshold -Snapshot $snapshot -MinimumKiB 3073) { throw 'over-limit threshold was accepted' }
`);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  } finally {
    removeTempDirectory(directory);
  }
});

test('RuntimeHost preserves Windows CIM commit-memory semantics and rejects boundary overage', () => {
  const result = runPowerShell(`
$ErrorActionPreference = 'Stop'
Import-Module ${quotePowerShell(modulePath)} -Force
# Load the production function through PowerShell's AST so this test keeps the
# implementation in one place while forcing its Windows branch on every CI OS.
$source = [IO.File]::ReadAllText(${quotePowerShell(modulePath)})
$tokens = $null
$syntaxErrors = $null
$moduleAst = [System.Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$syntaxErrors)
if ($syntaxErrors.Count) { throw 'RuntimeHost module AST is invalid' }
$functionAst = $moduleAst.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
        $node.Name -eq 'Get-RuntimeHostMemorySnapshot'
}, $true)
if ($null -eq $functionAst) { throw 'Production host-memory function was not found in AST' }
$functionText = $functionAst.Extent.Text
if (([regex]::Matches($functionText, '\\$IsWindows')).Count -ne 1 -or
    ([regex]::Matches($functionText, '\\$IsLinux')).Count -ne 1) {
  throw 'Production host-memory function platform branches changed unexpectedly'
}
$functionText = $functionText.Replace('if ($IsWindows)', 'if ($true)').Replace('if ($IsLinux)', 'if ($false)')
Invoke-Expression $functionText
function global:Get-CimInstance { return [pscustomobject]@{ FreeVirtualMemory = 4096; FreePhysicalMemory = 2048; TotalVirtualMemorySize = 8192 } }
$snapshot = Get-RuntimeHostMemorySnapshot
if ($snapshot.Platform -ne 'Windows' -or $snapshot.MemoryMetric -ne 'FreeCommitKiB') { throw 'Windows metric changed' }
if ($snapshot.MemoryMetricKiB -ne 4096 -or $snapshot.FreeCommitKiB -ne 4096) { throw 'CIM commit memory was not preserved' }
if (-not (Test-RuntimeHostMemoryThreshold -Snapshot $snapshot -MinimumKiB 4096)) { throw 'equal Windows threshold was rejected' }
if (Test-RuntimeHostMemoryThreshold -Snapshot $snapshot -MinimumKiB 4097) { throw 'over-limit Windows threshold was accepted' }
`);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
});

test('RuntimeHost returns a fixed valid PNG fixture and platform-specific Python defaults', () => {
  const result = runPowerShell(`
$ErrorActionPreference = 'Stop'
Import-Module ${quotePowerShell(modulePath)} -Force
$bytes = Get-RuntimePngFixtureBytes
if ($bytes.Length -ne 68) { throw "Unexpected PNG length: $($bytes.Length)" }
$signature = [byte[]](137, 80, 78, 71, 13, 10, 26, 10)
for ($index = 0; $index -lt $signature.Length; $index++) { if ($bytes[$index] -ne $signature[$index]) { throw 'PNG signature changed' } }
$python = Get-RuntimeDefaultPythonExecutable -RepositoryRoot (Get-Location).Path
$expectedSuffix = if ($IsWindows) { 'tests\\.venv\\Scripts\\python.exe' } else { 'tests/.venv/bin/python' }
if (-not $python.EndsWith($expectedSuffix, [StringComparison]::OrdinalIgnoreCase)) { throw "Unexpected Python default: $python" }
`);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
});

test('RuntimeHost rejects incomplete Linux memory information', () => {
  const directory = makeTempDirectory();
  const memInfoPath = join(directory, 'meminfo');
  writeFileSync(memInfoPath, 'MemAvailable: 1024 kB\n', 'utf8');
  try {
    const result = runPowerShell(`
$ErrorActionPreference = 'Stop'
Import-Module ${quotePowerShell(modulePath)} -Force
try { Get-RuntimeLinuxHostMemorySnapshot -Path ${quotePowerShell(memInfoPath)} | Out-Null; throw 'missing SwapFree was accepted' } catch { if ($_.Exception.Message -notmatch 'SwapFree') { throw } }
`);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  } finally {
    removeTempDirectory(directory);
  }
});

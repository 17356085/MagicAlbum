import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const backupScript = join(repoRoot, 'scripts', 'Test-ComposeBackup.ps1');
const powershell = process.env.PWSH_BIN || 'pwsh';

const baselineCoreTables = [
  'users',
  'threads',
  'posts',
  'thread_bookmarks',
  'thread_likes',
  'post_likes',
  'content_reports',
];
const baselineNotificationTables = ['notifications'];
const stageCoreTables = [
  'ai_summary_task',
  'ai_summary_attempt',
  'ai_summary_budget',
  'ai_summary_request',
  'community_state',
  'community_admin_role',
  'community_moderator_scope',
  'community_posting_restriction',
  'community_audit',
];
const stageNotificationTables = ['notification_stream_cursor', 'notification_stream_event'];

function quotePowerShell(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function makeTempDirectory(prefix = 'bluealbum-compose-backup-') {
  return mkdtempSync(join(tmpdir(), prefix));
}

function removeTempDirectory(directory) {
  const target = resolve(directory);
  assert.equal(dirname(target), resolve(tmpdir()));
  assert.ok(target.split(/[\\/]/).pop().startsWith('bluealbum-compose-backup-'));
  rmSync(target, { recursive: true, force: true });
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

function table(name, overrides = {}) {
  return {
    Name: name,
    Count: overrides.Count ?? 1,
    Sha256: overrides.Sha256 ?? 'a'.repeat(64),
  };
}

function snapshot(database, names, overrides = {}) {
  return {
    Database: database,
    SnapshotName: `${database}-fixture`,
    Tables: names.map((name) => table(name, overrides[name] ?? {})),
  };
}

function makeFixture({ requireStageFixtures = false, includeStageTables = requireStageFixtures, sourceOverrides = {}, restoreOverrides = {}, omitSource = [], omitRestore = [] } = {}) {
  const coreNames = [...baselineCoreTables, ...(includeStageTables ? stageCoreTables : [])];
  const notificationNames = [...baselineNotificationTables, ...(includeStageTables ? stageNotificationTables : [])];
  const sourceCoreNames = coreNames.filter((name) => !omitSource.includes(name));
  const restoreCoreNames = coreNames.filter((name) => !omitRestore.includes(name));
  const sourceNotificationNames = notificationNames.filter((name) => !omitSource.includes(name));
  const restoreNotificationNames = notificationNames.filter((name) => !omitRestore.includes(name));
  return {
    RequireStageFixtures: requireStageFixtures,
    SourceCore: snapshot('core', sourceCoreNames, sourceOverrides),
    RestoreCore: snapshot('core', restoreCoreNames, restoreOverrides),
    SourceNotifications: snapshot('notifications', sourceNotificationNames, sourceOverrides),
    RestoreNotifications: snapshot('notifications', restoreNotificationNames, restoreOverrides),
  };
}

function runSummary(fixture) {
  const directory = makeTempDirectory();
  const fixturePath = join(directory, 'fixture.json');
  const scriptPath = join(directory, 'harness.ps1');
  writeFileSync(fixturePath, JSON.stringify(fixture), 'utf8');
  writeFileSync(scriptPath, `
$ErrorActionPreference = 'Stop'
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(${quotePowerShell(backupScript)}, [ref]$tokens, [ref]$parseErrors)
if (@($parseErrors).Count -ne 0) { throw (@($parseErrors) | ForEach-Object Message) }
$functionAst = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Get-RequiredBusinessTableSummary'
}, $true)
if (-not $functionAst) { throw 'Get-RequiredBusinessTableSummary was not found in the production script.' }
. ([scriptblock]::Create($functionAst.Extent.Text))
$fixture = Get-Content -LiteralPath ${quotePowerShell(fixturePath)} -Raw | ConvertFrom-Json
try {
    $rows = @(Get-RequiredBusinessTableSummary -SourceCore $fixture.SourceCore -RestoreCore $fixture.RestoreCore -SourceNotifications $fixture.SourceNotifications -RestoreNotifications $fixture.RestoreNotifications -RequireStageFixtures:$fixture.RequireStageFixtures)
    [pscustomobject]@{ ok = $true; rows = $rows } | ConvertTo-Json -Depth 10 -Compress
    exit 0
} catch {
    [pscustomobject]@{ ok = $false; error = $_.Exception.Message } | ConvertTo-Json -Depth 10 -Compress
    exit 1
}
`, 'utf8');
  try {
    const result = spawnSync(powershell, ['-NoProfile', '-NonInteractive', '-File', scriptPath], {
      cwd: repoRoot,
      env: { ...process.env },
      encoding: 'utf8',
      windowsHide: true,
    });
    const output = result.stdout.trim();
    let payload;
    try {
      payload = JSON.parse(output);
    } catch (error) {
      throw new Error(`PowerShell harness returned invalid JSON (status ${result.status}): ${output}\n${result.stderr}`, { cause: error });
    }
    return { ...result, payload };
  } finally {
    removeTempDirectory(directory);
  }
}

test('Test-ComposeBackup.ps1 parses with PowerShell', () => {
  const result = runPowerShell(`
$tokens = $null
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(${quotePowerShell(backupScript)}, [ref]$tokens, [ref]$parseErrors) | Out-Null
if (@($parseErrors).Count -ne 0) { throw (@($parseErrors) | ForEach-Object Message) }
`);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
});

test('stage fixture mode requires all exact M3/M4 tables to be present, nonempty, and equal', () => {
  const result = runSummary(makeFixture({ requireStageFixtures: true }));
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.equal(result.payload.ok, true);
  assert.equal(result.payload.rows.length, 19);
  const stageRows = result.payload.rows.filter((row) => row.RequiredNonEmpty);
  assert.deepEqual(stageRows.map((row) => row.Table), [...stageCoreTables, ...stageNotificationTables]);
  assert.ok(stageRows.every((row) => row.Match && row.SourceCount > 0 && row.RestoreCount > 0));
  assert.ok(result.payload.rows.filter((row) => !row.RequiredNonEmpty).every((row) => row.Match));
});

test('standalone mode preserves the existing required-table behavior without the switch', () => {
  const result = runSummary(makeFixture({ includeStageTables: true }));
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.equal(result.payload.ok, true);
  assert.equal(result.payload.rows.length, 8);
  assert.ok(result.payload.rows.every((row) => row.RequiredNonEmpty === false && row.Match));
});

test('stage fixture mode rejects a missing required table', () => {
  const result = runSummary(makeFixture({ requireStageFixtures: true, omitRestore: ['community_audit'] }));
  assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`);
  assert.equal(result.payload.ok, false);
  assert.match(result.payload.error, /community_audit.*not present in both snapshots/);
});

test('stage fixture mode rejects an empty required table', () => {
  const result = runSummary(makeFixture({
    requireStageFixtures: true,
    sourceOverrides: { ai_summary_attempt: { Count: 0 } },
    restoreOverrides: { ai_summary_attempt: { Count: 0 } },
  }));
  assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`);
  assert.equal(result.payload.ok, false);
  assert.match(result.payload.error, /ai_summary_attempt.*nonempty/);
});

test('stage fixture mode rejects a changed required table even when its rowcount matches', () => {
  const result = runSummary(makeFixture({
    requireStageFixtures: true,
    restoreOverrides: { notification_stream_event: { Sha256: 'b'.repeat(64) } },
  }));
  assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`);
  assert.equal(result.payload.ok, false);
  assert.match(result.payload.error, /notification_stream_event.*did not match after restore/);
});

import assert from 'node:assert/strict';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const powershell = process.env.PWSH_BIN || 'pwsh';
const bash = process.env.BASH_BIN || 'bash';
const pythonCommand = process.env.PYTHON_BIN || (process.platform === 'win32' ? 'python' : 'python3');
const powershellSource = readFileSync(join(repoRoot, 'scripts/Test-ComposeRuntime.ps1'), 'utf8');
const bashSource = readFileSync(join(repoRoot, 'scripts/ci/compose-browser-smoke.sh'), 'utf8');

function makeTempDirectory(prefix = 'bluealbum-m0-acceptance-') {
  return mkdtempSync(join(tmpdir(), prefix));
}

function removeHarness(directory) {
  const target = resolve(directory);
  assert.equal(dirname(target), resolve(tmpdir()));
  assert.ok(target.split(/[\\/]/).pop().startsWith('bluealbum-m0-acceptance-'));
  rmSync(target, { recursive: true, force: true });
}

function quotePowerShell(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function runPowerShell(source, env = {}) {
  const directory = makeTempDirectory();
  const scriptPath = join(directory, 'harness.ps1');
  writeFileSync(scriptPath, source, 'utf8');
  try {
    return spawnSync(powershell, ['-NoProfile', '-NonInteractive', '-File', scriptPath], {
      cwd: repoRoot,
      env: { ...process.env, ...env },
      encoding: 'utf8',
      windowsHide: true,
    });
  } finally {
    removeHarness(directory);
  }
}

function runBash(source, args = [], env = {}) {
  const directory = makeTempDirectory();
  const scriptPath = join(directory, 'harness.sh');
  writeFileSync(scriptPath, source, 'utf8');
  chmodSync(scriptPath, 0o755);
  try {
    return spawnSync(bash, ['--noprofile', '--norc', scriptPath, ...args], {
      cwd: repoRoot,
      env: {
        ...process.env,
        PYTHON_BIN: pythonCommand,
        BASH_BIN: bash,
        ...env,
      },
      encoding: 'utf8',
      windowsHide: true,
    });
  } finally {
    removeHarness(directory);
  }
}

function writeJUnit(directory, name, count, { failure = false, error = false, skipped = false, malformed = false } = {}) {
  const reportPath = join(directory, name);
  if (malformed) {
    writeFileSync(reportPath, '<testsuite><testcase>', 'utf8');
    return reportPath;
  }
  const testcase = (index) => {
    const child = index === 0 && failure ? '<failure message="failed" />' :
      index === 0 && error ? '<error message="error" />' :
        index === 0 && skipped ? '<skipped />' : '';
    return `<testcase classname="m0" name="case-${index}">${child}</testcase>`;
  };
  writeFileSync(reportPath, `<testsuites><testsuite name="m0" tests="${count}" failures="${failure ? 1 : 0}" errors="${error ? 1 : 0}" skipped="${skipped ? 1 : 0}">${Array.from({ length: count }, (_, index) => testcase(index)).join('')}</testsuite></testsuites>`, 'utf8');
  return reportPath;
}

const powershellJunitFunction = powershellSource.slice(
  powershellSource.indexOf('function Assert-PytestJunitReport'),
  powershellSource.indexOf('function Invoke-VerificationCompose'),
);
const powershellResourceFunctions = powershellSource.slice(
  powershellSource.indexOf('function Get-ComposeProjectResources'),
  powershellSource.indexOf('New-Item -ItemType Directory'),
);
const bashFunctionPrefix = 'set -Eeuo pipefail\nCOMPOSE_ARGS=()\n' +
  ['die', 'compose', 'assert_project_unused', 'validate_junit', 'cleanup'].map((name) => {
    const match = bashSource.match(new RegExp(`^${name}\\(\\) \\{[\\s\\S]*?^\\}`, 'm'));
    assert.ok(match, `Production Bash function ${name} must exist`);
    return match[0];
  }).join('\n');

function runPowerShellJunit(reportPath, expected, exitCode = 0) {
  return runPowerShell(`
$ErrorActionPreference = 'Stop'
${powershellJunitFunction}
try {
  $actual = Assert-PytestJunitReport -ReportPath ${quotePowerShell(reportPath)} -SuiteName 'M0 test' -ExpectedTestCases ${expected} -ExitCode ${exitCode}
  if ($actual -ne ${expected}) { throw "Unexpected testcase count returned: $actual" }
  exit 0
} catch {
  Write-Error $_
  exit 1
}
`);
}

test('PowerShell JUnit gate accepts exactly 14/1 and rejects count, status, XML, failure, error, and skip violations', () => {
  const directory = makeTempDirectory();
  try {
    const cases = [
      ['valid-api', 14, 14, 0, {}],
      ['valid-browser', 1, 1, 0, {}],
      ['too-few', 13, 14, 0, {}],
      ['too-many', 15, 14, 0, {}],
      ['zero', 0, 14, 0, {}],
      ['nonzero-exit', 14, 14, 1, {}],
      ['failure', 14, 14, 0, { failure: true }],
      ['error', 14, 14, 0, { error: true }],
      ['skipped', 14, 14, 0, { skipped: true }],
      ['bad-xml', 14, 14, 0, { malformed: true }],
    ];
    for (const [name, actual, expected, exitCode, options] of cases) {
      const reportPath = writeJUnit(directory, `${name}.xml`, actual, options);
      const result = runPowerShellJunit(reportPath, expected, exitCode);
      const shouldPass = name === 'valid-api' || name === 'valid-browser';
      assert.equal(result.status, shouldPass ? 0 : 1, `${name}: ${result.stdout}\n${result.stderr}`);
    }
  } finally {
    removeHarness(directory);
  }
});

test('Bash JUnit gate accepts exactly 14/1 and rejects count, status nodes, and malformed XML', { concurrency: false }, () => {
  const directory = makeTempDirectory();
  try {
    const cases = [
      ['valid-api', 14, 14, {}],
      ['valid-browser', 1, 1, {}],
      ['too-few', 13, 14, {}],
      ['too-many', 15, 14, {}],
      ['zero', 0, 14, {}],
      ['failure', 14, 14, { failure: true }],
      ['error', 14, 14, { error: true }],
      ['skipped', 14, 14, { skipped: true }],
      ['bad-xml', 14, 14, { malformed: true }],
    ];
    for (const [name, actual, expected, options] of cases) {
      const reportPath = writeJUnit(directory, `${name}.xml`, actual, options);
      const result = runBash(`${bashFunctionPrefix}\nvalidate_junit "$1" "$2"\n`, [reportPath, String(expected)], {
        GITHUB_RUN_ID: '39114919729',
        GITHUB_RUN_ATTEMPT: '1',
        M0_CI_ARTIFACT_DIR: `outputs/.m0-acceptance-${process.pid}`,
      });
      const shouldPass = name === 'valid-api' || name === 'valid-browser';
      assert.equal(result.status, shouldPass ? 0 : 1, `${name}: ${result.stdout}\n${result.stderr}`);
    }
  } finally {
    removeHarness(directory);
  }
});

test('PowerShell project preflight checks labeled networks and refuses cleanup ownership on present or unreadable networks', () => {
  const directory = makeTempDirectory();
  try {
    const fakeLog = join(directory, 'docker.log');
    for (const mode of ['clean', 'network-present', 'network-read-fail', 'container-read-fail', 'volume-read-fail']) {
      writeFileSync(fakeLog, '', 'utf8');
      const result = runPowerShell(`
$ErrorActionPreference = 'Stop'
${powershellResourceFunctions}
$env:FAKE_DOCKER_LOG = ${quotePowerShell(fakeLog)}
$env:FAKE_DOCKER_MODE = ${quotePowerShell(mode)}
function docker {
  $command = $args -join ' '
  Add-Content -LiteralPath $env:FAKE_DOCKER_LOG -Value $command
  if ($command -match '^(compose|down) ') { throw 'Preflight must not mutate resources' }
  $global:LASTEXITCODE = 0
  if (($env:FAKE_DOCKER_MODE -eq 'container-read-fail' -and $command -match '^ps ') -or
      ($env:FAKE_DOCKER_MODE -eq 'volume-read-fail' -and $command -match '^volume ls')) { $global:LASTEXITCODE = 17 }
  if ($command -match '^network ls') {
    if ($env:FAKE_DOCKER_MODE -eq 'network-present') { return 'existing-network' }
    if ($env:FAKE_DOCKER_MODE -eq 'network-read-fail') { $global:LASTEXITCODE = 17 }
  }
}
try {
  Assert-ProjectUnused -Project 'bluealbum-m0-aaaaaaaaaa'
  exit 0
} catch {
  exit 1
}
`);
      assert.equal(result.status, mode === 'clean' ? 0 : 1, `${mode}: ${result.stdout}\n${result.stderr}`);
      const log = readFileSync(fakeLog, 'utf8');
      if (!['container-read-fail', 'volume-read-fail'].includes(mode)) assert.match(log, /network ls/);
      assert.doesNotMatch(log, /compose down/);
    }
  } finally {
    removeHarness(directory);
  }
});

test('Bash preflight refuses a labeled network and cleanup fails closed when network inventory is unreadable', { concurrency: false }, () => {
  const directory = makeTempDirectory();
  try {
    const fakeBin = join(directory, 'bin');
    const fakeDocker = join(fakeBin, 'docker');
    const fakeLog = join(directory, 'docker.log');
    const fakeDockerSource = '#!/usr/bin/env bash\nset -u\nprintf \'%s\\n\' "$*" >> "$FAKE_DOCKER_LOG"\nif [[ "$*" == *"network ls"* ]]; then\n  if [[ "${FAKE_DOCKER_MODE:-}" == network-present ]]; then printf \'network-id\\n\'; fi\n  if [[ "${FAKE_DOCKER_MODE:-}" == network-read-fail ]]; then exit 17; fi\nfi\nexit 0\n';
    mkdirSync(fakeBin, { recursive: true });
    writeFileSync(fakeDocker, fakeDockerSource, 'utf8');
    chmodSync(fakeDocker, 0o755);

    writeFileSync(fakeLog, '', 'utf8');
    const preflight = runBash(`${bashFunctionPrefix}\nPROJECT='bluealbum-ci-test'\nassert_project_unused\n`, [], {
      PATH: `${fakeBin}${delimiter}${process.env.PATH || ''}`,
      FAKE_DOCKER_LOG: fakeLog,
      FAKE_DOCKER_MODE: 'network-present',
      GITHUB_RUN_ID: '39114919729',
      GITHUB_RUN_ATTEMPT: '1',
      M0_CI_ARTIFACT_DIR: `outputs/.m0-acceptance-${process.pid}`,
    });
    assert.equal(preflight.status, 1, `${preflight.stdout}\n${preflight.stderr}`);
    const preflightLog = readFileSync(fakeLog, 'utf8');
    assert.match(preflightLog, /network ls/);
    assert.doesNotMatch(preflightLog, /compose down/);

    writeFileSync(fakeLog, '', 'utf8');
    const artifactDirectory = join(directory, 'artifacts');
    const cleanup = runBash(`${bashFunctionPrefix}
PROJECT='bluealbum-ci-test'
ARTIFACT_DIR=${JSON.stringify(artifactDirectory)}
LOG_DIR=${JSON.stringify(join(artifactDirectory, 'logs'))}
ENV_FILE=${JSON.stringify(join(artifactDirectory, 'ci-runtime.env'))}
COMPOSE_TOUCHED=1
CREDENTIALS_CREATED=0
cleanup
`, [], {
      PATH: `${fakeBin}${delimiter}${process.env.PATH || ''}`,
      FAKE_DOCKER_LOG: fakeLog,
      FAKE_DOCKER_MODE: 'network-read-fail',
      GITHUB_RUN_ID: '39114919729',
      GITHUB_RUN_ATTEMPT: '1',
      M0_CI_ARTIFACT_DIR: `outputs/.m0-acceptance-${process.pid}`,
    });
    assert.equal(cleanup.status, 1, `${cleanup.stdout}\n${cleanup.stderr}`);
    const cleanupLog = readFileSync(fakeLog, 'utf8');
    assert.match(cleanupLog, /compose .*down/);
    assert.match(cleanupLog, /network ls/);
  } finally {
    removeHarness(directory);
  }
});

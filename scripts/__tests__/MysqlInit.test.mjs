import assert from 'node:assert/strict'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
const bashBin = process.env.BASH_BIN || 'bash'

function toBashPath(filePath) {
  const normalized = resolve(filePath).replaceAll('\\', '/')
  if (process.platform !== 'win32') return normalized
  const drive = normalized.match(/^([A-Za-z]):\//)
  return drive ? `/${drive[1].toLowerCase()}${normalized.slice(2)}` : normalized
}

function createHarness() {
  const root = mkdtempSync(join(tmpdir(), 'bluealbum-mysql-init-'))
  const binDir = join(root, 'bin')
  const capturePath = join(root, 'mysql.sql')
  mkdirSync(binDir)
  const mysqlPath = join(binDir, 'mysql')
  writeFileSync(
    mysqlPath,
    [
      '#!/bin/sh',
      'set -eu',
      ': "${MYSQL_SQL_CAPTURE:?missing SQL capture path}"',
      'if [ "${MYSQL_INIT_TEST_EXIT:-0}" -ne 0 ]; then exit "$MYSQL_INIT_TEST_EXIT"; fi',
      'case "$*" in',
      '  *"FROM mysql.db"*"REPLACE("*) printf "%s\\n" "${MYSQL_RUNTIME_GRANT:-0}" ;;',
      '  *"FROM mysql.db"*) printf "%s\\n" "${MYSQL_LEGACY_GRANT:-0}" ;;',
      '  *"--database=m0_core -Nse SELECT DATABASE()"*) [ "$3" = "m0_core_user" ] ;;',
      '  *"--database=m0_notify_test -Nse SELECT DATABASE()"*) [ "$3" = "m0_notify_user" ] || [ "$3" = "m0_notify_ddl" ] ;;',
      '  *"-Nse SELECT 1"*) exit "${MYSQL_REPLACEMENT_AUTH_EXIT:-0}" ;;',
      '  *) cat >> "$MYSQL_SQL_CAPTURE" ;;',
      'esac',
      '',
    ].join('\n'),
    { encoding: 'utf8', mode: 0o755 },
  )
  try {
    chmodSync(mysqlPath, 0o755)
  } catch {
    // Git Bash on Windows may not expose POSIX mode changes through Node.
  }
  return {
    binDir,
    capturePath,
    cleanup: () => rmSync(root, { recursive: true, force: true }),
  }
}

function childEnv(harness, values) {
  const env = { ...process.env }
  delete env.BASH_ENV
  delete env.ENV
  delete env.MYSQL_ONETIME_PASSWORD
  env.PATH = `${toBashPath(harness.binDir)}:/usr/bin:/bin`
  env.MYSQL_SQL_CAPTURE = toBashPath(harness.capturePath)
  Object.assign(env, values)
  return env
}

function runBash(args, env) {
  const result = spawnSync(bashBin, args, {
    cwd: repoRoot,
    encoding: 'utf8',
    env,
    windowsHide: true,
  })
  if (result.error) throw new Error('Bash is required for the MySQL initializer regression tests')
  return result
}

function runSourceProbe(scriptPath, env, expectSuccess) {
  const successCheck = expectSuccess
    ? 'if [ "$source_status" -ne 0 ]; then exit 21; fi'
    : 'if [ "$source_status" -eq 0 ]; then exit 21; fi'
  const command = `
set +e
set +u
before_flags=$-
before_pwd=$MYSQL_PWD
before_exporter=$MYSQL_EXPORTER_PASSWORD
unset MYSQL_ONETIME_PASSWORD
source ${scriptPath}
source_status=$?
after_flags=$-
after_pwd=$MYSQL_PWD
after_exporter=$MYSQL_EXPORTER_PASSWORD
${successCheck}
if [ "$before_flags" != "$after_flags" ]; then exit 22; fi
if [ "$before_pwd" != "$after_pwd" ]; then exit 23; fi
if [ "$before_exporter" != "$after_exporter" ]; then exit 24; fi
if [ "\${MYSQL_ONETIME_PASSWORD+x}" = "x" ]; then exit 25; fi
MYSQL_ONETIME_PASSWORD="$MYSQL_ONETIME_PASSWORD"
`
  const result = runBash(['-c', command], env)
  assert.equal(result.status, 0, `source probe failed for ${scriptPath}`)
}

function readCapturedSql(harness) {
  assert.ok(existsSync(harness.capturePath), 'mock mysql was not invoked')
  return readFileSync(harness.capturePath, 'utf8')
}

test('notification database initializer isolates shell state when sourced', () => {
  const harness = createHarness()
  try {
    runSourceProbe(
      'scripts/init-notification-db.sh',
      childEnv(harness, {
        NOTIFICATION_DB_NAME: 'm0_notify_test',
        MYSQL_USER: 'm0_notify_user',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_PWD: 'parent-test-password',
      }),
      true,
    )
    const sql = readCapturedSql(harness)
    assert.ok(sql.includes('CREATE DATABASE IF NOT EXISTS `m0_notify_test`'), 'notification database SQL did not execute')
    assert.ok(sql.includes("GRANT ALL PRIVILEGES ON `m0_notify_test`.* TO 'm0_notify_user'@'%';"), 'notification grant SQL did not execute')
  } finally {
    harness.cleanup()
  }
})

test('dedicated notification account provisions a literal schema grant and removes the legacy grant', () => {
  const harness = createHarness()
  try {
    const env = childEnv(harness, {
      MYSQL_DATABASE: 'm0_core',
      MYSQL_USER: 'm0_core_user',
      MYSQL_PASSWORD: 'Core-DB-fixture-key-1234567890',
      MYSQL_ROOT_PASSWORD: 'Root-DB-fixture-key-1234567890',
      MYSQL_LEGACY_GRANT: '1',
      NOTIFICATION_DB_NAME: 'm0_notify_test',
      NOTIFICATION_DB_USER: 'm0_notify_user',
      NOTIFICATION_DB_PASSWORD: 'Notify-DB-fixture-key-1234567890',
    })
    const result = runBash(['scripts/init-notification-db.sh'], env)
    assert.equal(result.status, 0, result.stderr)
    const sql = readCapturedSql(harness)
    assert.ok(sql.includes("CREATE USER IF NOT EXISTS 'm0_notify_user'@'%'"))
    assert.ok(sql.includes("GRANT ALL PRIVILEGES ON `m0\\_notify\\_test`.* TO 'm0_notify_user'@'%';"))
    assert.ok(sql.includes("REVOKE ALL PRIVILEGES ON `m0_notify_test`.* FROM 'm0_core_user'@'%';"))

    writeFileSync(harness.capturePath, '')
    const rejected = runBash(['scripts/init-notification-db.sh'], { ...env, MYSQL_REPLACEMENT_AUTH_EXIT: '43' })
    assert.equal(rejected.status, 43)
    assert.doesNotMatch(readCapturedSql(harness), /GRANT ALL|REVOKE ALL/)
  } finally {
    harness.cleanup()
  }
})

test('migration account caps the previous runtime schema grant and validates separate credentials', () => {
  const harness = createHarness()
  try {
    const env = childEnv(harness, {
      MYSQL_DATABASE: 'm0_core',
      MYSQL_USER: 'm0_core_user',
      MYSQL_PASSWORD: 'Core-DB-fixture-key-1234567890',
      MYSQL_ROOT_PASSWORD: 'Root-DB-fixture-key-1234567890',
      MYSQL_LEGACY_GRANT: '1',
      MYSQL_RUNTIME_GRANT: '1',
      NOTIFICATION_DB_NAME: 'm0_notify_test',
      NOTIFICATION_DB_USER: 'm0_notify_user',
      NOTIFICATION_DB_PASSWORD: 'Notify-DB-fixture-key-1234567890',
      NOTIFICATION_DB_MIGRATION_USER: 'm0_notify_ddl',
      NOTIFICATION_DB_MIGRATION_PASSWORD: 'Migrate-DB-fixture-key-1234567890',
    })
    const result = runBash(['scripts/init-notification-db.sh'], env)
    assert.equal(result.status, 0, result.stderr)
    const sql = readCapturedSql(harness)
    const grant = '`m0\\_notify\\_test`.*'
    assert.ok(sql.includes(`GRANT ALL PRIVILEGES ON ${grant} TO 'm0_notify_ddl'@'%';`))
    const revoke = sql.indexOf(`REVOKE ALL PRIVILEGES ON ${grant} FROM 'm0_notify_user'@'%';`)
    assert.ok(revoke >= 0)
    assert.ok(sql.indexOf(`GRANT SELECT, INSERT, UPDATE ON ${grant} TO 'm0_notify_user'@'%';`) > revoke)
    assert.ok(!sql.includes(`GRANT ALL PRIVILEGES ON ${grant} TO 'm0_notify_user'@'%';`))

    writeFileSync(harness.capturePath, '')
    const rejected = runBash(['scripts/init-notification-db.sh'], {
      ...env,
      NOTIFICATION_DB_MIGRATION_PASSWORD: env.NOTIFICATION_DB_PASSWORD,
    })
    assert.equal(rejected.status, 1)
    assert.equal(readCapturedSql(harness), '')
  } finally {
    harness.cleanup()
  }
})

test('exporter initializer isolates shell state when sourced', () => {
  const harness = createHarness()
  try {
    runSourceProbe(
      'perf/docker/mysql/init-exporter.sh',
      childEnv(harness, {
        MYSQL_EXPORTER_PASSWORD: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_PWD: 'parent-test-password',
      }),
      true,
    )
    const sql = readCapturedSql(harness)
    assert.ok(sql.includes("CREATE USER 'exporter'@'%' IDENTIFIED BY '0123456789abcdef"), 'exporter user SQL did not execute')
    assert.ok(sql.includes('GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO'), 'exporter grant SQL did not execute')
  } finally {
    harness.cleanup()
  }
})

test('direct initializer execution preserves valid and invalid input status', () => {
  const cases = [
    {
      scriptPath: 'scripts/init-notification-db.sh',
      valid: {
        NOTIFICATION_DB_NAME: 'm0_notify_test',
        MYSQL_USER: 'm0_notify_user',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_PWD: 'parent-test-password',
      },
      invalid: {
        NOTIFICATION_DB_NAME: 'm0-notify-invalid',
        MYSQL_USER: 'm0_notify_user',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_PWD: 'parent-test-password',
      },
      marker: 'CREATE DATABASE IF NOT EXISTS `m0_notify_test`',
    },
    {
      scriptPath: 'perf/docker/mysql/init-exporter.sh',
      valid: {
        MYSQL_EXPORTER_PASSWORD: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_PWD: 'parent-test-password',
      },
      invalid: {
        MYSQL_EXPORTER_PASSWORD: 'not-a-valid-exporter-password',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_PWD: 'parent-test-password',
      },
      marker: "CREATE USER 'exporter'@'%' IDENTIFIED BY '0123456789abcdef",
    },
  ]

  for (const scenario of cases) {
    const validHarness = createHarness()
    try {
      const result = runBash([scenario.scriptPath], childEnv(validHarness, scenario.valid))
      assert.equal(result.status, 0, `direct initializer failed for ${scenario.scriptPath}`)
      assert.ok(readCapturedSql(validHarness).includes(scenario.marker), `direct SQL did not execute for ${scenario.scriptPath}`)
    } finally {
      validHarness.cleanup()
    }

    const invalidHarness = createHarness()
    try {
      const invalidEnv = childEnv(invalidHarness, scenario.invalid)
      runSourceProbe(scenario.scriptPath, invalidEnv, false)
      const result = runBash([scenario.scriptPath], invalidEnv)
      assert.notEqual(result.status, null, `direct initializer could not run for ${scenario.scriptPath}`)
      assert.notEqual(result.status, 0, `invalid input unexpectedly succeeded for ${scenario.scriptPath}`)
    } finally {
      invalidHarness.cleanup()
    }
  }
})

test('SQL failures still stop sourced and direct initializers', () => {
  for (const scriptPath of ['scripts/init-notification-db.sh', 'perf/docker/mysql/init-exporter.sh']) {
    const harness = createHarness()
    try {
      const env = childEnv(harness, {
        NOTIFICATION_DB_NAME: 'm0_notify_test',
        MYSQL_USER: 'm0_notify_user',
        MYSQL_ROOT_PASSWORD: 'M0-test-root-password',
        MYSQL_EXPORTER_PASSWORD: '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
        MYSQL_INIT_TEST_EXIT: '42',
      })
      const sourced = runBash(['-c', `set -e; source ${scriptPath}; exit 99`], env)
      assert.equal(sourced.status, 42, `sourced SQL failure was swallowed for ${scriptPath}`)
      const direct = runBash([scriptPath], env)
      assert.equal(direct.status, 42, `direct SQL failure was swallowed for ${scriptPath}`)
    } finally {
      harness.cleanup()
    }
  }
})

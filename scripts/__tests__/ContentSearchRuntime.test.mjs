import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { delimiter, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const bash = process.env.BASH_BIN || 'bash'
const scriptSource = readFileSync(join(repoRoot, 'scripts/ci/content-search-smoke.sh'), 'utf8')

function extractFunction(name) {
  const match = scriptSource.match(new RegExp(`^${name}\\(\\) \\{[\\s\\S]*?^\\}`, 'm'))
  assert.ok(match, `production Bash function ${name} must exist`)
  return match[0]
}

const lifecycleFunctions = [
  'die',
  'validate_port',
  'container_port',
  'container_volume_names',
  'assert_owned_container',
  'record_container_port',
  'update_container_port_metadata',
  'refresh_container_port',
  'start_role',
].map(extractFunction).join('\n')

function makeHarness(mode) {
  const directory = mkdtempSync(join(tmpdir(), 'bluealbum-content-search-'))
  const fakeBin = join(directory, 'bin')
  const fakeDocker = join(fakeBin, 'docker')
  const logPath = join(directory, 'docker.log')
  const artifactRoot = join(directory, 'artifacts')
  const metadataPath = join(artifactRoot, 'container-record.json')
  mkdirSync(fakeBin, { recursive: true })
  mkdirSync(artifactRoot, { recursive: true })
  const runId = 'abcdef1234'
  const rabbitName = `bluealbum-m2-rabbit-${runId}`
  const esName = `bluealbum-m2-es-${runId}`
  const rabbitId = 'a'.repeat(64)
  const esId = 'b'.repeat(64)
  const metadata = {
    runId,
    containers: {
      rabbit: {
        name: rabbitName,
        id: rabbitId,
        label: runId,
        anonymousVolumes: [{ type: 'volume', name: 'rabbit-volume', destination: '/var/lib/rabbitmq' }],
        loopbackPort: 35672,
        initialLoopbackPort: 35672,
        currentLoopbackPort: 35672,
        portHistory: [35672],
      },
      es: {
        name: esName,
        id: esId,
        label: runId,
        anonymousVolumes: [{ type: 'volume', name: 'es-volume', destination: '/usr/share/elasticsearch/data' }],
        loopbackPort: 39200,
        initialLoopbackPort: 39200,
        currentLoopbackPort: 39200,
        portHistory: [39200],
      },
    },
  }
  const fakeDockerSource = `#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\\n' "$*" >> "\${FAKE_DOCKER_LOG}"
case "\${1:-}" in
  start)
    exit 0
    ;;
  port)
    if [[ "\${FAKE_DOCKER_MODE:-}" == 'non-loopback' ]]; then
      printf '0.0.0.0:45672\\n'
    elif [[ "\${2:-}" == '${rabbitName}' ]]; then
      printf '127.0.0.1:45672\\n'
    elif [[ "\${2:-}" == '${esName}' ]]; then
      printf '127.0.0.1:49200\\n'
    else
      exit 1
    fi
    ;;
  inspect)
    format="\${3:-}"
    name="\${4:-}"
    if [[ "\${format}" == '{{.Id}}' ]]; then
      case "\${name}" in
        '${rabbitName}') printf '${rabbitId}\\n' ;;
        '${esName}') printf '${esId}\\n' ;;
        *) exit 1 ;;
      esac
    elif [[ "\${format}" == *'.Config.Labels'* ]]; then
      printf '${runId}\\n'
    elif [[ "\${format}" == '{{json .Mounts}}' ]]; then
      case "\${name}" in
        '${rabbitName}') printf '[{"Type":"volume","Name":"rabbit-volume","Destination":"/var/lib/rabbitmq"}]\\n' ;;
        '${esName}') printf '[{"Type":"volume","Name":"es-volume","Destination":"/usr/share/elasticsearch/data"}]\\n' ;;
        *) exit 1 ;;
      esac
    elif [[ "\${format}" == '{{.State.Running}}' ]]; then
      printf 'true\\n'
    else
      exit 1
    fi
    ;;
  *)
    exit 1
    ;;
esac
`
  writeFileSync(fakeDocker, fakeDockerSource, 'utf8')
  chmodSync(fakeDocker, 0o755)
  writeFileSync(logPath, '', 'utf8')
  writeFileSync(metadataPath, JSON.stringify(metadata, null, 2) + '\n', 'utf8')

  const harness = `
set -Eeuo pipefail
IFS=$'\\n\\t'
declare -A CONTAINER_NAME=()
declare -A CONTAINER_ID=()
declare -A CONTAINER_VOLUMES=()
declare -A CONTAINER_INITIAL_PORT=()
declare -A CONTAINER_CURRENT_PORT=()
declare -A CONTAINER_PORT_HISTORY=()
RUN_ID='${runId}'
ARTIFACT_ROOT=${JSON.stringify(artifactRoot)}
RABBIT_PORT='35672'
ES_PORT='39200'
ES_URL='http://127.0.0.1:39200'
SEARCH_DATABASE='ba_search_runtime_${runId}'
CONTENT_SEARCH_TEST_RABBIT_PORT='35672'
CONTENT_SEARCH_TEST_ELASTIC_URL='http://127.0.0.1:39200'
CONTAINER_NAME[rabbit]='${rabbitName}'
CONTAINER_NAME[es]='${esName}'
CONTAINER_ID[rabbit]='${rabbitId}'
CONTAINER_ID[es]='${esId}'
CONTAINER_VOLUMES[rabbit]='rabbit-volume'
CONTAINER_VOLUMES[es]='es-volume'
CONTAINER_INITIAL_PORT[rabbit]='35672'
CONTAINER_INITIAL_PORT[es]='39200'
CONTAINER_CURRENT_PORT[rabbit]='35672'
CONTAINER_CURRENT_PORT[es]='39200'
CONTAINER_PORT_HISTORY[rabbit]='35672'
CONTAINER_PORT_HISTORY[es]='39200'
${lifecycleFunctions}
start_role rabbit
start_role es
printf 'rabbit=%s env=%s\\n' "\$RABBIT_PORT" "\$CONTENT_SEARCH_TEST_RABBIT_PORT"
printf 'es=%s env=%s url=%s\\n' "\$ES_PORT" "\$CONTENT_SEARCH_TEST_ELASTIC_URL" "\$ES_URL"
python -c 'import os; print("child-rabbit=" + os.environ["CONTENT_SEARCH_TEST_RABBIT_PORT"]); print("child-es=" + os.environ["CONTENT_SEARCH_TEST_ELASTIC_URL"])'
`
  const result = spawnSync(bash, ['--noprofile', '--norc', '-c', harness], {
    cwd: repoRoot,
    env: {
      ...process.env,
      PATH: `${fakeBin}${delimiter}${process.env.PATH || ''}`,
      FAKE_DOCKER_LOG: logPath,
      FAKE_DOCKER_MODE: mode,
    },
    encoding: 'utf8',
    windowsHide: true,
  })
  return { directory, result, metadataPath }
}

test('refreshes RabbitMQ and Elasticsearch ports after restart and records history', () => {
  const harness = makeHarness('changed')
  try {
    assert.equal(harness.result.status, 0, `${harness.result.stdout}\n${harness.result.stderr}`)
    assert.match(harness.result.stdout, /rabbit=45672 env=45672/)
    assert.match(
      harness.result.stdout,
      /es=49200 env=http:\/\/127\.0\.0\.1:49200 url=http:\/\/127\.0\.0\.1:49200/,
    )
    assert.match(harness.result.stdout, /child-rabbit=45672/)
    assert.match(harness.result.stdout, /child-es=http:\/\/127\.0\.0\.1:49200/)
    const metadata = JSON.parse(readFileSync(harness.metadataPath, 'utf8'))
    assert.deepEqual(metadata.containers.rabbit.portHistory, [35672, 45672])
    assert.equal(metadata.containers.rabbit.initialLoopbackPort, 35672)
    assert.equal(metadata.containers.rabbit.currentLoopbackPort, 45672)
    assert.equal(metadata.containers.rabbit.loopbackPort, 45672)
    assert.deepEqual(metadata.containers.es.portHistory, [39200, 49200])
    assert.equal(metadata.containers.es.initialLoopbackPort, 39200)
    assert.equal(metadata.containers.es.currentLoopbackPort, 49200)
    assert.equal(metadata.containers.es.loopbackPort, 49200)
  } finally {
    rmSync(harness.directory, { recursive: true, force: true })
  }
})

test('rejects a restarted service published outside loopback', () => {
  const harness = makeHarness('non-loopback')
  try {
    assert.equal(harness.result.status, 1, `${harness.result.stdout}\n${harness.result.stderr}`)
    assert.match(harness.result.stderr, /loopback-only dynamic port/)
  } finally {
    rmSync(harness.directory, { recursive: true, force: true })
  }
})

#!/usr/bin/env python3
"""Compare matched WebSocket authentication loads on isolated HTTP and TLS gRPC nodes."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
from urllib.parse import urlsplit
from uuid import uuid4

import httpx
import websocket

from chat_cutover_support import ScenarioError, register, require


def docker(*args):
    result = subprocess.run(['docker', *args], timeout=10, text=True, capture_output=True, check=False)
    require(result.returncode == 0, f'Docker {args[0]} could not inspect the rehearsal.')
    return result.stdout


def loopback(value):
    address = urlsplit(value)
    if (address.scheme != 'http' or address.hostname != '127.0.0.1' or not address.port
            or address.username or address.path or address.query or address.fragment):
        raise argparse.ArgumentTypeError('Expected a loopback HTTP origin and port.')
    return value


def verify_node(project, container, transport):
    require(re.fullmatch('[0-9a-f]{64}', container), 'Expected a complete rehearsal container ID.')
    node, = json.loads(docker('inspect', container))
    labels = node['Config']['Labels']
    require(labels.get('com.docker.compose.project') == project
            and labels.get('com.docker.compose.oneoff', '').lower() == 'true'
            and labels.get('com.docker.compose.service') == 'chat-realtime',
            'Realtime node is outside this isolated test.')
    settings = dict(pair.split('=', 1) for pair in node['Config']['Env'] if '=' in pair)
    require(bool(settings.get('CHAT_REALTIME_GRPC_ADDR')) == (transport == 'grpc')
            and bool(settings.get('CHAT_REALTIME_GRPC_CREDENTIAL')) == (transport == 'grpc')
            and (transport == 'http' or bool(settings.get('CHAT_REALTIME_GRPC_TLS_CA')))
            and settings.get('OTEL_TRACES_EXPORTER') == 'none',
            'Realtime fixture did not start with the expected transport and telemetry configuration.')
    bindings = node['NetworkSettings']['Ports'].get('8092/tcp', [])
    require(len(bindings) == 1 and bindings[0]['HostIp'] == '127.0.0.1',
            'Expected one loopback-only realtime port binding.')
    return node['Image'], f"http://127.0.0.1:{int(bindings[0]['HostPort'])}"


def wait_healthy(http, nodes):
    for address in nodes.values():
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            try:
                if http.get(address + '/health', timeout=2).status_code == 200:
                    break
            except httpx.HTTPError:
                pass
            time.sleep(0.25)
        else:
            raise ScenarioError('A rehearsal realtime node did not become healthy.')


def handshake(address, origin, token):
    start = time.perf_counter()
    socket = None
    try:
        socket = websocket.create_connection(
            address.replace('http://', 'ws://', 1) + '/api/v1/chat/realtime',
            origin=origin, http_no_proxy=['127.0.0.1'], timeout=5,
        )
        socket.send(token)
        require(socket.recv() == 'ready', 'Realtime node did not authenticate a rehearsal session.')
        return (time.perf_counter() - start) * 1000
    except (OSError, websocket.WebSocketException):
        raise ScenarioError('Realtime session handshake did not complete.') from None
    finally:
        if socket:
            socket.close(timeout=0.5)


def cgroup_sample(container):
    usage, memory, peak = docker(
        'exec', container, 'sh', '-ec',
        "grep '^usage_usec ' /sys/fs/cgroup/cpu.stat; "
        'cat /sys/fs/cgroup/memory.current /sys/fs/cgroup/memory.peak',
    ).splitlines()
    return int(usage.split()[1]), int(memory), int(peak)


def workload(node, origin, tokens, workers, operations):
    # Fixed lanes keep concurrent sockets below the server's per-account limit.
    def run(lane):
        token = tokens[lane % len(tokens)]
        return [handshake(node, origin, token) for _ in range(lane, operations, workers)]

    with ThreadPoolExecutor(max_workers=workers) as pool:
        start = time.perf_counter()
        latencies = [latency for lane in pool.map(run, range(workers)) for latency in lane]
        elapsed = time.perf_counter() - start
    return elapsed, latencies


def measure(container, node, origin, tokens, workers, operations):
    cpu_before, _, _ = cgroup_sample(container)
    elapsed, latencies = workload(node, origin, tokens, workers, operations)
    cpu_after, memory, peak = cgroup_sample(container)
    latency = sorted(latencies)
    require(cpu_after >= cpu_before and memory > 0 and peak >= memory, 'Invalid container resource sample.')
    return {
        'successfulHandshakes': len(latency),
        'failedHandshakes': 0,
        'elapsedSeconds': round(elapsed, 4),
        'handshakesPerSecond': round(len(latency) / elapsed, 2),
        'p50Ms': round(statistics.median(latency), 3),
        'p95Ms': round(latency[(95 * len(latency) + 99) // 100 - 1], 3),
        'cpuMicroseconds': cpu_after - cpu_before,
        'memoryAfterBytes': memory,
        'memoryPeakSinceStartBytes': peak,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', type=loopback, required=True)
    parser.add_argument('--frontend', type=loopback, required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--http-container', required=True)
    parser.add_argument('--grpc-container', required=True)
    parser.add_argument('--parallel', type=int, default=8)
    parser.add_argument('--operations', type=int, default=240)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()

    require(re.fullmatch(r'bluealbum-ci-[a-z0-9-]+', args.project), 'Expected an isolated CI project name.')
    require(args.http_container != args.grpc_container, 'HTTP and gRPC must use different rehearsal nodes.')
    require(1 <= args.parallel <= 16 and args.parallel <= args.operations <= 2000, 'Invalid load bounds.')
    containers = {'http': args.http_container, 'grpc': args.grpc_container}
    fixtures = {mode: verify_node(args.project, cid, mode) for mode, cid in containers.items()}
    require(fixtures['http'][0] == fixtures['grpc'][0], 'Both realtime nodes must use the same image.')
    gateway_container = docker('ps', '-q', '--filter', f'label=com.docker.compose.project={args.project}',
                               '--filter', 'label=com.docker.compose.service=gateway').strip()
    require(re.fullmatch('[0-9a-f]{12,64}', gateway_container)
            and docker('port', gateway_container, '9000/tcp').strip() == urlsplit(args.gateway).netloc,
            'Gateway does not belong to this isolated stack.')
    nodes = {mode: value[1] for mode, value in fixtures.items()}

    with httpx.Client(timeout=10, trust_env=False) as http:
        wait_healthy(http, nodes)
        tokens = [register(http, args.gateway + '/api/v1', uuid4().hex[:10], 'b')[1]['token']
                  for _ in range((args.parallel + 1) // 2)]
    warmup = max(32, args.parallel)
    for mode in ('http', 'grpc'):
        workload(nodes[mode], args.frontend, tokens, args.parallel, warmup)

    results = {'http': [], 'grpc': []}
    for order in (('http', 'grpc'), ('grpc', 'http')):
        for mode in order:
            results[mode].append(measure(
                containers[mode], nodes[mode], args.frontend, tokens, args.parallel, args.operations,
            ))
    evidence = {
        'status': 'passed',
        'parallelism': args.parallel,
        'syntheticAccounts': len(tokens),
        'operationsPerRound': args.operations,
        'warmupPerTransport': warmup,
        'roundOrder': ['http,grpc', 'grpc,http'],
        'latencyScope': 'connect, credential frame and ready response',
        'resourceScope': 'Go container cgroup v2, including transport, excluding Java Core',
        'image': fixtures['http'][0],
        'transports': results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(f"Matched realtime authentication load passed: {2 * args.operations} per transport.")


if __name__ == '__main__':
    try:
        main()
    except (ScenarioError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error) if isinstance(error, ScenarioError) else 'Docker did not answer in time.') from None

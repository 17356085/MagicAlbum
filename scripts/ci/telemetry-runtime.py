#!/usr/bin/env python3
"""Verify a real BlueAlbum request in the private Tempo and Loki backends."""

import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen
from uuid import uuid4


SERVICES = {'bluealbum-gateway', 'bluealbum-core'}


def loopback(value):
    address = urlparse(value)
    if (address.scheme != 'http' or address.hostname not in {'127.0.0.1', 'localhost'}
            or not address.port or address.username or address.path or address.query or address.fragment):
        raise argparse.ArgumentTypeError('Use a loopback HTTP origin with an explicit port.')
    return value


def request_json(url, headers=None):
    with urlopen(Request(url, headers={'Accept': 'application/json', **(headers or {})}), timeout=6) as response:
        return json.load(response)


def service_name(resource):
    for attribute in resource.get('attributes', []):
        if attribute.get('key') == 'service.name':
            return attribute.get('value', {}).get('stringValue')
    return None


def trace_links(tempo, trace_id):
    document = request_json(f'{tempo}/api/traces/{trace_id}')
    spans = []
    for batch in document.get('batches', document.get('resourceSpans', [])):
        service = service_name(batch.get('resource', {}))
        if service not in SERVICES:
            continue
        for scope in batch.get('scopeSpans', batch.get('instrumentationLibrarySpans', [])):
            for span in scope.get('spans', []):
                spans.append((service, span.get('spanId'), span.get('parentSpanId')))

    gateway_ids = {span_id for service, span_id, _ in spans if service == 'bluealbum-gateway'}
    linked = any(service == 'bluealbum-core' and parent in gateway_ids for service, _, parent in spans)
    return len(spans) if linked else None


def log_services(loki, request_id, start):
    query = '{service_name=~"bluealbum-(gateway|core)"} |= "' + request_id + '"'
    params = urlencode({'query': query, 'start': start, 'end': time.time_ns(), 'limit': 30})
    result = request_json(f'{loki}/loki/api/v1/query_range?{params}')
    services = {stream['stream'].get('service_name') for stream in result['data']['result']}
    return sorted(services) if services == SERVICES else None


def wait_for(read):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            value = read()
            if value is not None:
                return value
        except (HTTPError, URLError, TimeoutError):
            pass
        time.sleep(1)
    raise SystemExit('Timed out waiting for private telemetry readback.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', required=True, type=loopback)
    parser.add_argument('--tempo', required=True, type=loopback)
    parser.add_argument('--loki', required=True, type=loopback)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    start = time.time_ns()
    trace_id = uuid4().hex
    request_id = 'telemetry-smoke-' + uuid4().hex[:16]
    sections = request_json(args.gateway + '/api/v1/sections?page=1&size=1', {
        'traceparent': f'00-{trace_id}-{uuid4().hex[:16]}-01',
        'X-Request-ID': request_id,
    })
    if not isinstance(sections.get('items'), list):
        raise SystemExit('Unexpected sections response from the BlueAlbum gateway.')

    spans = wait_for(lambda: trace_links(args.tempo, trace_id))
    services = wait_for(lambda: log_services(args.loki, request_id, start))
    evidence = {
        'status': 'passed', 'traceId': trace_id, 'requestId': request_id,
        'gatewayToCoreParentLink': True, 'verifiedSpans': spans, 'logServices': services,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Verify a real BlueAlbum request in the private Tempo and Loki backends."""

import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from uuid import uuid4

import httpx
import websocket

from chat_cutover_support import ScenarioError, register, require
from telemetry_support import ancestor_link, request_json, trace_spans


SERVICES = {'bluealbum-gateway', 'bluealbum-core'}


def loopback(value):
    address = urlparse(value)
    if (address.scheme != 'http' or address.hostname not in {'127.0.0.1', 'localhost'}
            or not address.port or address.username or address.path or address.query or address.fragment):
        raise argparse.ArgumentTypeError('Use a loopback HTTP origin with an explicit port.')
    return value


def trace_links(tempo, trace_id):
    spans = [span for span in trace_spans(tempo, trace_id) if span['service'] in SERVICES]
    gateway_ids = {span['spanId'] for span in spans if span['service'] == 'bluealbum-gateway'}
    linked = any(span['service'] == 'bluealbum-core' and span.get('parentSpanId') in gateway_ids for span in spans)
    return len(spans) if linked else None


def trace_component_counts(tempo, trace_id):
    spans = trace_spans(tempo, trace_id)
    services = {span['service'] for span in spans if span['service']}
    return {'traceId': trace_id, 'serviceSpanCounts': {
        service: sum(span['service'] == service for span in spans) for service in sorted(services)
    }}


def chat_auth_link(tempo, trace_id, transport):
    spans = trace_spans(tempo, trace_id)
    go_spans = [span for span in spans if span['service'] == 'bluealbum-chat-realtime']
    roots = {span['spanId'] for span in go_spans if span['name'] == 'chat.realtime.authenticate'}
    callers = {span['spanId'] for span in go_spans if span['name'] == 'chat.session.verify'
               and ancestor_link(spans, span, roots)}
    endpoint = 'VerifySession' if transport == 'grpc' else '/api/v1/chat/realtime/session'
    for span in spans:
        # Instrumentation versions may place the operation in the span name, RPC method or HTTP route.
        description = ' '.join([span['name'], *(attribute.get('value', {}).get('stringValue', '')
                             for attribute in span.get('attributes', []))])
        if (span['service'] == 'bluealbum-core' and endpoint in description
                and ancestor_link(spans, span, callers)):
            return len(spans)
    return None


def chat_broker_link(tempo, trace_id):
    spans = trace_spans(tempo, trace_id)
    writers = {span['spanId'] for span in spans if span['service'] == 'bluealbum-chat'
               and (span.get('kind') in (4, 'SPAN_KIND_PRODUCER') or
                    'chat.realtime.changed.v1' in span['name'])}
    for span in spans:
        if (span['service'] == 'bluealbum-chat-realtime' and span['name'] == 'chat.realtime.changed process'
                and (ancestor_link(spans, span, writers)
                     or any(link.get('spanId') in writers for link in span.get('links', [])))):
            return len(spans)
    return None


def traceparent(trace_id):
    return f'00-{trace_id}-{uuid4().hex[:16]}-01'


def verify_home_bff(http, gateway, personas):
    endpoint = gateway + '/api/v1/home/overview'
    anonymous = http.get(endpoint)
    require(anonymous.status_code == 200, 'public home BFF returned an unexpected status')
    public = anonymous.json()
    require(all(isinstance(public.get(key), dict) and isinstance(public[key].get('items'), list)
                for key in ('threads', 'sections')), 'home BFF public aggregates are missing')
    require(all(public.get(key) is None for key in ('profile', 'following', 'unreadNotifications')),
            'anonymous home BFF returned account-specific data')
    for account in personas:
        response = http.get(endpoint, headers={'Authorization': f"Bearer {account['token']}"})
        require(response.status_code == 200, 'signed-in home BFF returned an unexpected status')
        response_cache = response.headers.get('Cache-Control', '')
        require('private' in response_cache and 'no-store' in response_cache, 'signed-in home BFF is cacheable')
        page = response.json()
        require(str((page.get('profile') or {}).get('id')) == str(account['id']),
                'home BFF returned the wrong profile')
        require(page['threads'] == public['threads'] and page['sections'] == public['sections'],
                'home BFF public aggregates differ by account')


def exercise_traced_chat(gateway, frontend, realtime, tempo, transport, home_bff):
    with httpx.Client(timeout=10, trust_env=False) as http:
        api = gateway + '/api/v1'
        marker = uuid4().hex[:10]
        sender, sending_account = register(http, api, marker, 'a')
        receiving_api, receiver = register(http, api, marker, 'b')
        receiving_api.request('PATCH', '/chat/settings', body={'allowRequests': True})

        if home_bff:
            verify_home_bff(http, gateway, (sending_account, receiver))

        auth_trace, message_trace = uuid4().hex, uuid4().hex
        # Check the public route, then start the traced session on the private Go listener.
        # This anchors the trace ID at the same process that calls the Java verifier.
        public_socket = websocket.create_connection(
            gateway.replace('http://', 'ws://', 1) + '/api/v1/chat/realtime',
            timeout=8, origin=frontend, http_no_proxy=['127.0.0.1', 'localhost'],
        )
        try:
            public_socket.send(receiver['token'])
            require(public_socket.recv() == 'ready', 'gateway WebSocket route did not authenticate')
        finally:
            public_socket.shutdown()
        address = realtime.replace('http://', 'ws://', 1) + '/api/v1/chat/realtime'
        socket = websocket.create_connection(
            address, timeout=8, origin=frontend, http_no_proxy=['127.0.0.1', 'localhost'],
            header={'traceparent': traceparent(auth_trace)},
        )
        try:
            socket.send(receiver['token'])
            require(socket.recv() == 'ready', 'chat socket did not authenticate')
            response = http.post(
                f"{api}/chat/direct/{receiver['id']}/messages",
                headers={'X-Device-Id': sender.device, 'Authorization': f'Bearer {sender.token}',
                         'traceparent': traceparent(message_trace)},
                json={'clientMessageId': str(uuid4()), 'content': 'Trace rehearsal message'},
            )
            require(response.status_code == 201, 'chat trace rehearsal did not persist the message')
            require(socket.recv() == 'changed', 'chat socket did not receive the committed message signal')
        finally:
            socket.shutdown()

    return {
        'sessionTransport': transport,
        'gatewayWebSocketAuthenticated': True,
        'homeBffPersonaIsolation': home_bff,
        'authTraceId': auth_trace,
        'authVerifiedSpans': wait_for('Go-to-Java session verification',
                                     lambda: chat_auth_link(tempo, auth_trace, transport),
                                     lambda: trace_component_counts(tempo, auth_trace)),
        'goToJavaSessionLink': True,
        'messageTraceId': message_trace,
        'messageVerifiedSpans': wait_for('Java-to-Go RabbitMQ signal',
                                        lambda: chat_broker_link(tempo, message_trace),
                                        lambda: trace_component_counts(tempo, message_trace)),
        'javaRabbitToGoLink': True,
    }


def log_services(loki, request_id, start):
    query = '{service_name=~"bluealbum-(gateway|core)"} |= "' + request_id + '"'
    params = urlencode({'query': query, 'start': start, 'end': time.time_ns(), 'limit': 30})
    result = request_json(f'{loki}/loki/api/v1/query_range?{params}')
    services = {stream['stream'].get('service_name') for stream in result['data']['result']}
    return sorted(services) if services == SERVICES else None


def wait_for(description, read, diagnose=None):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            value = read()
            if value is not None:
                return value
        except (HTTPError, URLError, TimeoutError):
            pass
        time.sleep(1)
    if diagnose is not None:
        try:
            print(json.dumps(diagnose()))
        except (HTTPError, URLError, TimeoutError):
            pass
    raise SystemExit(f'Timed out waiting for {description} telemetry readback.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gateway', required=True, type=loopback)
    parser.add_argument('--tempo', required=True, type=loopback)
    parser.add_argument('--loki', required=True, type=loopback)
    parser.add_argument('--frontend', type=loopback, help='Also exercise traced chat in an isolated split stack.')
    parser.add_argument('--realtime', type=loopback, help='The isolated Go listener used for the traced upgrade.')
    parser.add_argument('--chat-transport', choices=['http', 'grpc'], default='http')
    parser.add_argument('--home-bff', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if bool(args.frontend) != bool(args.realtime):
        parser.error('traced chat needs both --frontend and --realtime')
    start = time.time_ns()
    trace_id = uuid4().hex
    request_id = 'telemetry-smoke-' + uuid4().hex[:16]
    sections = request_json(args.gateway + '/api/v1/sections?page=1&size=1', {
        'traceparent': traceparent(trace_id),
        'X-Request-ID': request_id,
    })
    if not isinstance(sections.get('items'), list):
        raise SystemExit('Unexpected sections response from the BlueAlbum gateway.')

    spans = wait_for('gateway-to-core HTTP', lambda: trace_links(args.tempo, trace_id))
    services = wait_for('gateway and core logs', lambda: log_services(args.loki, request_id, start))
    evidence = {
        'status': 'passed', 'traceId': trace_id, 'requestId': request_id,
        'gatewayToCoreParentLink': True, 'verifiedSpans': spans, 'logServices': services,
    }
    if args.frontend:
        evidence['chat'] = exercise_traced_chat(
            args.gateway, args.frontend, args.realtime, args.tempo, args.chat_transport, args.home_bff,
        )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence))


if __name__ == '__main__':
    try:
        main()
    except (ScenarioError, httpx.HTTPError, websocket.WebSocketException) as error:
        # HTTP and WebSocket exception bodies can include sensitive application data.
        raise SystemExit(str(error) if isinstance(error, ScenarioError) else type(error).__name__) from None

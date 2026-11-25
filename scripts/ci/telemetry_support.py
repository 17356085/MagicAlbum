"""Read parent relationships from the private Tempo endpoint."""

from urllib.request import Request, urlopen
import json


def request_json(url, headers=None):
    with urlopen(Request(url, headers={'Accept': 'application/json', **(headers or {})}), timeout=6) as response:
        return json.load(response)


def service_name(resource):
    for attribute in resource.get('attributes', []):
        if attribute.get('key') == 'service.name':
            return attribute.get('value', {}).get('stringValue')
    return None


def trace_spans(tempo, trace_id):
    document = request_json(f'{tempo}/api/traces/{trace_id}')
    spans = []
    for batch in document.get('batches', document.get('resourceSpans', [])):
        service = service_name(batch.get('resource', {}))
        for scope in batch.get('scopeSpans', batch.get('instrumentationLibrarySpans', [])):
            for span in scope.get('spans', []):
                spans.append({**span, 'service': service})
    return spans


def ancestor_link(spans, span, targets):
    parents = {item['spanId']: item.get('parentSpanId') for item in spans}
    parent = span.get('parentSpanId')
    while parent in parents:
        if parent in targets:
            return True
        parent = parents.pop(parent)
    return False

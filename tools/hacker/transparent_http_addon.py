"""Original-destination compatibility gate. Fixed lab upstream; no general Internet proxy yet."""
import asyncio
import json
from pathlib import Path
import time
from mitmproxy import ctx, http

BASE = Path('/lab')
PREFIX = '/capri-lab/c82aa79e80e7edb2'
UPSTREAM = ('192.168.1.3', 18765)
SOURCES = {'192.168.1.6', '192.168.1.8'}


class OriginalDestinationProxy:
    def __init__(self):
        self.started = time.monotonic()
        self.clients = set()

    def log(self, kind, **fields):
        with (BASE / 'transparent-events.jsonl').open('a') as f:
            f.write(json.dumps({'event': kind, **fields}) + '\n')

    async def running(self):
        self.log('ready')
        asyncio.create_task(self.watch())

    async def watch(self):
        while time.monotonic() - self.started < 180 and not (BASE / 'transparent.stop').exists():
            await asyncio.sleep(.2)
        ctx.master.shutdown()

    def client_connected(self, client):
        if client.peername and client.peername[0] == '192.168.1.8':
            self.clients.add(client.id)

    def client_disconnected(self, client):
        self.clients.discard(client.id)

    def request(self, flow: http.HTTPFlow):
        source = flow.client_conn.peername[0] if flow.client_conn.peername else ''
        path = flow.request.path.partition('?')[0]
        if source in {'192.168.1.6', '192.168.1.3'} and path in {'/_capri_proxy_health', '/_capri_proxy_idle'}:
            body = b'{"ready":true}' if path.endswith('health') else (b'{"idle":false}' if self.clients else b'{"idle":true}')
            flow.response = http.Response.make(200, body, {'Connection': 'close'})
            return
        original = flow.server_conn.address
        if source not in SOURCES or original != UPSTREAM or flow.request.scheme != 'http' or flow.request.method != 'GET' or path not in {PREFIX, PREFIX + '/probe', PREFIX + '/protocol/json'}:
            flow.response = http.Response.make(403, b'Outside original-destination probe scope.', {'Connection': 'close'})
            self.log('rejected', source=source)
            return
        # Preserve virtual-host metadata, but never let Host choose the network destination.
        host = flow.request.headers.get('Host')
        flow.request.host, flow.request.port = original
        if host:
            flow.request.headers['Host'] = host
        flow.request.headers['Connection'] = 'close'
        flow.request.headers['X-Capri-Lab-Request'] = 'Pixel-prototype'
        flow.metadata['capri_original'] = True
        self.log('original_destination', address=original[0], port=original[1], source=source)

    def server_connect(self, data):
        if data.server.address != UPSTREAM:
            data.server.error = 'Outside fixed original-destination scope'

    def response(self, flow: http.HTTPFlow):
        if flow.metadata.get('capri_original'):
            flow.response.headers['X-Capri-Original-Destination'] = 'verified'
            flow.response.headers['Connection'] = 'close'
            self.log('response', status=flow.response.status_code)


addons = [OriginalDestinationProxy()]

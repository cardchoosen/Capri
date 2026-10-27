"""Developer-only fixed HTTP fixture proxy. No generic upstream, CONNECT, TLS, or payload dumps."""
import asyncio
import json
from pathlib import Path
import time
from mitmproxy import ctx, http

BASE = Path('/lab')
PREFIX = '/capri-lab/c82aa79e80e7edb2'
ALLOWED = {'192.168.1.8', '192.168.1.6'}


class FixtureProxy:
    def __init__(self):
        self.requests = 0
        self.responses = 0
        self.started = time.monotonic()
        self.client_connections = set()

    def log(self, event, **fields):
        with (BASE / 'proxy-events.jsonl').open('a') as stream:
            stream.write(json.dumps({'event': event, 'monotonic': time.monotonic(), **fields}) + '\n')

    async def running(self):
        self.log('ready')
        asyncio.create_task(self.watch())

    async def watch(self):
        while time.monotonic() - self.started < 900 and not (BASE / 'stop').exists():
            await asyncio.sleep(.25)
        ctx.master.shutdown()

    def client_connected(self, client):
        if client.peername and client.peername[0] == '192.168.1.8':
            self.client_connections.add(client.id)

    def client_disconnected(self, client):
        self.client_connections.discard(client.id)

    def request(self, flow: http.HTTPFlow):
        source = flow.client_conn.peername[0] if flow.client_conn.peername else ''
        path = flow.request.path.partition('?')[0]
        if path in {'/_capri_proxy_health', '/_capri_proxy_idle'} and source in {'192.168.1.6', '192.168.1.3'}:
            body = b'{"ready":true}' if path.endswith('health') else (b'{"idle":false}' if self.client_connections else b'{"idle":true}')
            flow.response = http.Response.make(200, body, {'Content-Type': 'application/json', 'Connection': 'close'})
            return
        methods = {PREFIX: {'GET'}, PREFIX + '/probe': {'GET'}, PREFIX + '/event': {'GET'}}
        for case in ('json', 'binary', 'gzip', 'redirect', 'status', 'chunked'):
            methods[PREFIX + '/protocol/' + case] = {'GET'}
        methods[PREFIX + '/protocol/head'] = {'HEAD'}
        methods[PREFIX + '/protocol/echo'] = {'POST'}
        if source not in ALLOWED or flow.request.method not in methods.get(path, set()):
            flow.response = http.Response.make(403, b'Outside fixed HTTP fixture scope.', {'Connection': 'close'})
            self.log('rejected', source=source)
            return
        # Fixed upstream even if the client supplies an absolute URL or a different Host.
        flow.request.scheme = 'http'
        flow.request.host = '192.168.1.3'
        flow.request.port = 18765
        flow.request.headers['X-Capri-Lab-Request'] = 'Pixel-prototype'
        flow.request.headers['Connection'] = 'close'
        flow.metadata['capri_fixture'] = True
        self.requests += 1
        self.log('request', source=source, request_count=self.requests, kind='probe' if path.endswith('/probe') else 'fixture')

    def response(self, flow: http.HTTPFlow):
        if not flow.metadata.get('capri_fixture'):
            return
        flow.response.headers['X-Capri-Lab-Proxy'] = 'Pixel'
        flow.response.headers['Connection'] = 'close'
        flow.response.headers['Cache-Control'] = 'no-store'
        if flow.request.path.partition('?')[0] == PREFIX + '/probe' and flow.response.status_code == 200:
            data = json.loads(flow.response.get_text())
            data['proxy'] = 'Pixel'
            flow.response.text = json.dumps(data)
        self.responses += 1
        self.log('response', response_count=self.responses, status=flow.response.status_code)

    def error(self, flow: http.HTTPFlow):
        self.log('upstream_error')  # Never log raw request/response or error strings.

    def done(self):
        self.log('stopped', requests=self.requests, responses=self.responses)


addons = [FixtureProxy()]

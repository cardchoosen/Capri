"""Developer-only fixed HTTP fixture proxy. No generic upstream, CONNECT, TLS, or payload dumps."""
import asyncio
import json
from pathlib import Path
import time
from mitmproxy import ctx, http

BASE = Path('/lab')
PREFIX = '/capri-lab/c82aa79e80e7edb2'
ALLOWED = {'192.168.1.10', '192.168.1.8'}


class FixtureProxy:
    def __init__(self):
        self.requests = 0
        self.responses = 0
        self.started = time.monotonic()

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

    def request(self, flow: http.HTTPFlow):
        source = flow.client_conn.peername[0] if flow.client_conn.peername else ''
        path = flow.request.path.partition('?')[0]
        if path == '/_capri_proxy_health' and source in {'192.168.1.8', '192.168.1.16'}:
            flow.response = http.Response.make(200, b'{"ready":true}', {'Content-Type': 'application/json', 'Connection': 'close'})
            return
        if source not in ALLOWED or flow.request.method != 'GET' or path not in {PREFIX, PREFIX + '/probe', PREFIX + '/event'}:
            flow.response = http.Response.make(403, b'Outside fixed HTTP fixture scope.', {'Connection': 'close'})
            self.log('rejected', source=source)
            return
        # Fixed upstream even if the client supplies an absolute URL or a different Host.
        flow.request.scheme = 'http'
        flow.request.host = '192.168.1.16'
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

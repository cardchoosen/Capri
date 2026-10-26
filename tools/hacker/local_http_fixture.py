#!/usr/bin/env python3
"""Short-lived, single-target HTTP fixture. No filesystem serving or traffic interception."""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import TCPServer
import ipaddress
import json
from pathlib import Path
import secrets
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', required=True, type=ipaddress.IPv4Address)
    parser.add_argument('--target', required=True, type=ipaddress.IPv4Address)
    parser.add_argument('--port', type=int, default=18765)
    parser.add_argument('--seconds', type=int, default=600)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--token', help='Reuse a public fixture path when restarting the same experiment')
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535 or not 30 <= args.seconds <= 1800:
        parser.error('port must be 1024..65535; lifetime 30..1800 seconds')
    args.output.mkdir(parents=True, exist_ok=True)
    token = args.token or secrets.token_hex(8)
    if len(token) != 16 or any(c not in '0123456789abcdef' for c in token):
        parser.error('token must contain 16 lowercase hexadecimal characters')
    route = '/capri-lab/' + token
    deadline = time.monotonic() + args.seconds
    stop = args.output / 'stop'
    if stop.exists():
        raise RuntimeError('Stop marker exists; select a fresh output directory')
    counters = {'page': 0, 'probe': 0}

    def event(kind, **values):
        value = {'time': datetime.now(timezone.utc).isoformat(), 'event': kind, **values}
        with (args.output / 'events.jsonl').open('a') as stream:
            stream.write(json.dumps(value) + '\n')
        if kind != 'probe':
            print(json.dumps(value), flush=True)

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(2)

        def log_message(self, *_):
            pass  # Never log raw paths, headers, cookies, or request bodies.

        def reply(self, status, body=b'', content_type='text/plain; charset=utf-8', head=False):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Connection', 'close')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'")
            self.end_headers()
            if not head:
                self.wfile.write(body)

        def do_HEAD(self):
            if self.client_address[0] == str(args.bind) and self.path == '/health':
                return self.reply(200, head=True)
            self.reply(403, head=True)

        def do_GET(self):
            if self.client_address[0] != str(args.target):
                return self.reply(403, b'Not the selected test device.')
            if self.path == route:
                counters['page'] += 1
                event('page', source=str(args.target), count=counters['page'])
                page = '''<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Capri 网络测试</title>
<h2>Capri 局域网测试页</h2><p>已连接 Mac 的临时 HTTP 服务。</p><p>这是测试数据，不读取手机信息。</p>
<p id="state">正在检查连接…</p><p>请保持此页前台。页面每秒发送一次小型测试请求。</p>
<script>
let n=0;const label=document.getElementById('state');
async function probe(){try{const r=await fetch(location.pathname+'/probe',{cache:'no-store'});if(!r.ok)throw Error();const v=await r.json();label.textContent='连接正常 · 已完成 '+v.count+' 次请求';}catch(e){label.textContent='连接暂时中断，正在重试';}}
probe();let timer=setInterval(()=>{if(++n>=600){clearInterval(timer);label.textContent+=' · 本轮测试结束';return;}probe();},1000);
</script>'''
                return self.reply(200, page.encode(), 'text/html; charset=utf-8')
            if self.path == route + '/probe':
                counters['probe'] += 1
                event('probe', source=str(args.target), count=counters['probe'])
                return self.reply(200, json.dumps({'count': counters['probe']}).encode(), 'application/json')
            self.reply(404, b'No fixture at this path.')

    class LocalHTTPServer(HTTPServer):
        def server_bind(self):
            # HTTPServer normally does reverse DNS here; a LAN fixture needs none.
            TCPServer.server_bind(self)
            self.server_name = str(args.bind)
            self.server_port = self.server_address[1]

    server = LocalHTTPServer((str(args.bind), args.port), Handler)
    server.timeout = .5
    state = {'url': f'http://{args.bind}:{args.port}{route}', 'target': str(args.target), 'lifetime_seconds': args.seconds, 'traffic_interception': False}
    (args.output / 'state.json').write_text(json.dumps(state, indent=2) + '\n')
    event('ready', **state)
    try:
        while time.monotonic() < deadline and not stop.exists():
            try:
                server.handle_request()
            except (ConnectionError, TimeoutError):
                pass
    finally:
        server.server_close()
        event('stopped', **counters)


if __name__ == '__main__':
    main()

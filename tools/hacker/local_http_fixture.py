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
from http_protocol_fixture import protocol
from urllib.parse import urlsplit, parse_qs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', required=True, type=ipaddress.IPv4Address)
    parser.add_argument('--target', required=True, type=ipaddress.IPv4Address)
    parser.add_argument('--port', type=int, default=18765)
    parser.add_argument('--relay', type=ipaddress.IPv4Address, help='Explicit ordinary-UID proxy source allowed for this fixture')
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
        protocol_version = "HTTP/1.1"
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
            allowed = {str(args.target)} | ({str(args.relay)} if args.relay else set())
            if self.client_address[0] in allowed and protocol(self, urlsplit(self.path).path, route, head=True):
                return
            self.reply(403, head=True)

        def do_POST(self):
            allowed = {str(args.target)} | ({str(args.relay)} if args.relay else set())
            if self.client_address[0] not in allowed or self.path != route + '/protocol/echo':
                return self.reply(403)
            raw = self.headers.get('Content-Length', '')
            if self.headers.get('Transfer-Encoding') or not raw.isascii() or not raw.isdecimal() or len(raw) > 5 or int(raw) > 4096:
                return self.reply(413)
            payload = self.rfile.read(int(raw))
            if len(payload) != int(raw):
                return self.reply(400)
            event('protocol_post', source=self.client_address[0], bytes=len(payload))
            self.reply(200, payload, 'application/octet-stream')

        def do_GET(self):
            allowed = {str(args.target)} | ({str(args.relay)} if args.relay else set())
            if self.client_address[0] not in allowed:
                return self.reply(403, b'Not the selected test device.')
            parsed = urlsplit(self.path)
            if protocol(self, parsed.path, route):
                event('protocol_get', source=self.client_address[0])
                return
            if parsed.path == route and not parsed.query:
                counters['page'] += 1
                event('page', source=self.client_address[0], count=counters['page'])
                page = '''<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Capri 网络测试</title>
<h2>Capri 局域网测试页</h2><p>已连接 Mac 的临时 HTTP 服务。</p><p>这是测试数据，不读取手机信息。</p>
<p id="state">正在检查连接…</p><p>请保持此页前台。页面每秒发送一次小型测试请求。</p>
<script>
let n=0,failures=0;const label=document.getElementById('state');
async function probe(){const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),1800);
try{const q=new URLSearchParams({visibility:document.visibilityState,failures:String(failures),seq:String(++n)});
const r=await fetch(location.pathname+'/probe?'+q,{cache:'no-store',signal:controller.signal});if(!r.ok)throw Error();const v=await r.json();label.textContent='连接正常 · 已完成 '+v.count+' 次请求 · '+(document.hidden?'后台':'前台')+(v.proxy?' · Pixel代理':' · 直连');}
catch(e){failures++;label.textContent='连接暂时中断 · 重试次数 '+failures;}
finally{clearTimeout(timer);if(n<600)setTimeout(probe,1000);}}
document.addEventListener('visibilitychange',()=>fetch(location.pathname+'/event?state='+document.visibilityState,{cache:'no-store',keepalive:true}).catch(()=>{}));
probe();
</script>'''
                return self.reply(200, page.encode(), 'text/html; charset=utf-8')
            query = parse_qs(parsed.query, max_num_fields=4)
            if parsed.path == route + '/event':
                visibility = query.get('state', ['unknown'])[0]
                if visibility in ('visible', 'hidden'):
                    event('browser_state', source=self.client_address[0], visibility=visibility)
                return self.reply(200, b'OK')
            if parsed.path == route + '/probe':
                visibility = query.get('visibility', ['unknown'])[0]
                if visibility not in ('visible', 'hidden'):
                    visibility = 'unknown'
                def counter(name):
                    value = query.get(name, ['0'])[0]
                    return int(value) if value.isascii() and value.isdecimal() and len(value) <= 6 else 0
                counters['probe'] += 1
                event('probe', source=self.client_address[0], count=counters['probe'], visibility=visibility, failures=counter('failures'), sequence=counter('seq'), request_marked=self.headers.get('X-Capri-Lab-Request') == 'Pixel-prototype')
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

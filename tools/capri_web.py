#!/usr/bin/env python3
"""USB developer web client. Phone owns chats/tools; this loopback server stores no history.
Run: python3 tools/capri_web.py [--serial DEVICE] [--port 8765]
Debug APK must be installed. Open the printed one-time browser URL. Ctrl-C stops access.
"""
import argparse
import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shlex
import socket
import subprocess
import threading
from urllib.parse import quote

WEB = Path(__file__).with_name('web')
METHODS = {'web_state', 'web_create', 'web_stop', 'web_delete', 'web_select', 'web_attachment', 'send', 'status'}


class Bridge:
    """One persistent ADB-forwarded Unix connection, shared by browser clients."""
    def __init__(self, adb, serial):
        self.adb = [adb, '-s', serial]
        self.lock = threading.Lock()
        self.connection = None
        self.reader = None
        self.port = None
        self.phone_token = None

    def _content(self, method, args):
        payload = base64.b64encode(json.dumps(args, ensure_ascii=False).encode()).decode()
        command = ['content', 'call', '--uri', 'content://ai.capri.app.debug', '--method', method,
                   '--extra', 'payload:s:' + payload]
        result = subprocess.run(self.adb + ['shell', '-T', shlex.join(command)],
                                capture_output=True, text=True, timeout=12)
        start = result.stdout.find('{json=')
        if result.returncode or start < 0:
            raise RuntimeError('手机连接不可用；检查 USB、调试授权和 Capri。')
        return json.JSONDecoder().raw_decode(result.stdout[start + 6:])[0]

    def _disconnect(self):
        if self.reader is not None:
            self.reader.close(); self.reader = None
        if self.connection is not None:
            self.connection.close(); self.connection = None
        if self.port is not None:
            try:
                subprocess.run(self.adb + ['forward', '--remove', f'tcp:{self.port}'],
                               capture_output=True, timeout=5)
            except (OSError, subprocess.SubprocessError):
                pass
            self.port = None

    def _connect(self):
        info = self._content('web_connect', {})
        if 'error' in info:
            raise RuntimeError('请打开 Capri，并安装支持网页长连接的 Debug APK。')
        name = info.get('socket', '')
        if not name.startswith('ai.capri.app.web.') or not name.rsplit('.', 1)[-1].isdigit():
            raise RuntimeError('Invalid phone endpoint')
        result = subprocess.run(self.adb + ['forward', 'tcp:0', 'localabstract:' + name],
                                capture_output=True, text=True, timeout=8, check=True)
        self.port = int(result.stdout.strip())
        self.phone_token = info['token']
        self.connection = socket.create_connection(('127.0.0.1', self.port), timeout=8)
        self.connection.settimeout(8)
        self.reader = self.connection.makefile('rb')

    def call(self, method, args):
        if method not in METHODS:
            raise ValueError('Unsupported operation')
        if method == 'send' and not all(args.get(k) for k in ('chat_id', 'epoch', 'request_id')):
            raise ValueError('Select a chat and refresh before submitting')
        if len(json.dumps(args, ensure_ascii=False).encode()) > 48000:
            raise ValueError('Message too large')
        with self.lock:
            for attempt in range(2):
                try:
                    if self.connection is None:
                        self._connect()
                    payload = json.dumps({'token': self.phone_token, 'method': method, 'args': args},
                                         ensure_ascii=False).encode() + b'\n'
                    self.connection.sendall(payload)
                    raw = self.reader.readline(2_000_000)
                    if not raw.endswith(b'\n'):
                        raise OSError('Phone response incomplete')
                    return json.loads(raw)
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
                    self._disconnect()
                    # Re-reading is safe. Mutations are NEVER replayed after transport failure.
                    if method not in {'web_state', 'web_attachment', 'status'} or attempt:
                        raise RuntimeError('连接中断；请查看会话，未确认操作不会自动重发。') from None

    def close(self):
        with self.lock:
            self._disconnect()


def server_for(bridge, port=8765, token=None):
    token = token or secrets.token_urlsafe(32)

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.1'

        def log_message(self, *_):
            pass  # Neither credentials, tokens nor conversation text enter access logs.

        def reply(self, status, body, content_type='application/json; charset=utf-8'):
            if not isinstance(body, bytes):
                body = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' blob: data:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(body)

        def valid_host(self):
            return self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'

        def do_GET(self):
            if not self.valid_host():
                self.reply(403, {'error': 'Invalid host'}); return
            files = {'/': ('index.html', 'text/html; charset=utf-8'),
                     '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
                     '/style.css': ('style.css', 'text/css; charset=utf-8')}
            asset = files.get(self.path)
            if asset is None:
                self.reply(404, {'error': 'Not found'}); return
            self.reply(200, (WEB / asset[0]).read_bytes(), asset[1])

        def do_POST(self):
            # Close on rejected bodies: never reinterpret unread payload as a new request.
            self.close_connection = True
            origin = f'http://127.0.0.1:{self.server.server_port}'
            if not self.valid_host() or self.headers.get('Origin') != origin:
                self.reply(403, {'error': 'Invalid origin'}); return
            if not secrets.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token):
                self.reply(401, {'error': '请使用启动时输出的访问链接'}); return
            if self.path not in {'/api/call', '/api/attachment'}:
                self.reply(404, {'error': 'Not found'}); return
            try:
                if self.headers.get('Transfer-Encoding') or self.headers.get_content_type() != 'application/json':
                    raise ValueError('Expected JSON')
                length = int(self.headers.get('Content-Length', '-1'))
                if not 0 < length <= 48000:
                    raise ValueError('Invalid request size')
                self.connection.settimeout(15)
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError('Incomplete request')
                body = json.loads(raw)
                if not isinstance(body, dict) or not isinstance(body.get('args', {}), dict):
                    raise ValueError('Invalid request')
                if self.path == '/api/attachment':
                    self.attachment(body.get('args', {}))
                else:
                    if body.get('method') == 'web_attachment':
                        raise ValueError('Use attachment endpoint')
                    result = bridge.call(body.get('method'), body.get('args', {}))
                    self.reply(200, result)
            except (ValueError, TypeError):
                self.reply(400, {'error': '请求格式或大小不正确'})
            except (RuntimeError, subprocess.SubprocessError, OSError):
                self.reply(503, {'error': '连接中断或请求结果未确认。请恢复连接并查看会话；不会自动重发消息。'})

        def attachment(self, args):
            first = bridge.call('web_attachment', {**args, 'offset': 0})
            if 'error' in first:
                self.reply(409, {'error': first['error']}); return
            size = first.get('size', 0)
            if not isinstance(size, int) or not 0 < size <= 20_000_000:
                raise ValueError('Invalid attachment size')
            mime = first.get('mime')
            if mime not in {'image/png', 'image/jpeg', 'image/gif', 'image/webp'}:
                mime = 'application/octet-stream'
            self.send_response(200)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(size))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', "default-src 'none'; sandbox")
            self.send_header('Content-Disposition', "attachment; filename*=UTF-8''" + quote(str(first.get('name', 'attachment')), safe=''))
            self.end_headers()
            current = first; offset = 0
            try:
                while offset < size:
                    data = base64.b64decode(current['data'], validate=True)
                    if (current.get('offset') != offset or current.get('size') != size or not data
                            or len(data) > 65536 or offset + len(data) > size
                            or current.get('next_offset') != offset + len(data)):
                        raise ValueError('Attachment changed while reading')
                    self.wfile.write(data); offset += len(data)
                    if offset < size:
                        current = bridge.call('web_attachment', {**args, 'offset': offset})
                        if 'error' in current:
                            raise ValueError('Attachment no longer available')
            except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
                self.close_connection = True  # Partial content must fail, never become a second JSON response.

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    return server, token


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', default=os.environ.get('CAPRI_ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb')))
    p.add_argument('--serial')
    p.add_argument('--port', type=int, default=8765)
    args = p.parse_args()
    devices = subprocess.run([args.adb, 'devices', '-l'], capture_output=True, text=True, check=True, timeout=15)
    usb = [line.split()[0] for line in devices.stdout.splitlines() if '\tdevice ' in line and 'usb:' in line]
    serial = args.serial or (usb[0] if len(usb) == 1 else None)
    if not serial:
        p.error('请连接并授权一台 USB 手机，或使用 --serial 明确指定设备。')
    bridge = Bridge(args.adb, serial)
    subprocess.run(bridge.adb + ['shell', 'am', 'start', '-W', '-n', 'ai.capri.app/.MainActivity'], capture_output=True, check=True, timeout=20)
    server, token = server_for(bridge, args.port)
    print(f'Capri · http://127.0.0.1:{server.server_port}/#token={token}', flush=True)
    print('USB 开发入口；Ctrl-C 关闭。请保持 Capri 进程运行。', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        bridge.close()


if __name__ == '__main__':
    main()

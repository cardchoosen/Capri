#!/usr/bin/env python3
"""USB developer web client. Phone owns chats/tools; this loopback server stores no history.
Run: python3 tools/capri_web.py [--serial DEVICE] [--port 47863]
Debug APK must be installed. The server waits for an authorized device and restores the app/bridge.
Use capri_web_service.py install for macOS login autostart; Ctrl-C stops a manual server.
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
import time
import signal
import webbrowser
from urllib.parse import quote

DEFAULT_PORT = 47863
WEB = Path(__file__).with_name('web')
METHODS = {'web_state', 'web_revise', 'web_message', 'web_create', 'web_stop', 'web_delete', 'web_select', 'web_attachment', 'web_catalog', 'web_runtime', 'send', 'status'}


class BridgeError(RuntimeError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


class DeviceTarget:
    """Pin one authorized device. Reconnection never silently selects another phone."""
    def __init__(self, serial=None):
        self.serial = serial
        self.explicit = serial is not None
        self.last_launch = None
        self.lock = threading.Lock()

    def resolve(self, adb):
        result = subprocess.run([adb, 'devices', '-l'], capture_output=True, text=True, check=True, timeout=8)
        rows = [line.split() for line in result.stdout.splitlines()[1:] if line.strip()]
        with self.lock:
            if self.serial:
                row = next((r for r in rows if r[0] == self.serial), None)
                if row is None or len(row) < 2 or row[1] != 'device':
                    code = 'device_unauthorized' if row and len(row) > 1 and row[1] == 'unauthorized' else 'device_offline'
                    raise BridgeError(code, 'The selected Android device is not connected and authorized.')
                return self.serial
            usb = [r[0] for r in rows if len(r) > 2 and r[1] == 'device' and any(x.startswith('usb:') for x in r[2:])]
            if len(usb) != 1:
                raise BridgeError('device_ambiguous' if len(usb) > 1 else 'device_offline',
                                  'Connect and authorize one USB device, or select a serial explicitly.')
            self.serial = usb[0]
            return self.serial


class Bridge:
    """A persistent ADB connection with bounded, read-only recovery and no mutation replay."""
    def __init__(self, adb, serial=None, target=None):
        self.adb_path = adb
        self.target = target or DeviceTarget(serial)
        self.lock = threading.Lock()
        self.connection = None
        self.reader = None
        self.port = None
        self.phone_token = None
        self.last_used = 0

    @property
    def adb(self):
        if not self.target.serial:
            raise BridgeError('device_offline', 'No device has been selected.')
        return [self.adb_path, '-s', self.target.serial]

    def _content(self, method, args):
        payload = base64.b64encode(json.dumps(args, ensure_ascii=False).encode()).decode()
        command = ['content', 'call', '--uri', 'content://ai.capri.app.debug', '--method', method,
                   '--extra', 'payload:s:' + payload]
        result = subprocess.run(self.adb + ['shell', '-T', shlex.join(command)],
                                capture_output=True, text=True, timeout=12)
        start = result.stdout.find('{json=')
        if result.returncode or start < 0:
            raise BridgeError('bridge_unavailable', 'The debug provider is unavailable; check the USB authorization and debug APK.')
        return json.JSONDecoder().raw_decode(result.stdout[start + 6:])[0]

    def _disconnect(self):
        if self.reader is not None:
            self.reader.close(); self.reader = None
        if self.connection is not None:
            self.connection.close(); self.connection = None
        if self.port is not None:
            try:
                subprocess.run(self.adb + ['forward', '--remove', f'tcp:{self.port}'], capture_output=True, timeout=5)
            except (OSError, subprocess.SubprocessError):
                pass
            self.port = None
        self.last_used = 0

    def _connect(self):
        self.target.resolve(self.adb_path)
        try:
            info = self._content('web_connect', {})
        except BridgeError as error:
            if error.code != 'bridge_unavailable': raise
            info = {'error': 'not_ready: provider unavailable'}
        if str(info.get('error', '')).startswith('not_ready'):
            # Starting the Activity initializes its ViewModel; no message/task is submitted.
            with self.target.lock:
                now = time.monotonic()
                if self.target.last_launch is not None and now - self.target.last_launch < 15:
                    raise BridgeError('app_not_ready', 'Waiting for Capri startup; repeated launches are throttled.')
                self.target.last_launch = now
            subprocess.run(self.adb + ['shell', 'am', 'start', '-W', '-n', 'ai.capri.app/.MainActivity'],
                           capture_output=True, check=True, timeout=12)
            deadline = time.monotonic() + 6
            while 'error' in info and time.monotonic() < deadline:
                time.sleep(.2)
                info = self._content('web_connect', {})
                if 'error' in info and not str(info['error']).startswith('not_ready'):
                    break
        if 'error' in info:
            raise BridgeError('app_not_ready', 'Capri could not initialize its debug bridge. Open the phone app and check its state.')
        name = info.get('socket', '')
        if not name.startswith('ai.capri.app.web.') or not name.rsplit('.', 1)[-1].isdigit():
            raise BridgeError('invalid_endpoint', 'Invalid phone endpoint.')
        result = subprocess.run(self.adb + ['forward', 'tcp:0', 'localabstract:' + name],
                                capture_output=True, text=True, timeout=8, check=True)
        self.port = int(result.stdout.strip())
        self.phone_token = info['token']
        self.connection = socket.create_connection(('127.0.0.1', self.port), timeout=8)
        self.connection.settimeout(20)
        self.reader = self.connection.makefile('rb')

    def call(self, method, args):
        if method not in METHODS:
            raise ValueError('Unsupported operation')
        if method == 'send' and not all(args.get(k) for k in ('chat_id', 'epoch', 'request_id')):
            raise ValueError('Select a chat and refresh before submitting')
        if len(json.dumps(args, ensure_ascii=False).encode()) > 48000:
            raise ValueError('Message too large')
        with self.lock:
            # Android closes idle sockets after 15s. Discard ours before using an expired socket.
            if self.connection is not None and self.last_used and time.monotonic() - self.last_used >= 10:
                self._disconnect()
            for attempt in range(2):
                try:
                    if self.connection is None:
                        self._connect()
                    payload = json.dumps({'token': self.phone_token, 'method': method, 'args': args}, ensure_ascii=False).encode() + b'\n'
                    self.connection.sendall(payload)
                    raw = self.reader.readline(2_000_000)
                    if not raw.endswith(b'\n'):
                        raise OSError('Phone response incomplete')
                    result = json.loads(raw)
                    # An orphaned socket or weak ViewModel may survive Activity destruction.
                    if str(result.get('error', '')).startswith('not_ready'):
                        raise BridgeError('app_not_ready', 'Capri needs to initialize its debug bridge.')
                    self.last_used = time.monotonic()
                    return result
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
                    self._disconnect()
                    if method not in {'web_state', 'web_attachment', 'web_catalog', 'web_message', 'status'} or attempt:
                        if isinstance(error, BridgeError):
                            raise
                        raise BridgeError('connection_lost', 'Connection lost; inspect the chat before repeating an unconfirmed operation.') from None

    def close(self):
        with self.lock:
            self._disconnect()


class BridgePool:
    """Two phone workers: keep chat state independent of slow downloads/Run operations."""
    def __init__(self, adb, serial=None):
        target = DeviceTarget(serial)
        self.state = Bridge(adb, target=target)
        self.work = Bridge(adb, target=target)

    def call(self, method, args):
        return (self.state if method in {'web_state', 'status'} else self.work).call(method, args)

    def close(self):
        self.state.close(); self.work.close()


def tracker_frame(stream):
    """adb track-devices writes four hexadecimal length bytes, then that many UTF-8 bytes."""
    def read_exact(size):
        chunks = bytearray()
        while len(chunks) < size:
            chunk = stream.read(size - len(chunks))
            if not chunk: raise EOFError('Device tracker closed.')
            chunks.extend(chunk)
        return bytes(chunks)
    size = int(read_exact(4), 16)
    if not 0 <= size <= 65535: raise ValueError('Invalid device tracker frame.')
    return read_exact(size).decode('utf-8')


class DeviceMonitor:
    """ADB emits device changes; do not continuously poll device hardware or restart the app."""
    def __init__(self, bridge, connected=None):
        self.bridge = bridge
        self.connected = connected or (lambda: None)
        self.stopped = threading.Event()
        self.process = None
        self.thread = threading.Thread(target=self.run, name='CapriDeviceMonitor', daemon=True)

    def start(self):
        self.thread.start()

    def run(self):
        while not self.stopped.is_set():
            try:
                self.process = subprocess.Popen([self.bridge.state.adb_path, 'track-devices', '-l'],
                                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                while not self.stopped.is_set():
                    frame = tracker_frame(self.process.stdout)
                    if any(len(row.split()) > 1 and row.split()[1] == 'device' for row in frame.splitlines()):
                        for attempt in range(3):
                            try:
                                self.bridge.call('web_state', {})
                                self.connected()
                                break
                            except (RuntimeError, OSError, subprocess.SubprocessError):
                                if attempt == 2 or self.stopped.wait(2): break
            except (OSError, EOFError, ValueError, subprocess.SubprocessError):
                pass
            finally:
                if self.process is not None:
                    if self.process.poll() is None: self.process.terminate()
                    try: self.process.wait(timeout=3)
                    except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
                    if self.process.stdout: self.process.stdout.close()
            self.stopped.wait(3)

    def close(self):
        self.stopped.set()
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
        if self.thread.is_alive(): self.thread.join(timeout=3)


def access_token(path):
    """Keep old browser tabs usable after a desktop-service restart; owner-readable only."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink(): raise ValueError('Token path must not be a symbolic link.')
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(fd) as source: value = source.read(256).strip()
        if len(value) != 43 or not value.isascii() or not all(c.isalnum() or c in '-_' for c in value):
            raise ValueError('Invalid access token file.')
        os.chmod(path, 0o600)
        return value
    value = secrets.token_urlsafe(32)
    with os.fdopen(fd, 'w') as target: target.write(value + '\n')
    return value


def server_for(bridge, port=DEFAULT_PORT, token=None):
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
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, TimeoutError):
                self.close_connection = True

        def valid_host(self):
            return self.headers.get('Host') == f'127.0.0.1:{self.server.server_port}'

        def do_GET(self):
            if not self.valid_host():
                self.reply(403, {'error': 'Invalid host'}); return
            files = {'/': ('index.html', 'text/html; charset=utf-8'),
                     '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
                     '/run.js': ('run.js', 'text/javascript; charset=utf-8'),
                     '/models.js': ('models.js', 'text/javascript; charset=utf-8'),
                     '/inputs.js': ('inputs.js', 'text/javascript; charset=utf-8'),
                     '/chat-layout.js': ('chat-layout.js', 'text/javascript; charset=utf-8'),
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
            except (RuntimeError, subprocess.SubprocessError, OSError) as error:
                self.reply(503, {'error': str(error) if isinstance(error, BridgeError) else 'Connection unavailable; unconfirmed operations are not replayed.', 'code': error.code if isinstance(error, BridgeError) else 'connection_lost'})

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
    p.add_argument('--port', type=int, default=DEFAULT_PORT)
    p.add_argument('--state-dir', type=Path, default=Path(__file__).resolve().parents[1] / 'work' / 'web', help='Private local service state directory')
    p.add_argument('--open-browser', action='store_true', help='Open the access URL once when an authorized phone connects')
    args = p.parse_args()
    bridge = BridgePool(args.adb, args.serial)
    token_path = args.state_dir / f'access-{args.port}.token'
    server, token = server_for(bridge, args.port, access_token(token_path))
    url = f'http://127.0.0.1:{server.server_port}/#token={token}'
    print(f'Capri · {url}', flush=True)
    print('Waiting for an authorized USB device. Connection and app startup recover automatically.', flush=True)
    opened = False
    def connected():
        nonlocal opened
        if args.open_browser and not opened:
            opened = True
            webbrowser.open(url)
    monitor = DeviceMonitor(bridge, connected)
    monitor.start()
    # SIGTERM from launchd exits through finally, closing owned forwards and tracker processes.
    def stop(_signal, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        monitor.close()
        server.server_close()
        bridge.close()


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Bounded ordinary-UID reverse-proxy check against the fixed Mac HTTP fixture.
Does not install dependencies, change network rules, or intercept the original URL.
"""
import argparse
import json
from pathlib import Path
import subprocess
import shlex
import time
import urllib.error
import urllib.request

from proxy_runtime import BASE, ProxyRuntime

HOST = 'http://192.168.1.8:18766'
PREFIX = '/capri-lab/c82aa79e80e7edb2'


def get(path):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(HOST + path, timeout=2) as response:
            return response.status, response.read(1024)
    except urllib.error.HTTPError as error:
        return error.code, error.read(1024)


def health():
    try:
        status, body = get('/_capri_proxy_health')
        return status == 200 and json.loads(body) == {'ready': True}
    except (OSError, ValueError):
        return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 5 <= args.seconds <= 300:
        parser.error('Hold time must be 5..300 seconds')
    args.output.mkdir(parents=True, exist_ok=True)
    report = {'ordinary_uid': True, 'network_changes': False, 'client_verified': False}
    engine = None
    runtime = ProxyRuntime(args.adb, args.serial)
    try:
        # Refuse any listener; never stop an unknown process to reuse its port.
        import socket
        try:
            with socket.create_connection(('192.168.1.8', 18766), timeout=2):
                raise RuntimeError('Port already in use; stop its owning experiment first')
        except ConnectionRefusedError:
            pass
        addon = Path(__file__).with_name('http_proxy_addon.py').read_bytes()
        upload = runtime.adb + ['shell', '-T', 'run-as ai.capri.app /system/bin/sh -c ' + shlex.quote('cat > ' + BASE + '/addon.py')]
        subprocess.run(upload, input=addon, check=True, timeout=10, capture_output=True)
        runtime.shell(['/system/bin/run-as', 'ai.capri.app', '/system/bin/rm', '-f', BASE + '/stop', BASE + '/proxy-events.jsonl'], timeout=8).check_returncode()
        command = ['/lab/venv/bin/mitmdump', '-q', '--mode', 'reverse:http://192.168.1.16:18765',
                   '--listen-host', '192.168.1.8', '--listen-port', '18766', '--set', 'connection_strategy=lazy',
                   '--set', 'http2=false', '--set', 'block_global=false', '--set', 'block_private=false',
                   '--set', 'confdir=/lab/conf', '-s', '/lab/addon.py']
        with (args.output / 'engine.log').open('w') as log:
            engine = subprocess.Popen(runtime.command(command), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 30
            while not health():
                if engine.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Proxy did not become ready')
                time.sleep(.3)
            report['ready'] = True
            for path in (PREFIX + '/probe', '/outside'):
                status, _ = get(path)
                if status != 403:
                    raise RuntimeError('Source/path restriction failed')
            report['mac_rejected'] = True
            code = "import urllib.request,json;o=urllib.request.build_opener(urllib.request.ProxyHandler({}));r=o.open('" + HOST + PREFIX + "/probe?seq=1',timeout=5);d=json.load(r);assert r.status==200 and r.headers['X-Capri-Lab-Proxy']=='Pixel' and d['proxy']=='Pixel';print('proxy round trip verified')"
            result = runtime.run(['/lab/venv/bin/python', '-c', code], timeout=15)
            result.check_returncode()
            report['pixel_round_trip'] = True
            print(json.dumps({'event': 'ready', 'vivo_url': HOST + PREFIX, 'hold_seconds': args.seconds}), flush=True)
            deadline = time.monotonic() + args.seconds
            while time.monotonic() < deadline:
                if engine.poll() is not None:
                    raise RuntimeError('Engine exited during hold')
                time.sleep(.5)
    except BaseException as error:
        report['error_type'] = type(error).__name__
        raise
    finally:
        if engine is not None:
            runtime.shell(['/system/bin/run-as', 'ai.capri.app', '/system/bin/touch', BASE + '/stop'], timeout=8).check_returncode()
            try:
                engine.wait(timeout=15)
            except subprocess.TimeoutExpired:
                report['stop_timeout'] = True
                engine.terminate()  # Only this ADB client; remote process also has a fixed deadline.
                engine.wait(timeout=5)
            result = runtime.shell(['/system/bin/run-as', 'ai.capri.app', '/system/bin/cat', BASE + '/proxy-events.jsonl'], timeout=8)
            if result.returncode == 0:
                (args.output / 'events.jsonl').write_text(result.stdout)
                events = [json.loads(line) for line in result.stdout.splitlines()]
                report['client_verified'] = any(row.get('event') == 'request' and row.get('source') == '192.168.1.10' for row in events)
                report['engine_stopped'] = any(row.get('event') == 'stopped' for row in events) and not health()
            report['exit_code'] = engine.returncode
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)
    if not report.get('engine_stopped') or report.get('stop_timeout'):
        raise SystemExit('Engine shutdown was not verified')


if __name__ == '__main__':
    main()

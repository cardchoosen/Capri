#!/usr/bin/env python3
"""Developer adapter for an isolated proxy venv under Capri's ordinary Android UID. No root or network-rule changes."""
import argparse
import json
import ipaddress
from pathlib import Path
import re
import shlex
import subprocess

BASE = '/data/user/0/ai.capri.app/cache/hacker-proxy-prototype'


class ProxyRuntime:
    def __init__(self, adb, serial):
        self.adb = [adb, '-s', serial]
        result = subprocess.run(self.adb + ['shell', 'pm path ai.capri.app'], capture_output=True, text=True, check=True, timeout=8)
        paths = [line.removeprefix('package:') for line in result.stdout.splitlines() if line.startswith('package:') and line.endswith('/base.apk')]
        if len(paths) != 1:
            raise RuntimeError('Expected one Capri base APK')
        self.libraries = paths[0].rsplit('/', 1)[0] + '/lib/arm64'
        self.use_dns = self.shell(['/system/bin/run-as', 'ai.capri.app', '/system/bin/test', '-f', BASE + '/resolv.conf'], timeout=8).returncode == 0

    def shell(self, parts, **kwargs):
        return subprocess.run(self.adb + ['shell', shlex.join(parts)], capture_output=True, text=True, **kwargs)

    def command(self, guest):
        parts = [self.libraries + '/libproot.so', '--kill-on-exit', '--link2symlink', '-0', '-r',
                 '/data/user/0/ai.capri.app/files/runtime/ubuntu', '-b', '/dev', '-b', '/proc', *(['-b', BASE + '/resolv.conf:/etc/resolv.conf'] if self.use_dns else []), '-b', BASE + ':/lab', '-w', '/lab',
                 '/usr/bin/env', '-i', 'HOME=/lab/home', 'TMPDIR=/lab/tmp', 'PATH=/usr/local/bin:/usr/bin:/bin', 'LANG=C.UTF-8',
                 'PYTHONUNBUFFERED=1', 'PYTHONNOUSERSITE=1', 'PIP_CACHE_DIR=/lab/pip-cache', 'PIP_NO_INPUT=1', *guest]
        # Android env treats '=' anywhere in an operand as an assignment; APK paths contain '='.
        # Use a fixed sh executable, then exec the fully quoted PRoot command from it.
        outer = ['/system/bin/run-as', 'ai.capri.app', '/system/bin/env', '-i',
                 'PROOT_LOADER=' + self.libraries + '/libproot-loader.so', 'PROOT_TMP_DIR=' + BASE + '/tmp',
                 '/system/bin/sh', '-c', 'exec ' + shlex.join(parts)]
        return self.adb + ['shell', shlex.join(outer)]

    def run(self, guest, **kwargs):
        return subprocess.run(self.command(guest), capture_output=True, text=True, **kwargs)

    def configure_dns(self, address):
        content = 'nameserver ' + str(address) + '\noptions timeout:2 attempts:1'
        script = "printf '%s\\n' " + shlex.quote(content) + ' > ' + shlex.quote(BASE + '/resolv.conf')
        result = self.shell(['/system/bin/run-as', 'ai.capri.app', '/system/bin/sh', '-c', script], timeout=8)
        result.check_returncode()
        self.use_dns = True

    def create(self):
        result = self.shell(['/system/bin/run-as', 'ai.capri.app', '/system/bin/mkdir', '-p', BASE + '/tmp', BASE + '/home'], timeout=8)
        result.check_returncode()
        result = self.run(['/usr/bin/python3', '-m', 'venv', '/lab/venv'], timeout=60)
        if result.returncode:
            raise RuntimeError(result.stderr[:1200])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('action', choices=['create', 'configure-dns', 'resolve', 'install', 'install-offline', 'probe'])
    parser.add_argument('--version')
    parser.add_argument('--dns', type=ipaddress.IPv4Address)
    args = parser.parse_args()
    runtime = ProxyRuntime(args.adb, args.serial)
    if args.action == 'configure-dns':
        if not args.dns:
            parser.error('--dns required')
        runtime.configure_dns(args.dns)
        print('Per-prototype resolver configured; host/global resolver unchanged.')
        return
    if args.action == 'create':
        runtime.create()
        print('Isolated ordinary-UID venv created.', flush=True)
        return
    if args.action in ('resolve', 'install', 'install-offline'):
        if not args.version or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', args.version):
            parser.error('Explicit release version required')
        pip = ['/lab/venv/bin/python', '-m', 'pip', '--isolated', '--no-input', 'install', '--disable-pip-version-check', '--only-binary=:all:',
               '--index-url', 'https://pypi.org/simple', '--timeout', '15', '--retries', '1', '--report', '/lab/install-report.json']
        if args.action == 'resolve':
            pip += ['--dry-run']
        if args.action == 'install-offline':
            code = "import json,hashlib,pathlib;p=pathlib.Path('/lab/wheels');m=json.loads((p/'manifest.json').read_text());assert all(hashlib.sha256((p/x['filename']).read_bytes()).hexdigest()==x['sha256'] for x in m['files']);print('wheel hashes verified',len(m['files']))"
            checked = runtime.run(['/lab/venv/bin/python', '-c', code], timeout=15)
            checked.check_returncode()
            print(checked.stdout, end='', flush=True)
            pip += ['--no-index', '--find-links', '/lab/wheels']
        result = runtime.run(pip + ['mitmproxy==' + args.version], timeout=240)
    else:
        code = "import sys,platform,importlib.metadata,json;print(json.dumps({'python':sys.version.split()[0],'machine':platform.machine(),'mitmproxy':importlib.metadata.version('mitmproxy')}))"
        result = runtime.run(['/lab/venv/bin/python', '-c', code], timeout=15)
    print(result.stdout, end='', flush=True)
    if result.stderr:
        print(result.stderr[-2000:], end='', flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()

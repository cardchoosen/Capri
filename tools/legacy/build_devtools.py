"""Build the pinned ARM64 Git/curl/OpenSSH/Node/C/C++ layer without modifying the Python rootfs.

First resolve: python3 tools/build_devtools.py --resolve work/python-native-build/main-APKINDEX --community work/python-native-build/community-APKINDEX
Rebuild: python3 tools/build_devtools.py (verifies every cached/downloaded APK SHA256).
The index is fetched over HTTPS from Alpine; this does not perform APK signature verification.
"""
import argparse
import gzip
import hashlib
import io
import json
import posixpath
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'work/devtools-build'
LOCK = ROOT / 'docs/devtools-packages.json'
URL = 'https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolve', type=Path)
    parser.add_argument('--community', type=Path)
    args = parser.parse_args()
    BASE.mkdir(parents=True, exist_ok=True)
    if args.resolve:
        lookup = {}; indices = {}
        for repo, path in [('main', args.resolve), ('community', args.community)]:
            if path is None: continue
            indices[repo] = hashlib.sha256(path.read_bytes()).hexdigest()
            packages = [dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
                        for block in path.read_text().split('\n\n')]
            for d in packages:
                if 'P' in d:
                    d['repository'] = repo; lookup[d['P']] = d
            for d in packages:
                for provided in d.get('p', '').split():
                    lookup.setdefault(re.split('[=<>~]', provided)[0], d)
        chosen = {}

        def visit(name):
            if name.startswith('!'):
                return
            d = lookup[re.split('[=<>~]', name)[0]]
            if d['P'] in chosen:
                return
            chosen[d['P']] = dict(d)
            for dependency in d.get('D', '').split():
                visit(dependency)

        for name in ('git', 'git-init-template', 'curl', 'openssh-client-default', 'nodejs', 'npm', 'build-base', 'python3-dev', 'linux-headers', 'pkgconf'):
            visit(name)
        lock = {'repository': URL, 'index_sha256': indices,
                'architecture': 'aarch64', 'packages': sorted(chosen.values(), key=lambda d: d['P'])}
    else:
        lock = json.loads(LOCK.read_text())
    root = BASE / 'layer'
    shutil.rmtree(root, ignore_errors=True)
    root.mkdir()
    for d in lock['packages']:
        filename = f"{d['P']}-{d['V']}.apk"
        source = BASE / filename
        url = 'https://dl-cdn.alpinelinux.org/alpine/v3.22/' + d.get('repository', 'main') + '/aarch64/' + filename
        if not source.exists():
            subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--retry', '2',
                            '--max-time', '90', '--output', str(source), url], check=True)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if not args.resolve and digest != d['sha256']:
            raise RuntimeError('APK checksum mismatch: ' + filename)
        d['sha256'] = digest
        d['url'] = url
        # Base Python stays untouched; musl also lives in this layer as the compiler sysroot.
        # Actual executable ELF interpreter still resolves to the unchanged base /lib.
        d['included'] = d['P'] != 'python3'
        if d['included']:
            with tarfile.open(fileobj=io.BytesIO(gzip.decompress(source.read_bytes())), ignore_zeros=True) as tar:
                members = [m for m in tar.getmembers() if not m.name.startswith('.')]
                for member in members:
                    if member.issym() and member.linkname.startswith('/'):
                        member.linkname = posixpath.relpath(member.linkname.lstrip('/'), posixpath.dirname(member.name))
                tar.extractall(root, members=members, filter='data')
        print(filename, flush=True)
    # GCC is relocatable, but its distribution's default /usr include/link paths
    # need an explicit sysroot. Keep the base rootfs untouched and preserve argv.
    prefixes = list((root / 'usr/lib/gcc/aarch64-alpine-linux-musl').iterdir())
    if len(prefixes) != 1: raise RuntimeError('Ambiguous GCC version')
    version = prefixes[0].name
    wrappers = root / 'capri-bin'; wrappers.mkdir()
    for name, binary in [('cc', 'gcc'), ('gcc', 'gcc'), ('c++', 'g++'), ('g++', 'g++'), ('cpp', 'cpp')]:
        wrapper = wrappers / name
        wrapper.write_text('#!/bin/sh\nexec /opt/capri/tools/usr/bin/' + binary +
            ' --sysroot=/opt/capri/tools -B/opt/capri/tools/usr/lib/gcc/aarch64-alpine-linux-musl/' + version +
            '/ -B/opt/capri/tools/usr/bin/ "$@"\n')
        wrapper.chmod(0o755)
    # Asset identity is derived from the pinned package set. No timestamps/host ownership in the bundle.
    metadata = json.dumps(lock, indent=2) + '\n'
    (root / 'manifest.json').write_text(metadata)
    assets = ROOT / 'app/src/main/assets/runtime'
    asset = BASE / 'devtools.bundle'
    with asset.open('wb') as output, gzip.GzipFile(fileobj=output, mode='wb', filename='', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as tar:
            for p in sorted(root.rglob('*')):
                entry = tar.gettarinfo(p, arcname=str(p.relative_to(root)))
                entry.uid = entry.gid = entry.mtime = 0
                entry.uname = entry.gname = ''
                # Android SELinux can deny link(2) even inside app-owned directories.
                # Preserve executable aliases as relative symlinks, never tar hardlinks.
                if entry.islnk():
                    entry.type = tarfile.SYMTYPE
                    entry.linkname = posixpath.relpath(entry.linkname, posixpath.dirname(entry.name))
                    entry.size = 0
                if entry.isfile():
                    with p.open('rb') as content:
                        tar.addfile(entry, content)
                else:
                    tar.addfile(entry)
    LOCK.write_text(metadata)
    sha = hashlib.sha256(asset.read_bytes()).hexdigest()
    # Small repository files, one logical gzip stream/checksum at runtime.
    for old in assets.glob('devtools*.bundle'): old.unlink()
    parts = []
    with asset.open('rb') as source:
        while chunk := source.read(32 * 1024 * 1024):
            name = 'devtools-%03d.bundle' % (len(parts) + 1)
            (assets / name).write_bytes(chunk); parts.append(name)
    (assets / 'devtools.parts.json').write_text(json.dumps(parts, indent=2)+'\n')
    (assets / 'devtools.sha256').write_text(sha + '\n')
    hashes = ROOT / 'docs/runtime-sha256.json'
    values = json.loads(hashes.read_text())
    for path in list(values):
        if path.startswith('app/src/main/assets/runtime/devtools') and path.endswith('.bundle'): del values[path]
    for name in parts + ['devtools.parts.json']:
        path = assets / name
        values[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    hashes.write_text(json.dumps(values, indent=2) + '\n')
    print('BUNDLED', asset.stat().st_size, sha)


if __name__ == '__main__':
    main()

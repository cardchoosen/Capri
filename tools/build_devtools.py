"""Build the pinned ARM64 Git/curl/OpenSSH layer without modifying the Python rootfs.

First resolve: python3 tools/build_devtools.py --resolve work/devtools-build/APKINDEX
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

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'work/devtools-build'
LOCK = ROOT / 'docs/devtools-packages.json'
URL = 'https://dl-cdn.alpinelinux.org/alpine/v3.22/main/aarch64/'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resolve', type=Path)
    args = parser.parse_args()
    BASE.mkdir(parents=True, exist_ok=True)
    if args.resolve:
        text = args.resolve.read_text()
        packages = [dict(line.split(':', 1) for line in block.splitlines() if ':' in line)
                    for block in text.split('\n\n')]
        lookup = {d['P']: d for d in packages if 'P' in d}
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

        for name in ('git', 'git-init-template', 'curl', 'openssh-client-default'):
            visit(name)
        lock = {'repository': URL, 'index_sha256': hashlib.sha256(args.resolve.read_bytes()).hexdigest(),
                'architecture': 'aarch64', 'packages': sorted(chosen.values(), key=lambda d: d['P'])}
    else:
        lock = json.loads(LOCK.read_text())
    root = BASE / 'layer'
    shutil.rmtree(root, ignore_errors=True)
    root.mkdir()
    for d in lock['packages']:
        filename = f"{d['P']}-{d['V']}.apk"
        source = BASE / filename
        if not source.exists():
            subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', '--retry', '2',
                            '--max-time', '90', '--output', str(source), lock['repository'] + filename], check=True)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        if not args.resolve and digest != d['sha256']:
            raise RuntimeError('APK checksum mismatch: ' + filename)
        d['sha256'] = digest
        d['url'] = lock['repository'] + filename
        # The ELF interpreter comes from the unchanged base rootfs, not this layer.
        d['included'] = d['P'] != 'musl'
        if d['included']:
            with tarfile.open(fileobj=io.BytesIO(gzip.decompress(source.read_bytes())), ignore_zeros=True) as tar:
                members = [m for m in tar.getmembers() if not m.name.startswith('.')]
                for member in members:
                    if member.issym() and member.linkname.startswith('/'):
                        member.linkname = posixpath.relpath(member.linkname.lstrip('/'), posixpath.dirname(member.name))
                tar.extractall(root, members=members, filter='data')
        print(filename, flush=True)
    # Asset identity is derived from the pinned package set. No timestamps/host ownership in the bundle.
    metadata = json.dumps(lock, indent=2) + '\n'
    (root / 'manifest.json').write_text(metadata)
    asset = ROOT / 'app/src/main/assets/runtime/devtools.bundle'
    with asset.open('wb') as output, gzip.GzipFile(fileobj=output, mode='wb', filename='', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as tar:
            for p in sorted(root.rglob('*')):
                entry = tar.gettarinfo(p, arcname=str(p.relative_to(root)))
                entry.uid = entry.gid = entry.mtime = 0
                entry.uname = entry.gname = ''
                if entry.isfile():
                    with p.open('rb') as content:
                        tar.addfile(entry, content)
                else:
                    tar.addfile(entry)
    LOCK.write_text(metadata)
    sha = hashlib.sha256(asset.read_bytes()).hexdigest()
    (ROOT / 'app/src/main/assets/runtime/devtools.sha256').write_text(sha + '\n')
    hashes = ROOT / 'docs/runtime-sha256.json'
    values = json.loads(hashes.read_text())
    values[str(asset.relative_to(ROOT))] = sha
    hashes.write_text(json.dumps(values, indent=2) + '\n')
    print('BUNDLED', asset.stat().st_size, sha)


if __name__ == '__main__':
    main()
